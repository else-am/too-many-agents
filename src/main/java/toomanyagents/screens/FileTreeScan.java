package toomanyagents.screens;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;

/** A page retains one checked directory handle; queued folders contain no open resources. */
final class FileTreeScan implements AutoCloseable {
    record Page(List<String> paths, List<String> directories, List<String> ignored, boolean partial, List<String> notices, boolean done) {}
    private final ConfinedFiles.Root root;
    private final FileRepository.TreeRules rules;
    private final String query;
    private final ArrayDeque<String> folders = new ArrayDeque<>();
    private final Set<String> visited = new HashSet<>();
    private final Set<String> notices = new LinkedHashSet<>();
    private final Iterator<String> tracked;
    private final Map<String, Boolean> trackedDirectories = new HashMap<>();
    private ConfinedFiles.Directory directory;
    private String folder;
    private boolean firstBatch;
    private boolean closed, partial, resultLimit;
    private int emitted;
    private static final int MAX_RESULTS = 100_000;

    FileTreeScan(ConfinedFiles.Root root, FileRepository repository, String query) throws IOException {
        this.root = root;
        this.query = query.toLowerCase(Locale.ROOT);
        FileRepository.TreeRules prepared = null;
        try { prepared = repository == null ? null : repository.treeRules(); }
        catch (IOException unavailable) { incomplete("Git tree rules unavailable - showing all accessible files. " + unavailable.getMessage()); }
        rules = prepared;
        tracked = rules == null ? Collections.emptyIterator() : rules.trackedPaths().iterator();
        folders.add("");
    }

    synchronized Page next() throws IOException {
        if (closed) throw new IOException("This tree scan has expired. Refresh the tree.");
        if (rules != null) rules.beginPage();
        var files = new LinkedHashSet<String>();
        var directories = new LinkedHashSet<String>();
        var ignored = new LinkedHashSet<String>();
        int work = 0;
        long until = System.nanoTime() + 150_000_000;
        while (!resultLimit && work < 1000 && System.nanoTime() < until) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Tree scan cancelled");
            if (tracked.hasNext()) {
                String path = tracked.next(); work++;
                boolean isDirectory = false;
                try { isDirectory = ConfinedFiles.info(root, path).kind() == ConfinedFiles.Kind.DIRECTORY; }
                catch (InterruptedIOException cancelled) { throw cancelled; }
                catch (java.nio.file.NoSuchFileException missing) { /* Keep missing tracked files visible. */ }
                catch (IOException unavailable) { incomplete("Some tracked paths are unavailable or outside the granted folder."); }
                trackedDirectories.put(path, isDirectory);
                if (matches(path) && include(path, isDirectory, files, directories)) {
                    for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1))
                        trackedDirectories.put(path.substring(0, slash), true);
                }
                continue;
            }
            if (directory == null) {
                if (folders.isEmpty()) break;
                folder = folders.removeFirst();
                try {
                    directory = ConfinedFiles.directory(root, folder);
                    firstBatch = true;
                } catch (IOException unavailable) { incomplete("Some folders are unavailable or outside the granted folder."); continue; }
            }
            var batch = directory.next(Math.min(128, 1000 - work));
            if (firstBatch) {
                firstBatch = false;
                if (!visited.add(batch.info().path())) { directory.close(); directory = null; continue; }
                if (visited.size() > 50_000) { incomplete("Tree folder limit reached. Open folders individually to continue."); directory.close(); directory = null; folders.clear(); break; }
            }
            for (var entry : batch.entries()) {
                work++;
                String path = folder.isEmpty() ? entry.name() : folder + "/" + entry.name();
                var kind = entry.kind();
                if (kind == ConfinedFiles.Kind.LINK) {
                    try { kind = ConfinedFiles.info(root, path).kind(); }
                    catch (IOException unavailable) { incomplete("Some links are unavailable or outside the granted folder."); }
                }
                boolean isDirectory = kind == ConfinedFiles.Kind.DIRECTORY;
                Boolean trackedKind = trackedDirectories.get(path);
                if (trackedKind != null && trackedKind != isDirectory) {
                    incomplete("Some paths have conflicting file and folder types. Open their parent folder to inspect.");
                    continue;
                }
                boolean excluded = entry.name().equals(".git");
                try { excluded |= rules != null && rules.ignored(path, isDirectory); }
                catch (IOException unavailable) { incomplete("Some ignore rules could not be read."); }
                if (matches(path)) {
                    if (!include(path, isDirectory, files, directories)) break;
                    if (excluded) ignored.add(path);
                }
                if (isDirectory && !excluded) {
                    if (folders.size() >= 10_000 || path.split("/").length >= 128)
                        incomplete("Tree traversal limit reached. Open folders individually to continue.");
                    else folders.addLast(path);
                }
            }
            if (batch.done()) { directory.close(); directory = null; }
        }
        emitted += files.size() + directories.size();
        boolean done = resultLimit || (!tracked.hasNext() && directory == null && folders.isEmpty());
        if (rules != null) { notices.addAll(rules.notices()); partial |= rules.partial(); }
        return new Page(List.copyOf(files), List.copyOf(directories), List.copyOf(ignored), partial || !done, List.copyOf(notices), done);
    }

    private boolean matches(String path) { return path.toLowerCase(Locale.ROOT).contains(query); }
    private void incomplete(String notice) { partial = true; notices.add(notice); }
    private boolean include(String path, boolean directory, Set<String> files, Set<String> directories) {
        int additions = (directory ? directories : files).contains(path) ? 0 : 1;
        for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1))
            if (!directories.contains(path.substring(0, slash))) additions++;
        if ((long) emitted + files.size() + directories.size() + additions > MAX_RESULTS) {
            resultLimit = true;
            incomplete("Tree result limit reached. Narrow the search or open folders individually to continue.");
            return false;
        }
        (directory ? directories : files).add(path);
        for (int slash = path.lastIndexOf('/'); slash > 0; slash = path.lastIndexOf('/', slash - 1)) directories.add(path.substring(0, slash));
        return true;
    }
    @Override public synchronized void close() throws IOException {
        closed = true; folders.clear(); visited.clear(); trackedDirectories.clear();
        if (directory != null) { directory.close(); directory = null; }
    }
}
