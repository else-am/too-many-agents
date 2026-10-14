package toomanyagents.screens;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Data and short-lived cursors for one native folder grant. */
final class FileData implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final ConfinedFiles.Root root;
    private final Map<String, Cursor> cursors = new LinkedHashMap<>();
    private final Set<String> notices = new LinkedHashSet<>();
    private FileRepository repository;
    private boolean discovered;
    private volatile boolean closed;
    void invalidate() { closed = true; }
    private record Cursor(String operation, String argument, AutoCloseable value, long touched) {}
    private record Listing(ConfinedFiles.Directory directory, FileRepository.TreeRules rules) implements AutoCloseable {
        public void close() throws IOException { directory.close(); }
    }
    FileData(ConfinedFiles.Root root) { this.root = root; }

    synchronized JsonObject data(URI uri, String grantNotice) throws IOException {
        if (closed) throw new IOException("The page changed.");
        expire();
        if (!discovered) {
            discovered = true;
            try { repository = FileRepository.discover(root); }
            catch (IOException failure) { notices.add("Git unavailable - " + failure.getMessage()); }
        }
        var params = FilePages.query(uri);
        String path = params.getOrDefault("path", "");
        if (path.length() > 4096 || params.getOrDefault("query", "").length() > 4096) throw new IOException("File path or search is too long.");
        if (Path.of(path).isAbsolute() || (java.io.File.separatorChar == '\\' ? path.replace('\\', '/') : path).matches("(^|.*/)\\.\\.(/.*|$)"))
            throw new IOException("Use a path inside the viewed folder.");
        String operation = uri.getPath();
        var result = new JsonObject();
        result.addProperty("root", root.path());
        if (operation.equals("/_api/state")) {
            result.addProperty("home", System.getProperty("user.home")); result.addProperty("human", true);
            result.addProperty("notice", grantNotice); result.addProperty("git", repository != null);
            result.add("notices", JSON.toJsonTree(notices)); return result;
        }
        if (operation.equals("/_api/tree")) {
            String query = params.getOrDefault("query", "");
            FileTreeScan scan = (FileTreeScan) take(params, operation, query);
            if (scan == null) scan = new FileTreeScan(root, repository, query);
            try {
                var page = scan.next();
                result.add("paths", JSON.toJsonTree(page.paths())); result.add("directories", JSON.toJsonTree(page.directories()));
                result.add("ignored", JSON.toJsonTree(page.ignored())); result.addProperty("partial", page.partial());
                var all = new ArrayList<>(notices); all.addAll(page.notices()); result.add("notices", JSON.toJsonTree(all));
                result.addProperty("git", repository != null);
                if (page.done()) scan.close(); else result.addProperty("cursor", keep(operation, query, scan));
                return result;
            } catch (Exception failure) { closeQuietly(scan); throw failure; }
        }
        if (operation.equals("/_api/list")) {
            Listing listing = (Listing) take(params, operation, path);
            if (listing == null) {
                FileRepository.TreeRules rules = null;
                try { rules = repository == null ? null : repository.treeRules(); }
                catch (IOException unavailable) { notices.add("Git ignore rules unavailable - " + unavailable.getMessage()); }
                listing = new Listing(ConfinedFiles.directory(root, path), rules);
            }
            try {
                if (listing.rules() != null) listing.rules().beginPage();
                var batch = listing.directory().next(1000);
                var entries = new JsonArray();
                var sorted = new ArrayList<>(batch.entries());
                sorted.sort(Comparator.comparing((ConfinedFiles.Entry entry) -> entry.kind() != ConfinedFiles.Kind.DIRECTORY)
                    .thenComparing(ConfinedFiles.Entry::name, String.CASE_INSENSITIVE_ORDER));
                for (var entry : sorted) {
                    var item = new JsonObject(); item.addProperty("name", entry.name());
                    String child = path.isEmpty() ? entry.name() : path + "/" + entry.name();
                    var kind = entry.kind();
                    if (kind == ConfinedFiles.Kind.LINK) {
                        try { kind = ConfinedFiles.info(root, child).kind(); }
                        catch (IOException unavailable) { item.addProperty("notice", "Link unavailable or outside the granted folder."); }
                    }
                    item.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
                    item.addProperty("hidden", entry.name().startsWith("."));
                    item.addProperty("ignored", entry.name().equals(".git") || (listing.rules() != null && listing.rules().ignored(child, kind == ConfinedFiles.Kind.DIRECTORY)));
                    entries.add(item);
                }
                result.add("notices", JSON.toJsonTree(notices));
                result.addProperty("path", path); result.add("entries", entries); result.addProperty("truncated", !batch.done());
                if (batch.done()) listing.close(); else result.addProperty("cursor", keep(operation, path, listing));
                return result;
            } catch (Exception failure) { closeQuietly(listing); throw failure; }
        }
        if (operation.equals("/_api/file")) {
            try {
                var file = ConfinedFiles.read(root, path);
                result.addProperty("path", file.info().relative()); result.addProperty("deleted", false);
                result.addProperty("size", file.info().size()); result.addProperty("tooLarge", file.bytes() == null);
                if (file.bytes() != null) {
                    result.addProperty("base64", Base64.getEncoder().encodeToString(file.bytes()));
                    var image = FileImages.inspect(path, file.bytes());
                    if (image != null) result.add("image", JSON.toJsonTree(image));
                }
            } catch (NoSuchFileException absent) { result.addProperty("path", path); result.addProperty("deleted", true); }
            return result;
        }
        if (repository == null) throw new IOException("Git is unavailable for this folder.");
        String base = params.getOrDefault("base", "branch");
        if (operation.equals("/_api/changes")) return JSON.toJsonTree(repository.snapshot(base)).getAsJsonObject();
        if (operation.equals("/_api/refs")) return JSON.toJsonTree(repository.refs()).getAsJsonObject();
        if (operation.equals("/_api/diff")) {
            result.add("old", content(repository.baseContent(base, path)));
            try { result.add("new", content(repository.workingContent(path))); }
            catch (NoSuchFileException absent) { result.add("new", JsonNull.INSTANCE); }
            return result;
        }
        throw new IOException("Unknown files request.");
    }
    private static JsonElement content(FileRepository.Content value) {
        if ("Deleted".equals(value.notice())) return JsonNull.INSTANCE;
        var result = new JsonObject(); result.addProperty("size", value.size()); result.addProperty("notice", value.notice());
        result.addProperty("tooLarge", value.size() > ConfinedFiles.MAX_BYTES);
        if (value.bytes() != null) result.addProperty("base64", Base64.getEncoder().encodeToString(value.bytes()));
        return result;
    }
    private AutoCloseable take(Map<String,String> params, String operation, String argument) throws IOException {
        String token = params.get("cursor"); if (token == null || token.isEmpty()) return null;
        var cursor = cursors.remove(token);
        if (cursor == null) throw new IOException("This scan expired. Refresh to continue.");
        if (!cursor.operation().equals(operation) || !cursor.argument().equals(argument)) {
            closeQuietly(cursor.value()); throw new IOException("This cursor belongs to another request.");
        }
        return cursor.value();
    }
    private String keep(String operation, String argument, AutoCloseable value) {
        while (cursors.size() >= 4) { var first = cursors.keySet().iterator().next(); closeQuietly(cursors.remove(first).value()); }
        String token = UUID.randomUUID().toString(); cursors.put(token, new Cursor(operation, argument, value, System.nanoTime())); return token;
    }
    synchronized void expire() {
        long now = System.nanoTime();
        cursors.values().removeIf(cursor -> { if (now - cursor.touched() < 60_000_000_000L) return false; closeQuietly(cursor.value()); return true; });
    }
    @Override public synchronized void close() { closed = true; cursors.values().forEach(cursor -> closeQuietly(cursor.value())); cursors.clear(); }
    private static void closeQuietly(AutoCloseable value) { try { value.close(); } catch (Exception ignored) {} }
}
