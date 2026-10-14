package toomanyagents.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** Bounded background file work. URLs describe a page; only a native grant authorizes its data. */
final class FilePages {
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), task -> { var thread = new Thread(task, "screen-files"); thread.setDaemon(true); return thread; });
    private static final Map<ScreenView.Access, Set<CompletableFuture<?>>> requests = new ConcurrentHashMap<>();
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
    static Opened grant(String url) throws IOException {
        if (!location(url)) throw new IOException("This is not a files location.");
        Path wanted = path(url);
        var query = query(URI.create(url));
        ConfinedFiles.Root root;
        String selected = query.getOrDefault("open", "");
        String notice = "";
        try { root = ConfinedFiles.grant(wanted); }
        catch (IOException notFolder) {
            if (wanted.getParent() == null) throw notFolder;
            root = ConfinedFiles.grant(wanted.getParent());
            var file = ConfinedFiles.info(root, wanted.getFileName().toString());
            if (file.kind() != ConfinedFiles.Kind.FILE) throw notFolder;
            try {
                var repository = FileRepository.discover(root);
                if (repository != null) root = ConfinedFiles.grantExact(Path.of(repository.repositoryRoot()));
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
        if (!selected.isEmpty()) params.add("open=" + encode(selected));
        for (String key : new String[] {"mode", "base"}) if (query.containsKey(key)) params.add(key + "=" + encode(query.get(key)));
        if (!params.isEmpty()) normalized += "?" + String.join("&", params);
        if (URI.create(url).getRawFragment() != null) normalized += "#" + URI.create(url).getRawFragment();
        return new Opened(root, normalized, notice);
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
        var pending = requests.remove(access);
        if (pending != null) for (var request : pending) request.cancel(true);
    }

    static CompletableFuture<String> request(ScreenView view, ScreenView.Access access, String url) {
        if (!current(view, access)) return CompletableFuture.failedFuture(new IOException("The page changed."));
        var pending = requests.computeIfAbsent(access, ignored -> ConcurrentHashMap.newKeySet());
        var result = submit(() -> {
            if (!current(view, access)) throw new IOException("The page changed.");
            JsonObject value = data(access, URI.create(url));
            if (!current(view, access)) throw new IOException("The page changed.");
            return value.toString();
        });
        pending.add(result);
        result.whenComplete((value, failure) -> { pending.remove(result); });
        if (!current(view, access)) result.cancel(true);
        return result;
    }

    private static JsonObject data(ScreenView.Access access, URI uri) throws IOException {
        if (access.root() == null || access.provenance() != ScreenView.Provenance.HUMAN)
            throw new IOException("Use the screen's address bar or Files button to open a folder. Agent file access is not enabled.");
        String operation = uri.getPath();
        var result = new JsonObject();
        result.addProperty("root", access.root().path());
        if (operation.equals("/_api/state")) {
            result.addProperty("home", System.getProperty("user.home"));
            result.addProperty("human", true);
            result.addProperty("notice", access.notice());
            return result;
        }
        String path = query(uri).getOrDefault("path", "");
        // API paths never select a different grant, even if they are absolute or contain '..'.
        if (Path.of(path).isAbsolute() || (java.io.File.separatorChar == '\\' ? path.replace('\\', '/') : path).matches("(^|.*/)\\.\\.(/.*|$)")) throw new IOException("Use a path inside the viewed folder.");
        if (operation.equals("/_api/list")) {
            var listing = ConfinedFiles.list(access.root(), path, 2000);
            result.addProperty("path", listing.info().relative());
            result.addProperty("truncated", listing.truncated());
            var entries = new JsonArray();
            listing.entries().stream().sorted(Comparator.comparing((ConfinedFiles.Entry item) -> item.kind() != ConfinedFiles.Kind.DIRECTORY)
                .thenComparing(ConfinedFiles.Entry::name, String.CASE_INSENSITIVE_ORDER)).forEach(item -> {
                    var entry = new JsonObject();
                    entry.addProperty("name", item.name());
                    entry.addProperty("kind", item.kind().name().toLowerCase(java.util.Locale.ROOT));
                    entry.addProperty("hidden", item.name().startsWith("."));
                    entries.add(entry);
                });
            result.add("entries", entries);
            return result;
        }
        if (operation.equals("/_api/file")) {
            ConfinedFiles.Content file;
            try { file = ConfinedFiles.read(access.root(), path); }
            catch (java.nio.file.NoSuchFileException deleted) {
                result.addProperty("path", path);
                result.addProperty("deleted", true);
                return result;
            }
            result.addProperty("deleted", false);
            result.addProperty("path", file.info().relative());
            result.addProperty("size", file.info().size());
            result.addProperty("tooLarge", file.bytes() == null);
            // Bytes are data, never executable HTML/SVG served on the privileged origin.
            if (file.bytes() != null) result.addProperty("base64", Base64.getEncoder().encodeToString(file.bytes()));
            return result;
        }
        throw new IOException("Unknown files request.");
    }
}
