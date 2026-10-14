package toomanyagents.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Memory;
import com.sun.jna.platform.mac.CoreFoundation;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;

/**
 * Shared native notifications are hints; subscriptions and every refresh retain their exact file grant.
 * JCEF 17e805a's CefQueryCallback_N.cpp clears its native callback after Success even for persistent queries.
 * Use one-response queries; keep pending changes and native watches through a bounded rearm gap.
 */
final class FileWatches {
    private static final int MAX_GROUPS = 32, MAX_SUBSCRIPTIONS = 64, MAX_INPUTS = 512, MAX_EVENTS = 256;
    private static final Semaphore THREADS = new Semaphore(MAX_GROUPS);
    private static final int CONTENT = 1, STRUCTURE = 2, GIT = 4, RULES = 8, ALL = 15;
    private static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");
    private static final ScheduledExecutorService LOOP = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "screen-file-watches"));
    // All maps belong to LOOP. Native callbacks see only immutable snapshots and bounded dirty bits.
    private static final Map<ScreenView.Access, Subscription> subscriptions = new HashMap<>();
    private static final Map<String, Group> groups = new HashMap<>();
    private static final Map<ScreenView.Access, List<FileWatchInput>> checked = new ConcurrentHashMap<>();
    private static volatile int groupCount, subscriptionCount, limitedCount;
    private static volatile long revision;
    static {
        LOOP.scheduleWithFixedDelay(FileWatches::flush, 100, 100, TimeUnit.MILLISECONDS);
        LOOP.scheduleWithFixedDelay(() -> {
            subscriptions.values().forEach(s -> { if (s.active()) s.dirty(ALL, null, true); });
            rebuild();
        }, 30, 30, TimeUnit.SECONDS);
    }

    private FileWatches() {}
    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name); thread.setDaemon(true); return thread;
    }
    private record Interest(FileWatchInput input, String path, String directory) {}
    private record Target(Subscription subscription, Interest interest) {}

    private static final class Subscription {
        final ScreenView view;
        final ScreenView.Access access;
        List<Interest> inputs = List.of();
        CefQueryCallback callback;
        long query;
        boolean awake, subscribed, limited, inputsLimited, busy, wildcard;
        long retryAfter, rearmUntil;
        int dirty;
        boolean reconcile;
        final Set<String> content = new LinkedHashSet<>();
        Subscription(ScreenView view, ScreenView.Access access) { this.view = view; this.access = access; awake = view.live(); }
        boolean active() { return awake && subscribed && FilePages.current(view, access); }
        synchronized void dirty(int kind, String path, boolean full) {
            dirty |= kind; reconcile |= full;
            if ((kind & CONTENT) != 0 && path == null) wildcard = true;
            if (path != null && content.size() < MAX_INPUTS) content.add(path);
        }
    }

    /** Runs on the bounded file worker, after the backend has verified its interests. */
    static void inputs(ScreenView view, ScreenView.Access access, List<FileWatchInput> wanted, boolean limited) {
        if (!FilePages.current(view, access) || wanted.equals(checked.get(access))) return;
        var verified = new ArrayList<Interest>();
        var ordered = new ArrayList<>(wanted);
        ordered.sort(Comparator.comparingInt(i -> switch (i.kind()) {
            case CONTENT -> 0; case GIT -> 1; case RULES -> 2; case STRUCTURE -> 3;
        }));
        for (var input : ordered.subList(0, Math.min(MAX_INPUTS, ordered.size()))) {
            if (verified.size() >= MAX_INPUTS) { limited = true; break; }
            try {
                Path requested = Path.of(input.root().path()).resolve(input.path()).normalize();
                Path directory = input.directory() ? requested : requested.getParent();
                // An expected metadata directory can be absent; watch its nearest confined existing parent.
                while (directory != null && directory.startsWith(Path.of(input.root().path()))) {
                    try {
                        var info = ConfinedFiles.info(input.root(), directory.toString());
                        if (info.kind() != ConfinedFiles.Kind.DIRECTORY) break;
                        verified.add(new Interest(input, requested.toString(), info.path()));
                        // In-root aliases keep their requested name, but changes can arrive at the real target.
                        try {
                            var target = ConfinedFiles.info(input.root(), requested.toString());
                            if (!target.path().equals(requested.toString()) && verified.size() < MAX_INPUTS) {
                                String parent = target.kind() == ConfinedFiles.Kind.DIRECTORY ? target.path() : Path.of(target.path()).getParent().toString();
                                verified.add(new Interest(input, target.path(), parent));
                            }
                        } catch (IOException unavailable) { /* The alias's parent still observes its replacement. */ }
                        break;
                    } catch (NoSuchFileException missing) { directory = directory.getParent(); }
                }
            } catch (IOException | IllegalArgumentException unavailable) { limited = true; }
        }
        boolean finalLimited = limited || wanted.size() > MAX_INPUTS;
        LOOP.execute(() -> {
            if (!FilePages.current(view, access)) return;
            if (!subscriptions.containsKey(access) && subscriptions.size() >= MAX_SUBSCRIPTIONS) return;
            var sub = subscriptions.computeIfAbsent(access, ignored -> new Subscription(view, access));
            verified.sort(Comparator.comparingInt(i -> switch (i.input.kind()) {
                case CONTENT -> 0; case GIT -> 1; case RULES -> 2; case STRUCTURE -> 3;
            }));
            sub.inputs = List.copyOf(verified);
            checked.put(access, List.copyOf(wanted));
            sub.inputsLimited = finalLimited;
            rebuild();
        });
    }

    static void subscribe(ScreenView view, ScreenView.Access access, long query, CefQueryCallback callback) {
        LOOP.execute(() -> {
            if (!FilePages.current(view, access) || access.root() == null) {
                fail(callback, "The files page changed or has no file access."); return;
            }
            if (!subscriptions.containsKey(access) && subscriptions.size() >= MAX_SUBSCRIPTIONS) {
                fail(callback, "Too many files pages are watching. Close another files page and retry."); return;
            }
            var sub = subscriptions.computeIfAbsent(access, ignored -> new Subscription(view, access));
            if (sub.callback != null) fail(sub.callback, "A newer files subscription replaced this one.");
            sub.query = query; sub.callback = callback; sub.rearmUntil = 0;
            if (!sub.subscribed) sub.dirty(ALL, null, true);
            sub.subscribed = true;
            rebuild();
        });
    }

    static void queryCancelled(CefBrowser browser, long query) {
        LOOP.execute(() -> {
            for (var sub : subscriptions.values()) if (sub.access.browser() == browser && sub.query == query) {
                sub.callback = null; sub.subscribed = false; sub.rearmUntil = 0;
            }
            rebuild();
        });
    }

    static void live(ScreenView.Access access, boolean awake) {
        if (access == null) return;
        LOOP.execute(() -> {
            var sub = subscriptions.get(access);
            if (sub == null) return;
            sub.awake = awake;
            if (awake) {
                sub.dirty(ALL, null, true);
                if (sub.subscribed && sub.callback == null) sub.rearmUntil = System.nanoTime() + 5_000_000_000L;
            }
            rebuild();
        });
    }

    static void cancel(ScreenView.Access access) {
        if (access == null) return;
        LOOP.execute(() -> {
            var sub = subscriptions.remove(access);
            checked.remove(access);
            if (sub != null && sub.callback != null) fail(sub.callback, "The files page changed.");
            rebuild();
        });
    }

    private static void fail(CefQueryCallback callback, String message) {
        try { callback.failure(1, message); } catch (RuntimeException closed) { /* Native query already ended. */ }
    }

    private static void rebuild() {
        subscriptions.values().removeIf(s -> !FilePages.current(s.view, s.access));
        checked.keySet().retainAll(subscriptions.keySet());
        var desired = new LinkedHashMap<String, List<Target>>();
        for (var sub : subscriptions.values()) {
            sub.limited = sub.inputsLimited;
            if (!sub.active()) continue;
            for (var input : sub.inputs) {
                // One recursive stream covers verified directories inside the viewed root, including .git.
                // This shares notifications only; each interest retains its original grant and cancellation identity.
                String path = MAC && Path.of(input.directory).startsWith(Path.of(sub.access.root().path()))
                    ? sub.access.root().path() : input.directory;
                // A global config or missing ancestor .git must not recursively watch the whole home/volume.
                if (MAC && !path.equals(sub.access.root().path()) && Path.of(sub.access.root().path()).startsWith(Path.of(path))) {
                    continue; // These rare broad ancestor changes are reconciled quietly every 30 seconds.
                }
                if (!desired.containsKey(path) && desired.size() >= MAX_GROUPS) { sub.limited = true; continue; }
                desired.computeIfAbsent(path, ignored -> new ArrayList<>()).add(new Target(sub, input));
            }
        }
        for (var iterator = groups.entrySet().iterator(); iterator.hasNext();) {
            var group = iterator.next();
            if (!desired.containsKey(group.getKey())) { group.getValue().stop(); iterator.remove(); }
        }
        for (var entry : desired.entrySet()) {
            var group = groups.get(entry.getKey());
            if (group == null) {
                group = new Group(entry.getKey());
                groups.put(entry.getKey(), group);
                group.targets = List.copyOf(entry.getValue());
                group.start();
            } else {
                group.targets = List.copyOf(entry.getValue());
                if (!group.running) group.start();
            }
        }
        groupCount = groups.size(); subscriptionCount = (int) subscriptions.values().stream().filter(s -> s.subscribed).count();
        limitedCount = (int) subscriptions.values().stream().filter(s -> s.limited).count();
    }

    private static long flushRound;
    private static void flush() {
        limitedCount = (int) subscriptions.values().stream().filter(s -> s.limited).count();
        var work = new ArrayList<>(subscriptions.values());
        if (!work.isEmpty()) Collections.rotate(work, -(int) (flushRound++ % work.size()));
        boolean expired = false;
        for (var sub : work) {
            if (sub.active() && sub.callback == null && sub.rearmUntil != 0 && System.nanoTime() >= sub.rearmUntil) {
                sub.subscribed = false; sub.rearmUntil = 0; expired = true;
            }
            if (!sub.active() || sub.callback == null || sub.busy || System.nanoTime() < sub.retryAfter) continue;
            final int dirty;
            final boolean reconcile;
            final Set<String> paths;
            synchronized (sub) {
                if (sub.dirty == 0) continue;
                dirty = sub.dirty; reconcile = sub.reconcile; paths = sub.wildcard ? Set.of() : Set.copyOf(sub.content);
                sub.dirty = 0; sub.reconcile = false; sub.wildcard = false; sub.content.clear();
            }
            sub.busy = true;
            if (reconcile || (dirty & (STRUCTURE | GIT | RULES)) != 0) checked.remove(sub.access);
            var callback = sub.callback;
            FilePages.changed(sub.view, sub.access, (dirty & STRUCTURE) != 0, (dirty & GIT) != 0, (dirty & RULES) != 0)
                .whenComplete((ignored, failure) -> LOOP.execute(() -> {
                    sub.busy = false;
                    if (!sub.active()) return;
                    if (sub.callback != callback) { sub.dirty(dirty, null, true); return; }
                    if (failure != null) { sub.limited = true; sub.retryAfter = System.nanoTime() + 1_000_000_000L; sub.dirty(dirty, null, true); return; }
                    var value = new JsonObject();
                    value.addProperty("revision", ++revision);
                    value.addProperty("content", (dirty & CONTENT) != 0);
                    value.addProperty("structure", (dirty & STRUCTURE) != 0);
                    value.addProperty("git", (dirty & GIT) != 0);
                    value.addProperty("rules", (dirty & RULES) != 0);
                    value.addProperty("reconcile", reconcile);
                    value.addProperty("limited", sub.limited);
                    var affected = new JsonArray(); paths.forEach(affected::add); value.add("paths", affected);
                    sub.callback = null;
                    sub.rearmUntil = System.nanoTime() + 5_000_000_000L;
                    try { callback.success(value.toString()); }
                    catch (RuntimeException closed) { sub.subscribed = false; sub.rearmUntil = 0; rebuild(); }
                }));
        }
        if (expired) rebuild();
    }

    static JsonObject diagnostics() {
        var value = new JsonObject(); value.addProperty("groups", groupCount);
        value.addProperty("subscriptions", subscriptionCount); value.addProperty("limited", limitedCount);
        value.addProperty("revision", revision); value.addProperty("groupLimit", MAX_GROUPS);
        value.addProperty("threads", MAX_GROUPS - THREADS.availablePermits()); return value;
    }

    private static final class Group {
        final String path;
        final AtomicBoolean stopped = new AtomicBoolean(), restartQueued = new AtomicBoolean();
        volatile List<Target> targets = List.of();
        volatile WatchService service;
        volatile boolean running;
        Group(String path) { this.path = path; }
        void start() {
            if (stopped.get() || running) return;
            if (!THREADS.tryAcquire()) { targets.forEach(t -> t.subscription.limited = true); return; }
            running = true;
            daemon(() -> {
            try { if (MAC) mac(); else portable(); }
            catch (Exception | LinkageError failure) {
                if (!stopped.get()) LOOP.execute(() -> {
                    targets.forEach(t -> { t.subscription.limited = true; t.subscription.dirty(ALL, null, true); });
                });
            } finally { running = false; THREADS.release(); }
        }, "screen-file-events").start(); }
        void stop() {
            stopped.set(true);
            var active = service;
            if (active != null) try { active.close(); } catch (IOException ignored) {}
        }
        void event(String changed, boolean structural, boolean full) {
            if (stopped.get()) return;
            for (var target : targets) {
                var interest = target.interest;
                boolean match = full || changed.equals(interest.path)
                    || interest.input.directory() && Path.of(changed).startsWith(Path.of(interest.path));
                if (!full && interest.input.kind() == FileWatchInput.Kind.STRUCTURE
                    && !changed.equals(interest.path) && !Objects.equals(Path.of(changed).getParent(), Path.of(interest.path))) match = false;
                if (!match) continue;
                int kind = switch (interest.input.kind()) {
                    case CONTENT -> CONTENT;
                    case STRUCTURE -> structural || full ? STRUCTURE : CONTENT;
                    case GIT -> GIT;
                    case RULES -> RULES;
                };
                if (kind != 0) target.subscription.dirty(kind,
                    interest.input.kind() == FileWatchInput.Kind.CONTENT ? interest.input.path() : null, full);
            }
        }
        void portable() throws Exception {
            try (var watch = FileSystems.getDefault().newWatchService()) {
                service = watch;
                if (stopped.get()) return;
                Path directory = Path.of(path);
                directory.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                event(path, true, true);
                while (!stopped.get()) {
                    var key = watch.take();
                    var events = key.pollEvents();
                    if (events.size() > MAX_EVENTS) event(path, true, true);
                    else for (var change : events) {
                        if (change.kind() == StandardWatchEventKinds.OVERFLOW) event(path, true, true);
                        else if (change.context() instanceof Path name)
                            event(directory.resolve(name).toString(), change.kind() != StandardWatchEventKinds.ENTRY_MODIFY, false);
                    }
                    if (!key.reset()) { event(path, true, true); break; }
                }
            } finally { service = null; }
        }
        void mac() throws Exception {
            var cf = CoreFoundation.INSTANCE;
            var api = MacEvents.INSTANCE;
            var loop = MacLoop.INSTANCE;
            var root = CoreFoundation.CFStringRef.createCFString(path);
            var mode = CoreFoundation.CFStringRef.createCFString("kCFRunLoopDefaultMode");
            var pointers = new Memory(Native.POINTER_SIZE); pointers.setPointer(0, root.getPointer());
            var roots = cf.CFArrayCreate(null, pointers, new CoreFoundation.CFIndex(1), null);
            Pointer stream = null, runLoop = null;
            boolean scheduled = false, started = false;
            // Strongly retained through stream release. No native callback stats a path or retains event names.
            MacEvents.Events callback = (ignored, context, count, paths, flags, ids) -> {
                try {
                    long length = count.longValue();
                    if (length > MAX_EVENTS || length < 0) { event(path, true, true); return; }
                    for (int i = 0; i < length; i++) {
                        int flag = flags.getInt((long) i * 4);
                        // MustScanSubDirs, dropped events, wrapped ids, root moved, mount/unmount.
                        if ((flag & 0xef) != 0) {
                            event(path, true, true);
                            if ((flag & 0x20) != 0 && restartQueued.compareAndSet(false, true)) LOOP.execute(() -> {
                                if (groups.get(path) != this) return;
                                // A moved/replaced root must be verified again by the confined file worker.
                                targets.forEach(t -> { t.subscription.inputs = List.of(); checked.remove(t.subscription.access); });
                                stop(); groups.remove(path); rebuild();
                            });
                            continue;
                        }
                        String name = paths.getPointer((long) i * Native.POINTER_SIZE).getString(0, "UTF-8");
                        event(name, (flag & (0x100 | 0x200 | 0x800)) != 0, false);
                    }
                } catch (Throwable failure) { event(path, true, true); }
            };
            try {
                if (stopped.get()) return;
                // NoDefer | WatchRoot | FileEvents. FSEvents observes recursively without walking directories.
                stream = api.FSEventStreamCreate(null, callback, null, roots.getPointer(), -1L, .05, 0x16);
                if (stream == null) throw new IOException("Could not create file watcher.");
                runLoop = loop.CFRunLoopGetCurrent();
                api.FSEventStreamScheduleWithRunLoop(stream, runLoop, mode.getPointer()); scheduled = true;
                started = api.FSEventStreamStart(stream) != 0;
                if (!started) throw new IOException("Could not start file watcher.");
                event(path, true, true);
                // Bounded runs avoid a stop-before-CFRunLoopRun race; cleanup belongs to this thread.
                while (!stopped.get()) loop.CFRunLoopRunInMode(mode.getPointer(), .1, (byte) 1);
            } finally {
                if (stream != null) {
                    if (started) api.FSEventStreamStop(stream);
                    if (scheduled) api.FSEventStreamUnscheduleFromRunLoop(stream, runLoop, mode.getPointer());
                    api.FSEventStreamInvalidate(stream); api.FSEventStreamRelease(stream);
                }
                java.lang.ref.Reference.reachabilityFence(callback);
                roots.release(); mode.release(); root.release(); pointers.close();
            }
        }
    }

    // Apple's FSEvents API: https://developer.apple.com/library/archive/documentation/Darwin/Conceptual/FSEvents_ProgGuide/
    private interface MacEvents extends Library {
        MacEvents INSTANCE = Native.load("CoreServices", MacEvents.class);
        interface Events extends Callback { void invoke(Pointer stream, Pointer context, NativeLong count, Pointer paths, Pointer flags, Pointer ids); }
        Pointer FSEventStreamCreate(Pointer allocator, Events callback, Pointer context, Pointer paths, long since, double latency, int flags);
        byte FSEventStreamStart(Pointer stream);
        void FSEventStreamScheduleWithRunLoop(Pointer stream, Pointer loop, Pointer mode);
        void FSEventStreamUnscheduleFromRunLoop(Pointer stream, Pointer loop, Pointer mode);
        void FSEventStreamStop(Pointer stream);
        void FSEventStreamInvalidate(Pointer stream);
        void FSEventStreamRelease(Pointer stream);
    }
    private interface MacLoop extends Library {
        MacLoop INSTANCE = Native.load("CoreFoundation", MacLoop.class);
        Pointer CFRunLoopGetCurrent();
        int CFRunLoopRunInMode(Pointer mode, double seconds, byte returnAfterSourceHandled);
    }
}
