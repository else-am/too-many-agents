package toomanyagents.screens;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Bounded background file work. URLs describe a page; only a native grant authorizes its data. */
final class FilePages {
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), task -> { var thread = new Thread(task, "screen-files"); thread.setDaemon(true); return thread; });
    private static final Map<ScreenView.Access, Set<CompletableFuture<?>>> requests = new ConcurrentHashMap<>();
    private static final Map<ScreenView.Access, FileData> sessions = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService CLEANUP = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "screen-files-cleanup"); thread.setDaemon(true); return thread;
    });
    static { CLEANUP.scheduleWithFixedDelay(() -> sessions.values().forEach(FileData::expire), 15, 15, TimeUnit.SECONDS); }
    record Opened(ConfinedFiles.Root root, String url, String notice) {}

    private FilePages() {}

    static <T> CompletableFuture<T> submit(Callable<T> work) {
        var result = new CompletableFuture<T>();
        var task = new FutureTask<Void>(() -> {
            try { result.complete(work.call()); }
            catch (Exception failure) { result.completeExceptionally(failure); }
            return null;
        });
        result.orTimeout(15, TimeUnit.SECONDS).whenComplete((value, failure) -> {
            if (failure != null) task.cancel(true);
            WORK.remove(task);
        });
        try { WORK.execute(task); }
        catch (RejectedExecutionException failure) { result.completeExceptionally(new IOException("Files are busy. Try again shortly.")); }
        return result;
    }

    static boolean location(String url) {
        try { var uri = URI.create(url); return "tma".equalsIgnoreCase(uri.getScheme()) && "files".equalsIgnoreCase(uri.getHost()) && !uri.getPath().startsWith("/_app/") && !uri.getPath().startsWith("/_api/"); }
        catch (IllegalArgumentException | NullPointerException ignored) { return false; }
    }

    static boolean picker(String url) {
        if (!location(url)) return false;
        try { return query(URI.create(url)).containsKey("pick"); }
        catch (RuntimeException invalid) { return false; }
    }

    static boolean sameRoot(ScreenView.Access access, String url) {
        try { return access.root() == null || path(url).toString().equals(access.root().path()); }
        catch (RuntimeException invalid) { return false; }
    }

    static Path path(String url) {
        String path = URI.create(url).getPath();
        if (System.getProperty("os.name").startsWith("Windows") && path.matches("^/[a-zA-Z]:/.*")) path = path.substring(1);
        return Path.of(path).toAbsolutePath().normalize();
    }

    static String typedPath(String value) {
        String text = value.strip();
        if (text.equals("~") || text.startsWith("~/") || text.startsWith("~\\"))
            text = System.getProperty("user.home") + text.substring(1);
        if (text.startsWith("\\\\")) return "";
        try { return Path.of(text).isAbsolute() ? Builtin.files(Path.of(text).normalize()) : ""; }
        catch (RuntimeException ignored) { return ""; }
    }

    /** Called only for native human intent, or a human-owned saved location after world trust. */
    static Opened grant(String url) throws IOException { return grant(url, null); }

    /**
     * With an environment, everything resolves inside it: links within it work, others are outside, and a file's
     * repository root is used only when it lies within the environment, which is otherwise the root.
     */
    static Opened grant(String url, ConfinedFiles.Root environment) throws IOException {
        if (!location(url)) throw new IOException("This is not a files location.");
        Path wanted = path(url);
        var query = query(URI.create(url));
        if (environment != null) query.remove("pick");
        ConfinedFiles.Root root;
        String selected = query.getOrDefault("open", "");
        String notice = "";
        try { root = environment == null ? ConfinedFiles.grant(wanted) : folder(environment, wanted); }
        catch (IOException notFolder) {
            if (wanted.getParent() == null) throw notFolder;
            var scope = environment != null ? environment : ConfinedFiles.grant(wanted.getParent());
            var file = ConfinedFiles.info(scope, wanted.toString());
            if (file.kind() != ConfinedFiles.Kind.FILE) throw notFolder;
            root = ConfinedFiles.grantExact(Path.of(file.path()).getParent());
            try {
                var repository = FileRepository.discover(root);
                if (repository != null) {
                    Path top = Path.of(repository.repositoryRoot());
                    root = environment == null || top.startsWith(environment.path()) ? ConfinedFiles.grantExact(top) : environment;
                }
            } catch (IOException unavailable) {
                notice = "Git unavailable - showing the file's folder. " + unavailable.getMessage();
            }
            selected = ConfinedFiles.info(root, file.path()).relative();
            query.remove("pick");
        }
        if (!selected.isEmpty()) {
            Path relative = Path.of(selected).normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) throw new IOException("Selection is outside the viewed folder.");
            try { selected = ConfinedFiles.info(root, relative.toString()).relative(); }
            catch (java.nio.file.NoSuchFileException deleted) {
                selected = java.io.File.separatorChar == '\\' ? relative.toString().replace('\\', '/') : relative.toString();
            }
        }
        String normalized = Builtin.files(Path.of(root.path()));
        var params = new java.util.ArrayList<String>();
        if (query.containsKey("pick")) params.add("pick");
        // Presentation only: the picker's hidden-files choice, kept while browsing.
        if (query.containsKey("pick") && query.containsKey("hidden")) params.add("hidden");
        if (!selected.isEmpty()) params.add("open=" + encode(selected));
        for (String key : new String[] {"mode", "base"}) if (query.containsKey(key)) params.add(key + "=" + encode(query.get(key)));
        if (!params.isEmpty()) normalized += "?" + String.join("&", params);
        if (URI.create(url).getRawFragment() != null) normalized += "#" + URI.create(url).getRawFragment();
        return new Opened(root, normalized, notice);
    }

    /** A folder inside the environment, pinned at the real path it resolves to there. */
    private static ConfinedFiles.Root folder(ConfinedFiles.Root environment, Path path) throws IOException {
        var info = ConfinedFiles.info(environment, path.toString());
        if (info.kind() != ConfinedFiles.Kind.DIRECTORY) throw new java.nio.file.FileSystemException(path.toString(), null, "not a folder");
        return ConfinedFiles.grantExact(Path.of(info.path()));
    }

    static String encode(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    static Map<String, String> query(URI uri) {
        var result = new HashMap<String, String>();
        if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
            var parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    static boolean current(ScreenView view, ScreenView.Access access) {
        return view.access == access && view.browser == access.browser() && view.generation == access.generation();
    }

    static void cancel(ScreenView.Access access) {
        if (access == null) return;
        FileWatches.cancel(access);
        var session = sessions.remove(access);
        if (session != null) session.invalidate();
        var pending = requests.remove(access);
        if (pending != null) for (var request : pending) request.cancel(true);
        if (session != null) CLEANUP.execute(session::close);
    }

    static CompletableFuture<String> request(ScreenView view, ScreenView.Access access, String url) {
        if (!current(view, access)) return CompletableFuture.failedFuture(new IOException("The page changed."));
        if (access.root() == null)
            return CompletableFuture.failedFuture(new IOException("This view has no file access. Use the path button or address bar to open a folder yourself."));
        var session = sessions.computeIfAbsent(access, ignored -> new FileData(access.root()));
        var pending = requests.computeIfAbsent(access, ignored -> ConcurrentHashMap.newKeySet());
        var result = submit(() -> {
            if (!current(view, access)) throw new IOException("The page changed.");
            JsonObject value = session.data(URI.create(url), access.notice());
            if (URI.create(url).getPath().equals("/_api/state")) {
                value.addProperty("document", access.document());
                value.addProperty("human", access.provenance() == ScreenView.Provenance.HUMAN);
            }
            FileWatches.inputs(view, access, session.watchInputs(), session.watchInputsLimited());
            if (!current(view, access)) throw new IOException("The page changed.");
            return value.toString();
        });
        pending.add(result);
        result.whenComplete((value, failure) -> { pending.remove(result); });
        if (!current(view, access)) {
            result.cancel(true);
            cancel(access);
        }
        return result;
    }

    /** Invalidation retains the subscription and grant, but discards derived state before the page refetches. */
    static CompletableFuture<Void> changed(ScreenView view, ScreenView.Access access, boolean structure, boolean git, boolean rules) {
        var pending = requests.computeIfAbsent(access, ignored -> ConcurrentHashMap.newKeySet());
        CompletableFuture<Void> result = submit(() -> {
            if (!current(view, access)) throw new IOException("The page changed.");
            var session = sessions.get(access);
            if (session != null) session.changed(structure, git, rules);
            if (!current(view, access)) throw new IOException("The page changed.");
            return null;
        });
        pending.add(result);
        result.whenComplete((ignored, failure) -> pending.remove(result));
        if (!current(view, access)) { result.cancel(true); cancel(access); }
        return result;
    }

    static void sleeping(ScreenView.Access access) {
        if (access == null) return;
        var pending = requests.get(access);
        if (pending != null) for (var request : pending) request.cancel(true);
    }
}
