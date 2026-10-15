package toomanyagents.screens;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import org.eclipse.jgit.attributes.Attribute;
import org.eclipse.jgit.attributes.AttributesNode;
import org.eclipse.jgit.attributes.AttributesRule;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.io.AutoLFInputStream;
import toomanyagents.screens.ConfinedGitObjects.Budget;
import toomanyagents.screens.ConfinedGitObjects.Limit;

/** Synchronous, read-only Git data for one granted viewer root. Call on the viewer's background executor. */
public final class FileRepository {
    public record Change(String path, String kind, String notice) {}
    public record Snapshot(String base, List<Change> changes, List<String> trackedPaths,
                           boolean partial, List<String> notices) {}
    /** Null bytes mean a deleted, binary, large or unsupported file. No object ID is accepted by this API. */
    public record Content(String path, byte[] bytes, long size, String notice) {}
    public record Refs(List<String> names, boolean partial, List<String> notices) {}
    private record TreeEntry(ObjectId id, int mode) {}
    private record Base(ObjectId commit, String label, String notice) {}

    private final ConfinedFiles.Root scope;
    private final ConfinedFiles.Root git;
    private final ConfinedFiles.Root common;
    private final String repositoryRoot;
    private final String prefix;
    private final java.util.function.Consumer<FileWatchInput> watches;

    private FileRepository(ConfinedFiles.Root scope, ConfinedFiles.Root git, ConfinedFiles.Root common, Path root, java.util.function.Consumer<FileWatchInput> watches) {
        this.watches = watches;
        this.scope = scope;
        this.git = git;
        this.common = common;
        this.repositoryRoot = root.toString();
        String relative = root.relativize(Path.of(scope.path())).toString();
        if (com.sun.jna.Platform.isWindows()) relative = relative.replace('\\', '/');
        this.prefix = relative.isEmpty() ? "" : relative + "/";
    }

    public String repositoryRoot() { return repositoryRoot; }
    public String scopePrefix() { return prefix; }
    public List<String> metadataRoots() { return git.path().equals(common.path()) ? List.of(git.path()) : List.of(git.path(), common.path()); }

    /** Finds a containing repository. A returned repository never changes the supplied content grant. */
    public static FileRepository discover(ConfinedFiles.Root scope) throws IOException {
        return discover(scope, ignored -> {});
    }

    static FileRepository discover(ConfinedFiles.Root scope, java.util.function.Consumer<FileWatchInput> watches) throws IOException {
        var budget = new Budget();
        for (Path ancestor = Path.of(scope.path()); ancestor != null; ancestor = ancestor.getParent()) {
            budget.step();
            var parent = ConfinedFiles.grantExact(ancestor);
            ConfinedFiles.Info marker;
            try { marker = ConfinedFiles.info(parent, ".git"); }
            catch (NoSuchFileException missing) {
                watches.accept(new FileWatchInput(parent, ".git", false, FileWatchInput.Kind.GIT));
                continue;
            }
            watches.accept(new FileWatchInput(parent, ".git", marker.kind() == ConfinedFiles.Kind.DIRECTORY, FileWatchInput.Kind.GIT));
            var expected = ancestor.resolve(".git");
            if (!marker.path().equals(expected.toString())) throw new IOException("Git metadata redirects through a symbolic link");
            ConfinedFiles.Root git, common;
            if (marker.kind() == ConfinedFiles.Kind.DIRECTORY) {
                git = ConfinedFiles.grantExact(expected);
                common = git;
                if (optional(git, "commondir", 4096, budget) != null)
                    throw new IOException("Common Git directory redirects require a linked worktree");
            } else if (marker.kind() == ConfinedFiles.Kind.FILE) {
                String link = text(ConfinedGitObjects.read(parent, ".git", 4096, budget)).trim();
                if (!link.startsWith("gitdir: ")) throw new IOException("Invalid worktree Git directory link");
                Path directory = ancestor.resolve(link.substring(8).trim()).normalize();
                git = ConfinedFiles.grantExact(directory);
                String back = text(ConfinedGitObjects.read(git, "gitdir", 4096, budget)).trim();
                if (!directory.resolve(back).normalize().equals(expected))
                    throw new IOException("Worktree Git directory has no matching backpointer");
                String commonLink = text(ConfinedGitObjects.read(git, "commondir", 4096, budget)).trim();
                Path commonPath = directory.resolve(commonLink).normalize();
                if (directory.getParent() == null || !directory.getParent().equals(commonPath.resolve("worktrees")))
                    throw new IOException("Worktree metadata is outside the common worktrees directory");
                common = ConfinedFiles.grantExact(commonPath);
                // Re-read reciprocal links through their grants after both directories have been pinned.
                if (!text(ConfinedGitObjects.read(parent, ".git", 4096, budget)).trim().equals(link)
                    || !text(ConfinedGitObjects.read(git, "gitdir", 4096, budget)).trim().equals(back)
                    || !text(ConfinedGitObjects.read(git, "commondir", 4096, budget)).trim().equals(commonLink))
                    throw new IOException("Worktree metadata changed during discovery");
            } else throw new IOException("Unsupported Git metadata entry");
            var found = new FileRepository(scope, git, common, ancestor, watches);
            for (String name : List.of("HEAD", "index", "config.worktree", "commondir", "gitdir"))
                watches.accept(new FileWatchInput(git, name, false, FileWatchInput.Kind.GIT));
            for (String name : List.of("config", "packed-refs", "objects/info/alternates", "objects/info/http-alternates"))
                watches.accept(new FileWatchInput(common, name, false, FileWatchInput.Kind.GIT));
            for (String name : List.of("refs", "objects/pack"))
                watches.accept(new FileWatchInput(common, name, true, FileWatchInput.Kind.GIT));
            for (String name : List.of("info/exclude", "info/attributes"))
                watches.accept(new FileWatchInput(common, name, false, FileWatchInput.Kind.RULES));
            found.config(budget);
            return found;
        }
        return null;
    }

    private Config config(Budget budget) throws IOException {
        var user = userConfig(ConfinedFiles.grant(Path.of(System.getProperty("user.home"))), budget, new LinkedHashSet<>(), watches);
        var defaults = new Config();
        // Only these data-only settings can affect comparisons. User helpers/includes never reach JGit.
        for (String key : List.of("autocrlf", "eol", "filemode")) {
            String value = user.getString("core", null, key);
            if (value != null) defaults.setString("core", null, key, value);
        }
        var config = new Config(defaults);
        try {
            byte[] bytes = optional(common, "config", ConfinedFiles.MAX_BYTES, budget);
            if (bytes != null) config.fromText(text(bytes)); // Includes are data; Config never follows them.
            if (config.getBoolean("extensions", null, "worktreeConfig", false)) {
                var worktree = new Config(config);
                byte[] local = optional(git, "config.worktree", ConfinedFiles.MAX_BYTES, budget);
                if (local != null) worktree.fromText(text(local));
                config = worktree;
            }
        } catch (org.eclipse.jgit.errors.ConfigInvalidException | IllegalArgumentException invalid) {
            throw new IOException("Invalid Git configuration", invalid);
        }
        String format = config.getString("extensions", null, "objectFormat");
        if (format != null && !format.equals("sha1")) throw new IOException("SHA-256 Git repositories are not supported");
        String refs = config.getString("extensions", null, "refStorage");
        if (refs != null && !refs.equals("files")) throw new IOException("Git reftable repositories are not supported");
        if (optional(common, "objects/info/alternates", 4096, budget) != null
            || optional(common, "objects/info/http-alternates", 4096, budget) != null)
            throw new IOException("Git alternate object directories are not supported");
        return config;
    }

    public Snapshot snapshot(String requestedBase) throws IOException {
        var budget = new Budget();
        Config config = config(budget);
        var changes = new ArrayList<Change>();
        var tracked = new TreeSet<String>();
        var notices = new LinkedHashSet<String>();
        boolean partial = false;
        String label = requestedBase == null || requestedBase.isBlank() ? "branch" : requestedBase;
        try (var objects = new ConfinedGitObjects(common, budget); var reader = objects.reader()) {
            Base base = base(label, config, reader, budget);
            label = base.label();
            if (base.notice() != null) notices.add(base.notice());
            Map<String, TreeEntry> before = tree(reader, base.commit(), budget);
            tracked.addAll(before.keySet());
            ObjectId head = resolveRef("HEAD", budget);
            tracked.addAll(tree(reader, head, budget).keySet());
            IndexState index = readIndex(tracked, budget, notices);
            if (!index.complete()) partial = true;
            var policy = new Policy(config, budget, notices, reader, index.blobs());
            var indexCrlf = new HashMap<ObjectId, Boolean>();
            Set<String> current = new TreeSet<>();
            Set<String> links = new HashSet<>();
            try { scan("", current, links, tracked, policy, new HashSet<>(), budget); }
            catch (Limit limit) { partial = true; notices.add(limit.getMessage()); }
            // Tracked files must still be examined in ignored folders, and missing tracked paths are deletions.
            var paths = new TreeSet<>(current);
            paths.addAll(tracked);
            for (String path : paths) {
                budget.step();
                TreeEntry old = before.get(path);
                if (links.contains(path)) {
                    if (old == null) changes.add(new Change(path, "added", "Symbolic link"));
                    else try {
                        byte[] target = ConfinedFiles.readLink(scope, path);
                        budget.bytes(target.length);
                        ObjectId actual;
                        try (var formatter = new ObjectInserter.Formatter()) { actual = formatter.idFor(Constants.OBJ_BLOB, target); }
                        if (old.mode() != FileMode.SYMLINK.getBits() || !actual.equals(old.id()))
                            changes.add(new Change(path, "modified", "Symbolic link"));
                    } catch (InterruptedIOException cancelled) { throw cancelled; }
                    catch (IOException unavailable) {
                        changes.add(new Change(path, "unknown", unavailable.getMessage())); partial = true;
                    }
                    continue;
                }
                try {
                    var info = ConfinedFiles.info(scope, path);
                    if (info.kind() != ConfinedFiles.Kind.FILE) {
                        if (old != null) { changes.add(new Change(path, "unknown", "Non-file Git entry is not compared")); partial = true; }
                        continue;
                    }
                    if (old != null && old.mode() == FileMode.GITLINK.getBits()) {
                        partial = true;
                        changes.add(new Change(path, "unknown", "Submodule changes are not compared"));
                        continue;
                    }
                    if (old == null) { changes.add(new Change(path, "added", null)); continue; }
                    var attributes = policy.attributes(path);
                    ObjectId indexed = index.blobs().get(prefix + path);
                    if (normalization(attributes, config) == 2 && indexed != null) {
                        Boolean crlf = indexCrlf.get(indexed);
                        if (crlf == null) { crlf = containsCrlf(reader, indexed, budget); indexCrlf.put(indexed, crlf); }
                        // Git preserves existing CRLF in the index for automatic text conversion.
                        if (crlf) attributes.put("text", new Attribute("text", Attribute.State.UNSET));
                    }
                    String filter = value(attributes.get("filter"));
                    String notice = filter == null ? null : "Unfiltered content - executable Git filters are disabled";
                    var actual = workingHash(path, attributes, config, budget, "lfs".equals(filter));
                    boolean modeChanged = old.mode() == FileMode.SYMLINK.getBits()
                        || (config.getBoolean("core", null, "filemode", !com.sun.jna.Platform.isWindows())
                            && (old.mode() == FileMode.EXECUTABLE_FILE.getBits()) != actual.executable());
                    if ("lfs".equals(filter)) {
                        byte[] pointer = objectBytes(reader, old.id(), 4096);
                        if (pointer != null && lfsMatches(pointer, actual.sha256(), actual.size()) && !modeChanged) continue;
                        notice = "LFS content is local only - no download or filter is run";
                    }
                    if (modeChanged) {
                        String modeNotice = old.mode() == FileMode.SYMLINK.getBits() ? "File type changed" : "Executable bit changed";
                        notice = notice == null ? modeNotice : notice + " - " + modeNotice;
                    }
                    if (modeChanged || !actual.id().equals(old.id())) changes.add(new Change(path, "modified", notice));
                } catch (NoSuchFileException missing) {
                    if (old != null) changes.add(new Change(path, "deleted", null));
                } catch (Limit limit) {
                    partial = true;
                    changes.add(new Change(path, "unknown", limit.getMessage()));
                    notices.add(limit.getMessage());
                } catch (ConfinedFiles.OutsideRoot denied) {
                    partial = true;
                    changes.add(new Change(path, "unknown", "Link points outside the viewed folder"));
                }
            }
        } catch (Limit limit) { partial = true; notices.add(limit.getMessage()); }
        return new Snapshot(label, List.copyOf(changes), List.copyOf(tracked), partial, List.copyOf(notices));
    }

    /** Reads current content with the same display cap as the ordinary viewer. */
    public Content workingContent(String path) throws IOException {
        path = checkedPath(path);
        try {
            byte[] target = ConfinedFiles.readLink(scope, path);
            return new Content(path, target, target.length, "Symbolic link");
        } catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException notLink) { /* Ordinary files use the checked content handle below. */ }
        var content = ConfinedFiles.read(scope, path);
        var budget = new Budget();
        Config config = config(budget);
        var notices = new LinkedHashSet<String>();
        try (var objects = new ConfinedGitObjects(common, budget); var reader = objects.reader()) {
            IndexState index = readIndex(new HashSet<>(), budget, notices);
            var policy = new Policy(config, budget, notices, reader, index.blobs());
            String filter = value(policy.attributes(path).get("filter"));
            String notice = filter == null ? null : "Unfiltered content - executable Git filters are disabled";
            if (content.bytes() == null) return new Content(path, null, content.info().size(), "File exceeds 2 MB");
            return display(path, content.bytes(), content.info().size(), notice);
        }
    }

    /** Resolves the requested base and scoped path itself; callers cannot request arbitrary object IDs. */
    public Content baseContent(String requestedBase, String path) throws IOException {
        path = checkedPath(path);
        var budget = new Budget();
        var config = config(budget);
        try (var objects = new ConfinedGitObjects(common, budget); var reader = objects.reader()) {
            Base base = base(requestedBase, config, reader, budget);
            if (base.commit() == null) return new Content(path, null, 0, "Deleted");
            try (var walk = new RevWalk(reader)) {
                var commit = walk.parseCommit(base.commit());
                try (var tree = TreeWalk.forPath(reader, prefix + path, commit.getTree())) {
                    if (tree == null) return new Content(path, null, 0, "Deleted");
                    if (tree.getFileMode(0).getObjectType() != Constants.OBJ_BLOB)
                        return new Content(path, null, 0, "Not a regular Git file");
                    var loader = reader.open(tree.getObjectId(0), Constants.OBJ_BLOB);
                    if (loader.getSize() > ConfinedFiles.MAX_BYTES)
                        return new Content(path, null, loader.getSize(), "File exceeds 2 MB");
                    byte[] bytes = loader.getCachedBytes(ConfinedFiles.MAX_BYTES);
                    String lfs = lfsOid(bytes);
                    if (lfs != null) {
                        Content local = localLfsContent(path, lfs, bytes, budget);
                        if (local != null) return local;
                    }
                    return display(path, bytes, loader.getSize(), lfs == null ? null : "LFS pointer - content is not downloaded");
                }
            }
        } catch (ConfinedGitObjects.ObjectLimit large) {
            return new Content(path, null, large.size, "File exceeds 2 MB");
        }
    }

    public Refs refs() throws IOException {
        var budget = new Budget();
        config(budget);
        var names = new TreeSet<String>();
        var notices = new ArrayList<String>();
        boolean partial = false;
        try {
            collectRefs("refs", names, budget);
            for (String name : packedRefs(budget).keySet()) names.add(name);
        } catch (Limit limit) { partial = true; notices.add(limit.getMessage()); }
        return new Refs(List.copyOf(names), partial, List.copyOf(notices));
    }

    public boolean ignored(String path, boolean directory) throws IOException {
        path = checkedPath(path);
        var budget = new Budget();
        return new Policy(config(budget), budget, new LinkedHashSet<>()).ignored(path, directory);
    }

    /** One tree scan reuses its parsed ignore rules instead of reopening Git metadata for every entry. */
    public TreeRules treeRules() throws IOException {
        var budget = new Budget();
        var notices = new LinkedHashSet<String>();
        var tracked = new TreeSet<String>();
        var index = readIndex(tracked, budget, notices);
        return new TreeRules(List.copyOf(tracked), new Policy(config(budget), budget, notices), !index.complete(), notices);
    }

    public final class TreeRules {
        private final List<String> tracked;
        private final Policy policy;
        private final boolean partial;
        private final Set<String> notices;
        private TreeRules(List<String> tracked, Policy policy, boolean partial, Set<String> notices) {
            this.tracked = tracked; this.policy = policy; this.partial = partial; this.notices = notices;
        }
        public List<String> trackedPaths() { return tracked; }
        public boolean partial() { return partial; }
        public List<String> notices() { return List.copyOf(notices); }
        public void beginPage() { policy.budget = new Budget(); }
        public boolean ignored(String path, boolean directory) throws IOException { return policy.ignored(checkedPath(path), directory); }
    }

    private void collectRefs(String folder, Set<String> names, Budget budget) throws IOException {
        budget.step();
        if (folder.split("/").length > 128) throw new Limit("Ref directory depth limit reached");
        ConfinedFiles.Listing listing;
        try { listing = ConfinedFiles.list(common, folder, ConfinedFiles.MAX_ENTRIES); }
        catch (NoSuchFileException missing) { return; }
        watches.accept(new FileWatchInput(common, folder, true, FileWatchInput.Kind.GIT));
        if (listing.truncated()) throw new Limit("Ref list is incomplete");
        for (var entry : listing.entries()) {
            budget.step();
            String path = folder + "/" + entry.name();
            if (entry.kind() == ConfinedFiles.Kind.DIRECTORY) collectRefs(path, names, budget);
            else if (entry.kind() == ConfinedFiles.Kind.FILE && Repository.isValidRefName(path)) {
                if (resolveRef(path, budget) != null) names.add(path);
            }
        }
    }

    private Base base(String requested, Config config, ObjectReader reader, Budget budget) throws IOException {
        String name = requested == null || requested.isBlank() ? "branch" : requested;
        ObjectId head = resolveRef("HEAD", budget);
        if (name.equalsIgnoreCase("uncommitted")) return new Base(head, "uncommitted", null);
        if (!name.equalsIgnoreCase("branch")) return new Base(explicitCommit(name, reader, budget), name, null);
        if (head == null) return new Base(null, "uncommitted", "Unborn branch - comparing with an empty tree");
        String headText = text(ConfinedGitObjects.read(git, "HEAD", 4096, budget)).trim();
        String branch = headText.startsWith("ref: refs/heads/") ? headText.substring(16) : null;
        String remote = branch == null ? null : config.getString("branch", branch, "remote");
        ObjectId upstream = null;
        if (remote != null && !remote.equals(".") && validRemote(remote)) upstream = resolveRef("refs/remotes/" + remote + "/HEAD", budget);
        if (upstream == null) upstream = resolveRef("refs/remotes/origin/HEAD", budget);
        if (upstream == null) return new Base(head, "uncommitted", "No local default branch - showing Uncommitted");
        try (var walk = new RevWalk(reader)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(head));
            walk.markStart(walk.parseCommit(upstream));
            budget.step();
            var merge = walk.next();
            budget.step();
            if (merge != null) return new Base(merge.copy(), "branch", null);
        } catch (org.eclipse.jgit.errors.MissingObjectException missing) {
            return new Base(head, "uncommitted", "Default branch history is unavailable - showing Uncommitted");
        }
        return new Base(head, "uncommitted", "No merge base - showing Uncommitted");
    }

    private ObjectId explicitCommit(String expression, ObjectReader reader, Budget budget) throws IOException {
        if (expression.startsWith("-") || expression.length() > 512 || expression.indexOf('\0') >= 0)
            throw new IOException("Invalid explicit Git ref");
        int suffix = expression.length();
        for (int i = 0; i < expression.length(); i++) if (expression.charAt(i) == '~' || expression.charAt(i) == '^') { suffix = i; break; }
        String name = expression.substring(0, suffix);
        ObjectId id = null;
        if (ObjectId.isId(name)) id = ObjectId.fromString(name);
        else if (name.equals("HEAD") || Repository.isValidRefName(name)) id = resolveRef(name, budget);
        if (id == null) {
            for (String candidate : List.of("refs/heads/" + name, "refs/tags/" + name, "refs/remotes/" + name)) {
                if (Repository.isValidRefName(candidate)) { id = resolveRef(candidate, budget); if (id != null) break; }
            }
        }
        if (id == null) throw new IOException("Unknown explicit Git ref: " + expression);
        try (var walk = new RevWalk(reader)) {
            RevObject object = walk.peel(walk.parseAny(id));
            if (!(object instanceof RevCommit commit)) throw new IOException("Explicit ref does not name a commit");
            int cursor = suffix;
            while (cursor < expression.length()) {
                char operator = expression.charAt(cursor++);
                int start = cursor;
                while (cursor < expression.length() && Character.isDigit(expression.charAt(cursor))) cursor++;
                int count;
                try { count = start == cursor ? 1 : Integer.parseInt(expression.substring(start, cursor)); }
                catch (NumberFormatException invalid) { throw new IOException("Invalid explicit Git ref"); }
                if (count > 100 || (operator != '~' && operator != '^')) throw new IOException("Unsupported explicit Git ref expression");
                if (operator == '^') {
                    if (count > 0) {
                        if (count > commit.getParentCount()) throw new IOException("Explicit ref has no requested parent");
                        commit = walk.parseCommit(commit.getParent(count - 1));
                    }
                } else for (int i = 0; i < count; i++) {
                    budget.step();
                    if (commit.getParentCount() == 0) throw new IOException("Explicit ref has no requested ancestor");
                    commit = walk.parseCommit(commit.getParent(0));
                }
            }
            return commit.copy();
        }
    }

    private ObjectId resolveRef(String name, Budget budget) throws IOException {
        var seen = new HashSet<String>();
        for (int depth = 0; depth < 16; depth++) {
            budget.step();
            if (!name.equals("HEAD") && !Repository.isValidRefName(name)) throw new IOException("Invalid Git ref");
            if (!seen.add(name)) throw new IOException("Cyclic symbolic Git ref");
            watches.accept(new FileWatchInput(name.equals("HEAD") ? git : common, name, false, FileWatchInput.Kind.GIT));
            byte[] bytes = optional(name.equals("HEAD") ? git : common, name, 4096, budget);
            if (bytes == null) return packedRefs(budget).get(name);
            String value = text(bytes).trim();
            if (value.startsWith("ref: ")) { name = value.substring(5).trim(); continue; }
            if (!ObjectId.isId(value)) throw new IOException("Invalid Git ref value");
            return ObjectId.fromString(value);
        }
        throw new IOException("Too many symbolic Git refs");
    }

    private Map<String, ObjectId> packedRefs(Budget budget) throws IOException {
        byte[] bytes = optional(common, "packed-refs", ConfinedFiles.MAX_BYTES, budget);
        if (bytes == null) return Map.of();
        var result = new HashMap<String, ObjectId>();
        for (String line : text(bytes).split("\n")) {
            budget.step();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("^")) continue;
            if (line.length() < 42 || line.charAt(40) != ' ' || !ObjectId.isId(line.substring(0, 40))
                || !Repository.isValidRefName(line.substring(41))) throw new IOException("Invalid packed Git ref");
            result.put(line.substring(41), ObjectId.fromString(line.substring(0, 40)));
        }
        return result;
    }

    private Map<String, TreeEntry> tree(ObjectReader reader, ObjectId id, Budget budget) throws IOException {
        var entries = new TreeMap<String, TreeEntry>();
        if (id == null) return entries;
        try (var revisions = new RevWalk(reader)) {
            ObjectId tree = revisions.parseCommit(id).getTree();
            if (!prefix.isEmpty()) {
                try (var selected = TreeWalk.forPath(reader, prefix.substring(0, prefix.length() - 1), tree)) {
                    if (selected == null || selected.getFileMode(0) != FileMode.TREE) return entries;
                    tree = selected.getObjectId(0);
                }
            }
            try (var walk = new TreeWalk(reader)) {
                walk.addTree(tree);
                walk.setRecursive(true);
                while (walk.next()) {
                    budget.step();
                    String path = checkedPath(walk.getPathString());
                    entries.put(path, new TreeEntry(walk.getObjectId(0), walk.getRawMode(0)));
                }
            }
        }
        return entries;
    }

    private record IndexState(Map<String, ObjectId> blobs, boolean complete) {}

    private IndexState readIndex(Set<String> tracked, Budget budget, Set<String> notices) throws IOException {
        byte[] bytes = optional(git, "index", ConfinedGitObjects.MAX_OBJECT_BYTES, budget);
        if (bytes == null) return new IndexState(Map.of(), true);
        var blobs = new HashMap<String, ObjectId>();
        // JGit only sees an application-owned bounded copy, never a repository pathname or a shared index link.
        Path folder = Files.createTempDirectory("tma-view-index-");
        Path copy = folder.resolve("index");
        try {
            Files.write(copy, indexEntries(bytes));
            DirCache index = DirCache.read(copy.toFile(), FS.DETECTED);
            for (int i = 0; i < index.getEntryCount(); i++) {
                budget.step();
                var entry = index.getEntry(i);
                if (entry.getFileMode() == FileMode.TREE) throw new IOException("Sparse index");
                String path = checkedPath(entry.getPathString());
                if (entry.getStage() == 0 && (entry.getFileMode() == FileMode.REGULAR_FILE || entry.getFileMode() == FileMode.EXECUTABLE_FILE))
                    blobs.put(path, entry.getObjectId());
                if (path.startsWith(prefix)) tracked.add(checkedPath(path.substring(prefix.length())));
            }
            return new IndexState(Map.copyOf(blobs), true);
        } catch (InterruptedIOException | Limit cancelled) { throw cancelled; }
        catch (IOException | RuntimeException unsupported) {
            notices.add("Index unavailable - comparing confined working files; ignored staged additions may be missing");
            return new IndexState(Map.of(), false);
        } finally { Files.deleteIfExists(copy); Files.deleteIfExists(folder); }
    }

    /** Strip optional index extensions so untrusted cache-tree lengths never drive JGit allocations. */
    private static byte[] indexEntries(byte[] bytes) throws IOException {
        if (bytes.length < 32) throw new IOException("Truncated index");
        var header = ByteBuffer.wrap(bytes);
        int magic = header.getInt(), version = header.getInt(), count = header.getInt();
        if (magic != 0x44495243 || version < 2 || version > 3 || count < 0 || count > 50_000)
            throw new IOException("Unsupported index header");
        var digest = Constants.newMessageDigest();
        digest.update(bytes, 0, bytes.length - 20);
        if (!Arrays.equals(digest.digest(), Arrays.copyOfRange(bytes, bytes.length - 20, bytes.length)))
            throw new IOException("Index checksum mismatch");
        int end = 12;
        for (int i = 0; i < count; i++) {
            int start = end;
            if (start + 62 > bytes.length - 20) throw new IOException("Truncated index entry");
            int flags = header.getShort(start + 60) & 0xffff;
            end = start + 62 + ((flags & 0x4000) != 0 ? 2 : 0);
            int nameStart = end;
            while (end < bytes.length - 20 && bytes[end] != 0 && end - nameStart <= 4096) end++;
            if (end == bytes.length - 20 || end - nameStart > 4096) throw new IOException("Invalid index path");
            end = start + ((end + 1 - start + 7) / 8) * 8;
            if (end > bytes.length - 20) throw new IOException("Truncated index entry");
        }
        for (int cursor = end; cursor < bytes.length - 20;) {
            if (cursor + 8 > bytes.length - 20) throw new IOException("Truncated index extension");
            long size = Integer.toUnsignedLong(header.getInt(cursor + 4));
            if (bytes[cursor] < 'A' || bytes[cursor] > 'Z' || size > bytes.length - 20L - cursor - 8)
                throw new IOException("Unsupported index extension");
            cursor += 8 + (int) size;
        }
        byte[] copy = Arrays.copyOf(bytes, end + 20);
        digest.reset(); digest.update(copy, 0, end);
        System.arraycopy(digest.digest(), 0, copy, end, 20);
        return copy;
    }

    private void scan(String folder, Set<String> paths, Set<String> links, Set<String> tracked, Policy policy, Set<String> visited, Budget budget) throws IOException {
        budget.step();
        if (folder.split("/").length > 128) throw new Limit("Indexing incomplete - directory depth limit reached");
        var listing = ConfinedFiles.list(scope, folder.isEmpty() ? "." : folder, ConfinedFiles.MAX_ENTRIES);
        if (!visited.add(listing.info().path())) return;
        watches.accept(new FileWatchInput(scope, listing.info().relative(), true, FileWatchInput.Kind.STRUCTURE));
        if (listing.truncated()) throw new Limit("Indexing incomplete - folder entry limit reached");
        for (var entry : listing.entries()) {
            budget.step();
            if (entry.name().equals(".git")) continue;
            String path = folder.isEmpty() ? entry.name() : folder + "/" + entry.name();
            boolean directory = entry.kind() == ConfinedFiles.Kind.DIRECTORY;
            if (entry.kind() == ConfinedFiles.Kind.LINK) links.add(path);
            if (!tracked.contains(path) && policy.ignored(path, directory)) continue;
            if (directory) scan(path, paths, links, tracked, policy, visited, budget);
            else if (entry.kind() == ConfinedFiles.Kind.FILE || entry.kind() == ConfinedFiles.Kind.LINK) paths.add(path);
        }
    }

    private final class Policy {
        private final ObjectReader reader;
        private final Map<String, ObjectId> indexed;
        private Budget budget;
        private int ignoreBytes;
        private final Set<String> notices;
        private final Map<String, IgnoreNode> ignores = new HashMap<>();
        private final Map<String, AttributesNode> attributes = new HashMap<>();
        private final Map<String, List<Attribute>> macros = new HashMap<>();
        private final IgnoreNode exclude;
        private final IgnoreNode globalIgnore = new IgnoreNode();
        private final AttributesNode globalAttributes = new AttributesNode();
        private record AncestorRules(String prefix, IgnoreNode ignore, AttributesNode attributes) {}
        private final List<AncestorRules> ancestors = new ArrayList<>();
        private final AttributesNode infoAttributes;
        Policy(Config config, Budget budget, Set<String> notices) throws IOException {
            this(config, budget, notices, null, Map.of());
        }
        Policy(Config config, Budget budget, Set<String> notices, ObjectReader reader, Map<String, ObjectId> indexed) throws IOException {
            this.reader = reader; this.indexed = indexed; this.budget = budget; this.notices = notices;
            exclude = new IgnoreNode();
            byte[] bytes = optional(common, "info/exclude", ConfinedFiles.MAX_BYTES, budget);
            if (bytes != null) exclude.parse(new ByteArrayInputStream(bytes));
            infoAttributes = new AttributesNode();
            bytes = optional(common, "info/attributes", ConfinedFiles.MAX_BYTES, budget);
            if (bytes != null) infoAttributes.parse(new ByteArrayInputStream(bytes));
            if (config.getString("core", null, "excludesFile") != null)
                notices.add("Repository-configured external ignore files are not loaded");
            if (config.getString("core", null, "attributesFile") != null)
                notices.add("External Git attribute files are not loaded");
            loadUserRules();
            if (!prefix.isEmpty()) {
                Path directory = Path.of(repositoryRoot);
                String relative = "";
                while (!directory.equals(Path.of(scope.path()))) {
                    var root = ConfinedFiles.grantExact(directory);
                    watches.accept(new FileWatchInput(root, ".gitignore", false, FileWatchInput.Kind.RULES));
                    watches.accept(new FileWatchInput(root, ".gitattributes", false, FileWatchInput.Kind.RULES));
                    var ignore = new IgnoreNode();
                    var attributes = new AttributesNode();
                    bytes = optional(root, ".gitignore", ConfinedFiles.MAX_BYTES, budget);
                    if (bytes != null) ignore.parse(new ByteArrayInputStream(bytes));
                    bytes = optional(root, ".gitattributes", ConfinedFiles.MAX_BYTES, budget);
                    if (bytes == null) bytes = indexedAttributes(relative + ".gitattributes");
                    if (bytes != null) attributes.parse(new ByteArrayInputStream(bytes));
                    ancestors.add(new AncestorRules(relative, ignore, attributes));
                    String segment = prefix.substring(relative.length()).split("/", 2)[0];
                    directory = directory.resolve(segment);
                    relative += segment + "/";
                }
            }
            macros.put("binary", List.of(new Attribute("diff", Attribute.State.UNSET),
                new Attribute("merge", Attribute.State.UNSET), new Attribute("text", Attribute.State.UNSET)));
            defineMacros(globalAttributes);
            if (prefix.isEmpty()) {
                var rootAttributes = new AttributesNode();
                bytes = attributeBytes(".gitattributes");
                if (bytes != null) rootAttributes.parse(new ByteArrayInputStream(bytes));
                attributes.put("", rootAttributes);
                defineMacros(rootAttributes);
            } else defineMacros(ancestors.getFirst().attributes());
            defineMacros(infoAttributes);
        }
        /** Only user-owned config selects a global file. Repository config can never select a home-directory read. */
        private void loadUserRules() throws IOException {
            ConfinedFiles.Root home = ConfinedFiles.grant(Path.of(System.getProperty("user.home")));
            Config user = userConfig(home, budget, notices, watches);
            String directory = userGitDirectory(home, notices);
            String excludes = user.getString("core", null, "excludesFile");
            byte[] bytes = excludes != null ? userFile(home, excludes)
                : directory == null ? null : userFile(home, directory + "/ignore");
            if (bytes != null) globalIgnore.parse(new ByteArrayInputStream(bytes));
            String attributes = user.getString("core", null, "attributesFile");
            bytes = attributes != null ? userFile(home, attributes)
                : directory == null ? null : userFile(home, directory + "/attributes");
            if (bytes != null) globalAttributes.parse(new ByteArrayInputStream(bytes));
        }
        private byte[] userFile(ConfinedFiles.Root home, String name) throws IOException {
            String relative = name.startsWith("~/") ? name.substring(2) : name;
            Path target = homeTarget(home, relative);
            if (!target.startsWith(Path.of(home.path()))) {
                notices.add("User Git rule file outside the home directory is not loaded");
                return null;
            }
            try { return userRuleBytes(home, target.toString(), budget, watches); }
            catch (ConfinedFiles.OutsideRoot outside) {
                notices.add("User Git rule file outside the home directory is not loaded");
                return null;
            }
        }
        boolean ignored(String path, boolean directory) throws IOException {
            // A child cannot re-include itself if an ancestor directory is excluded.
            String full = prefix + path;
            int slash = full.indexOf('/');
            while (slash >= 0) {
                if (matchIgnore(full.substring(0, slash), true)) return true;
                slash = full.indexOf('/', slash + 1);
            }
            return matchIgnore(full, directory);
        }
        private boolean matchIgnore(String full, boolean directory) throws IOException {
            Boolean result = globalIgnore.checkIgnored(full, directory);
            Boolean next = exclude.checkIgnored(full, directory);
            if (next != null) result = next;
            for (var ancestor : ancestors) {
                if (!full.startsWith(ancestor.prefix()) || full.length() <= ancestor.prefix().length()) continue;
                next = ancestor.ignore().checkIgnored(full.substring(ancestor.prefix().length()), directory);
                if (next != null) result = next;
            }
            if (!full.startsWith(prefix) || full.length() <= prefix.length()) return Boolean.TRUE.equals(result);
            String path = full.substring(prefix.length());
            for (String folder : parents(path)) {
                IgnoreNode node = ignores.get(folder);
                if (node == null) {
                    node = new IgnoreNode();
                    byte[] bytes = workingOptional(folder + ".gitignore", budget);
                    if (ignores.size() >= 512 || ignoreBytes + (bytes == null ? 0 : bytes.length) > 8 * 1024 * 1024) {
                        ignores.clear(); ignoreBytes = 0;
                    }
                    if (bytes != null) { node.parse(new ByteArrayInputStream(bytes)); ignoreBytes += bytes.length; }
                    ignores.put(folder, node);
                }
                next = node.checkIgnored(path.substring(folder.length()), directory);
                if (next != null) result = next;
            }
            return Boolean.TRUE.equals(result);
        }
        Map<String, Attribute> attributes(String path) throws IOException {
            var result = new HashMap<String, Attribute>();
            apply(globalAttributes, prefix + path, result);
            for (var ancestor : ancestors) apply(ancestor.attributes(), (prefix + path).substring(ancestor.prefix().length()), result);
            for (String folder : parents(path)) {
                AttributesNode node = attributes.get(folder);
                if (node == null) {
                    node = new AttributesNode();
                    byte[] bytes = attributeBytes(folder + ".gitattributes");
                    if (bytes != null) node.parse(new ByteArrayInputStream(bytes));
                    attributes.put(folder, node);
                }
                apply(node, path.substring(folder.length()), result);
            }
            apply(infoAttributes, prefix + path, result);
            return result;
        }
        private byte[] attributeBytes(String path) throws IOException {
            byte[] bytes = workingOptional(path, budget);
            return bytes != null ? bytes : indexedAttributes(prefix + path);
        }
        private byte[] indexedAttributes(String path) throws IOException {
            ObjectId id = indexed.get(path);
            if (reader == null || id == null) return null;
            byte[] bytes = objectBytes(reader, id, ConfinedFiles.MAX_BYTES);
            if (bytes == null) throw new Limit("Indexed Git attributes exceed 2 MB");
            return bytes;
        }
        private void defineMacros(AttributesNode node) {
            for (AttributesRule rule : node.getRules()) if (rule.getPattern().startsWith("[attr]"))
                macros.put(rule.getPattern().substring(6), rule.getAttributes());
        }
        private void apply(AttributesNode node, String path, Map<String, Attribute> result) throws IOException {
            for (AttributesRule rule : node.getRules()) {
                budget.step();
                if (rule.getPattern().startsWith("[attr]")) continue;
                if (rule.isMatch(path, false)) for (Attribute attribute : rule.getAttributes()) {
                    var expanded = new HashMap<String, Attribute>();
                    expand(attribute, expanded, 0);
                    result.putAll(expanded);
                }
            }
        }
        private void expand(Attribute attribute, Map<String, Attribute> result, int depth) throws IOException {
            budget.step();
            if (result.putIfAbsent(attribute.getKey(), attribute) != null) return;
            if (depth > 128) throw new Limit("Git attribute macro depth limit reached");
            List<Attribute> definition = macros.get(attribute.getKey());
            if (definition == null) return;
            for (Attribute child : definition) {
                Attribute.State state = child.getState();
                Attribute expanded = child;
                switch (attribute.getState()) {
                    case UNSET -> {
                        if (state == Attribute.State.SET) expanded = new Attribute(child.getKey(), Attribute.State.UNSET);
                        else if (state == Attribute.State.UNSET) expanded = new Attribute(child.getKey(), Attribute.State.SET);
                    }
                    case UNSPECIFIED -> expanded = new Attribute(child.getKey(), Attribute.State.UNSPECIFIED);
                    case CUSTOM -> { if (state == Attribute.State.CUSTOM) expanded = new Attribute(child.getKey(), attribute.getValue()); }
                    default -> {}
                }
                expand(expanded, result, depth + 1);
            }
        }
    }

    private static Config userConfig(ConfinedFiles.Root home, Budget budget, Set<String> notices, java.util.function.Consumer<FileWatchInput> watches) throws IOException {
        Config user = new Config();
        String directory = userGitDirectory(home, notices);
        var names = new ArrayList<String>();
        if (directory != null) names.add(directory + "/config");
        names.add(".gitconfig");
        for (String name : names) {
            byte[] bytes;
            try { bytes = userRuleBytes(home, name, budget, watches); }
            catch (ConfinedFiles.OutsideRoot outside) {
                notices.add("User Git configuration outside the home directory is not loaded");
                continue;
            }
            if (bytes == null) continue;
            var next = new Config(user);
            try { next.fromText(text(bytes)); }
            catch (org.eclipse.jgit.errors.ConfigInvalidException invalid) { throw new IOException("Invalid user Git configuration", invalid); }
            user = next;
        }
        if (user.getSections().contains("include") || user.getSections().contains("includeIf"))
            notices.add("Settings from extra Git config files are not loaded - ignore rules and line-ending handling may differ from Git");
        return user;
    }

    private static String userGitDirectory(ConfinedFiles.Root home, Set<String> notices) {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg == null || xdg.isBlank()) return ".config/git";
        Path target = homeTarget(home, xdg);
        if (!target.startsWith(Path.of(home.path()))) {
            notices.add("XDG Git configuration outside the home directory is not loaded");
            return null;
        }
        return target.resolve("git").toString();
    }

    private static Path homeTarget(ConfinedFiles.Root home, String name) {
        Path target = Path.of(home.path()).resolve(name).normalize();
        Path suppliedHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (target.startsWith(suppliedHome)) target = Path.of(home.path()).resolve(suppliedHome.relativize(target));
        return target;
    }

    private static byte[] userRuleBytes(ConfinedFiles.Root home, String path, Budget budget, java.util.function.Consumer<FileWatchInput> watches) throws IOException {
        try {
            var content = ConfinedFiles.read(home, path);
            if (content.bytes() == null) throw new Limit("User Git rule file exceeds 2 MB");
            budget.bytes(content.bytes().length);
            watches.accept(new FileWatchInput(home, path, false, FileWatchInput.Kind.RULES));
            return content.bytes();
        } catch (NoSuchFileException missing) {
            watches.accept(new FileWatchInput(home, path, false, FileWatchInput.Kind.RULES));
            return null;
        }
    }

    private record WorkingHash(ObjectId id, long size, boolean executable, String sha256) {}

    /** Hashes through one held channel. Normalized text uses a bounded counting pass before the Git blob hash. */
    private WorkingHash workingHash(String path, Map<String, Attribute> attributes, Config config, Budget budget, boolean lfs) throws IOException {
        try (var channel = ConfinedFiles.channel(scope, path)) {
            var before = channel.stamp();
            long size = before.size();
            boolean normalize = normalization(attributes, config) != 0 && !lfs;
            if (normalize) {
                size = consume(input(channel, attributes, config, budget), null, null);
                if (!before.equals(channel.stamp())) throw new Limit("File changed while comparing - refresh to retry");
                channel.position(0);
            }
            var sha1 = Constants.newMessageDigest();
            sha1.update(("blob " + size + "\0").getBytes(StandardCharsets.US_ASCII));
            java.security.MessageDigest sha256 = null;
            if (lfs) try { sha256 = java.security.MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            long count = consume(input(channel, lfs ? Map.of() : attributes, lfs ? new Config() : config, budget), sha1, sha256);
            if (count != size || !before.equals(channel.stamp())) throw new Limit("File changed while comparing - refresh to retry");
            return new WorkingHash(ObjectId.fromRaw(sha1.digest()), count, before.executable(),
                sha256 == null ? null : HexFormat.of().formatHex(sha256.digest()));
        }
    }

    private static java.io.InputStream input(ConfinedFiles.ReadChannel channel, Map<String, Attribute> attributes, Config config, Budget budget) {
        var raw = new java.io.InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                budget.step();
                int count = channel.read(ByteBuffer.wrap(bytes, offset, length));
                if (count > 0) budget.bytes(count);
                return count;
            }
            // The outer operation owns the channel across both passes.
            @Override public void close() {}
        };
        int normalization = normalization(attributes, config);
        return normalization == 0 ? raw : AutoLFInputStream.create(raw, normalization == 2
            ? new AutoLFInputStream.StreamFlag[]{AutoLFInputStream.StreamFlag.DETECT_BINARY}
            : new AutoLFInputStream.StreamFlag[0]);
    }

    private static long consume(java.io.InputStream input, java.security.MessageDigest sha1, java.security.MessageDigest sha256) throws IOException {
        long size = 0;
        byte[] bytes = new byte[64 * 1024];
        try (input) {
            int count;
            while ((count = input.read(bytes)) >= 0) {
                size += count;
                if (sha1 != null) sha1.update(bytes, 0, count);
                if (sha256 != null) sha256.update(bytes, 0, count);
            }
        }
        return size;
    }

    private byte[] workingOptional(String path, Budget budget) throws IOException {
        watches.accept(new FileWatchInput(scope, path, false, FileWatchInput.Kind.RULES));
        try { return workingBytes(path, ConfinedFiles.MAX_BYTES, budget); }
        catch (NoSuchFileException missing) { return null; }
    }

    private byte[] workingBytes(String path, int limit, Budget budget) throws IOException {
        try (var channel = ConfinedFiles.channel(scope, path)) {
            if (channel.size() > limit) throw new Limit("File exceeds the 16 MB comparison limit");
            var out = new ByteArrayOutputStream();
            var buffer = ByteBuffer.allocate(64 * 1024);
            int count;
            while ((count = channel.read(buffer)) >= 0) {
                budget.bytes(count);
                if (out.size() + count > limit) throw new Limit("File exceeds the comparison limit");
                out.write(buffer.array(), 0, count); buffer.clear();
            }
            return out.toByteArray();
        }
    }

    private static List<String> parents(String path) {
        var result = new ArrayList<String>(); result.add("");
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') result.add(path.substring(0, i + 1));
        return result;
    }

    /** 0: raw, 1: text, 2: automatic text/binary detection. */
    private static int normalization(Map<String, Attribute> attributes, Config config) {
        Attribute text = attributes.get("text");
        Attribute binary = attributes.get("binary");
        if ((binary != null && binary.getState() == Attribute.State.SET) || (text != null && text.getState() == Attribute.State.UNSET)) return 0;
        String auto = config.getString("core", null, "autocrlf");
        boolean enabled = (text != null && text.getState() != Attribute.State.UNSPECIFIED)
            || value(attributes.get("eol")) != null || "true".equalsIgnoreCase(auto) || "input".equalsIgnoreCase(auto);
        if (!enabled) return 0;
        if (text != null && text.getState() == Attribute.State.SET) return 1;
        if ("auto".equals(value(text))) return 2;
        return value(attributes.get("eol")) != null ? 1 : 2;
    }

    private static String value(Attribute attribute) {
        return attribute != null && attribute.getState() == Attribute.State.CUSTOM ? attribute.getValue() : null;
    }

    private static Content display(String path, byte[] bytes, long size, String notice) {
        for (byte value : bytes) if (value == 0) return new Content(path, null, size, "Binary file");
        return new Content(path, bytes, size, notice);
    }

    private static byte[] objectBytes(ObjectReader reader, ObjectId id, int limit) throws IOException {
        var object = reader.open(id, Constants.OBJ_BLOB);
        return object.getSize() > limit ? null : object.getCachedBytes(limit);
    }

    private Content localLfsContent(String path, String oid, byte[] pointer, Budget budget) throws IOException {
        String object = "lfs/objects/" + oid.substring(0, 2) + "/" + oid.substring(2, 4) + "/" + oid;
        try (var channel = ConfinedFiles.channel(common, object, true)) {
            long size = channel.size();
            if (size > ConfinedFiles.MAX_BYTES) return new Content(path, null, size, "Local LFS file exceeds 2 MB");
            var out = new ByteArrayOutputStream();
            var buffer = ByteBuffer.allocate(64 * 1024);
            int count;
            while ((count = channel.read(buffer)) >= 0) {
                budget.bytes(count);
                if (out.size() + count > ConfinedFiles.MAX_BYTES)
                    return new Content(path, null, channel.size(), "Local LFS file exceeds 2 MB");
                out.write(buffer.array(), 0, count); buffer.clear();
            }
            byte[] bytes = out.toByteArray();
            return lfsMatches(pointer, bytes) ? display(path, bytes, bytes.length, "Local LFS content") : null;
        } catch (NoSuchFileException missing) { return null; }
    }

    private static boolean containsCrlf(ObjectReader reader, ObjectId id, Budget budget) throws IOException {
        try (var stream = reader.open(id, Constants.OBJ_BLOB).openStream()) {
            byte[] bytes = new byte[64 * 1024];
            boolean previousCr = false;
            int count;
            while ((count = stream.read(bytes)) >= 0) {
                budget.bytes(count);
                for (int i = 0; i < count; i++) {
                    if (previousCr && bytes[i] == '\n') return true;
                    previousCr = bytes[i] == '\r';
                }
            }
            return false;
        }
    }

    private static String lfsOid(byte[] pointer) {
        if (pointer.length > 4096) return null;
        String value = text(pointer);
        if (!value.startsWith("version https://git-lfs.github.com/spec/v1\n")) return null;
        for (String line : value.split("\n")) if (line.matches("oid sha256:[0-9a-f]{64}")) return line.substring(11);
        return null;
    }

    private static boolean lfsMatches(byte[] pointer, byte[] bytes) {
        String expected = lfsOid(pointer);
        if (expected == null) return false;
        try {
            String actual = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            return lfsMatches(pointer, actual, bytes.length);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static boolean lfsMatches(byte[] pointer, String hash, long size) {
        String expected = lfsOid(pointer);
        return expected != null && expected.equals(hash) && text(pointer).contains("\nsize " + size + "\n");
    }

    private static boolean validRemote(String name) { return Repository.isValidRefName("refs/remotes/" + name + "/HEAD"); }

    private static String checkedPath(String path) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0
            || (com.sun.jna.Platform.isWindows() && (path.indexOf('\\') >= 0 || path.matches("[A-Za-z]:.*"))))
            throw new IOException("Invalid scoped file path");
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equals(".git"))
            throw new IOException("Invalid scoped file path");
        return path;
    }

    private static byte[] optional(ConfinedFiles.Root root, String path, int limit, Budget budget) throws IOException {
        try { return ConfinedGitObjects.read(root, path, limit, budget); }
        catch (NoSuchFileException missing) { return null; }
    }

    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
}
