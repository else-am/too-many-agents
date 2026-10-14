package toomanyagents.screens;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.InflaterInputStream;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.dfs.*;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.*;

/** The only JGit storage adapter. It knows no working-tree path and cannot write or contact a remote. */
final class ConfinedGitObjects extends DfsObjDatabase {
    static final int MAX_OBJECT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    private final ConfinedFiles.Root git;
    private final Budget budget;

    static {
        DfsBlockCache.reconfigure(new DfsBlockCacheConfig().setBlockLimit(16 * 1024 * 1024).setBlockSize(64 * 1024));
    }

    ConfinedGitObjects(ConfinedFiles.Root git, Budget budget) {
        // Never share JGit's global pack cache across grants or refreshes.
        super(new InMemoryRepository(new DfsRepositoryDescription(UUID.randomUUID().toString())),
            new DfsReaderOptions().setStreamFileThreshold(ConfinedFiles.MAX_BYTES)
                .setDeltaBaseCacheLimit(4 * 1024 * 1024).setStreamPackBufferSize(64 * 1024)
                .setLoadRevIndexInParallel(false).setUseObjectSizeIndex(false));
        this.git = git;
        this.budget = budget;
    }

    static class Limit extends IOException {
        Limit(String message) { super(message); }
    }

    static final class ObjectLimit extends Limit {
        final long size;
        ObjectLimit(long size) { super("Git object exceeds the read limit"); this.size = size; }
    }

    /** Per-operation limits, also used by metadata, tree walks and working-file comparisons. */
    static final class Budget {
        private long remainingBytes = 128L * 1024 * 1024;
        private int remainingSteps = 50_000;
        private final long deadline = System.nanoTime() + 10_000_000_000L;
        void step() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
            if (--remainingSteps < 0 || System.nanoTime() > deadline) throw new Limit("Indexing incomplete - work limit reached");
        }
        void bytes(long count) throws IOException {
            step();
            remainingBytes -= count;
            if (remainingBytes < 0) throw new Limit("Indexing incomplete - byte limit reached");
        }
    }

    static byte[] read(ConfinedFiles.Root root, String path, int limit, Budget budget) throws IOException {
        try (var channel = ConfinedFiles.channel(root, path, true)) {
            if (channel.size() > limit) throw new Limit("Metadata or object exceeds its read limit");
            var out = new ByteArrayOutputStream();
            var buffer = ByteBuffer.allocate(64 * 1024);
            int count;
            while ((count = channel.read(buffer)) >= 0) {
                budget.bytes(count);
                if (out.size() + count > limit) throw new Limit("Metadata or object exceeds its read limit");
                out.write(buffer.array(), 0, count);
                buffer.clear();
            }
            return out.toByteArray();
        }
    }

    @Override protected List<DfsPackDescription> listPacks() throws IOException {
        budget.step();
        ConfinedFiles.Listing listing;
        try { listing = ConfinedFiles.list(git, "objects/pack", 256); }
        catch (NoSuchFileException missing) { return new ArrayList<>(); }
        if (listing.truncated()) throw new Limit("Too many Git pack files");
        var names = new HashSet<String>();
        for (var entry : listing.entries()) if (entry.kind() == ConfinedFiles.Kind.FILE) names.add(entry.name());
        var result = new ArrayList<DfsPackDescription>();
        for (String name : names) {
            if (!name.matches("pack-[0-9a-f]{40}\\.pack") || !names.contains(name.replace(".pack", ".idx"))) continue;
            if (result.size() >= 64) throw new Limit("Too many Git packs");
            String stem = name.substring(0, name.length() - 5);
            var pack = new DfsPackDescription(getRepository().getDescription(), stem, PackSource.RECEIVE);
            pack.addFileExt(PackExt.PACK);
            pack.addFileExt(PackExt.INDEX);
            result.add(pack);
        }
        return result;
    }

    /** Validate the allocation-driving index header before JGit parses the index. */
    private static void validateIndex(byte[] bytes) throws IOException {
        long size = bytes.length;
        if (size < 8 + 256 * 4 + 40) throw new IOException("Truncated Git pack index");
        var header = ByteBuffer.wrap(bytes);
        if (header.getInt() != 0xff744f63 || header.getInt() != 2) throw new IOException("Only Git pack index version 2 is supported");
        int previous = 0;
        for (int i = 0; i < 256; i++) {
            int count = header.getInt();
            if (count < previous || count > 250_000) throw new Limit("Git pack index exceeds the object limit");
            previous = count;
        }
        if (size < 8L + 256 * 4 + 28L * previous + 40) throw new IOException("Truncated Git pack index");
    }

    @Override protected ReadableChannel openFile(DfsPackDescription pack, PackExt extension) throws IOException {
        if (extension != PackExt.PACK && extension != PackExt.INDEX) throw new IOException("Unsupported pack extension");
        String name = pack.getFileName(extension);
        if (!name.matches("pack-[0-9a-f]{40}\\.(pack|idx)")) throw new IOException("Invalid pack name");
        if (extension == PackExt.INDEX) {
            byte[] bytes = read(git, "objects/pack/" + name, MAX_INDEX_BYTES, budget);
            // Parse exactly the bytes validated here; a second pathname open could replace the header.
            validateIndex(bytes);
            return new ReadableChannel() {
                private int position;
                private boolean open = true;
                @Override public int read(ByteBuffer target) throws IOException {
                    budget.step();
                    if (!open) throw new IOException("closed");
                    if (position == bytes.length) return -1;
                    int count = Math.min(target.remaining(), bytes.length - position);
                    target.put(bytes, position, count); position += count; return count;
                }
                @Override public long position() { return position; }
                @Override public void position(long value) throws IOException {
                    if (value < 0 || value > bytes.length) throw new IOException("Invalid index offset");
                    position = (int) value;
                }
                @Override public long size() { return bytes.length; }
                @Override public int blockSize() { return 0; }
                @Override public void setReadAheadBytes(int ignored) {}
                @Override public boolean isOpen() { return open; }
                @Override public void close() { open = false; }
            };
        }
        SeekableByteChannel channel = ConfinedFiles.channel(git, "objects/pack/" + name, true);
        try {
            long max = extension == PackExt.INDEX ? MAX_INDEX_BYTES : 1024L * 1024 * 1024;
            if (channel.size() > max) throw new Limit("Git pack exceeds the read limit");
            return new ReadableChannel() {
                @Override public int read(ByteBuffer target) throws IOException {
                    budget.step(); int count = channel.read(target); if (count > 0) budget.bytes(count); return count;
                }
                @Override public long position() throws IOException { return channel.position(); }
                @Override public void position(long value) throws IOException { channel.position(value); }
                @Override public long size() throws IOException { return channel.size(); }
                @Override public int blockSize() { return 0; }
                @Override public void setReadAheadBytes(int bytes) {}
                @Override public boolean isOpen() { return channel.isOpen(); }
                @Override public void close() throws IOException { channel.close(); }
            };
        } catch (Throwable failure) { channel.close(); throw failure; }
    }

    ObjectReader reader() { return new Reader(); }

    private final class Reader extends ObjectReader.Filter {
        private final ObjectReader packs = ConfinedGitObjects.this.newReader();
        @Override protected ObjectReader delegate() { return packs; }
        @Override public ObjectReader newReader() { return new Reader(); }
        @Override public ObjectLoader open(AnyObjectId id) throws IOException { return open(id, OBJ_ANY); }
        @Override public ObjectLoader open(AnyObjectId id, int hint) throws IOException {
            budget.step();
            ObjectLoader loader;
            try { loader = packs.open(id, hint); }
            catch (MissingObjectException missing) { loader = loose(id, hint); }
            if (loader.getSize() > MAX_OBJECT_BYTES) throw new ObjectLimit(loader.getSize());
            budget.bytes(loader.getSize());
            return loader;
        }
        @Override public boolean has(AnyObjectId id) throws IOException {
            try { open(id); return true; } catch (MissingObjectException missing) { return false; }
        }
        @Override public boolean has(AnyObjectId id, int hint) throws IOException {
            try { open(id, hint); return true; } catch (MissingObjectException missing) { return false; }
        }
        @Override public long getObjectSize(AnyObjectId id, int hint) throws IOException { return open(id, hint).getSize(); }
    }

    private ObjectLoader loose(AnyObjectId id, int hint) throws IOException {
        String name = id.name();
        byte[] compressed;
        try { compressed = read(git, "objects/" + name.substring(0, 2) + "/" + name.substring(2), MAX_OBJECT_BYTES, budget); }
        catch (NoSuchFileException missing) { throw new MissingObjectException(id.copy(), hint == ObjectReader.OBJ_ANY ? "unknown" : Constants.typeString(hint)); }
        byte[] raw;
        try (var inflated = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = inflated.read(buffer)) >= 0) {
                budget.bytes(count);
                if (out.size() == 0) {
                    int end = 0;
                    while (end < Math.min(count, 64) && buffer[end] != 0) end++;
                    if (end < Math.min(count, 64)) {
                        String header = new String(buffer, 0, end, StandardCharsets.US_ASCII);
                        int space = header.indexOf(' ');
                        if (space > 0) try {
                            long declared = Long.parseLong(header.substring(space + 1));
                            if (declared > MAX_OBJECT_BYTES) throw new ObjectLimit(declared);
                        } catch (NumberFormatException invalid) { throw new IOException("Invalid loose object size", invalid); }
                    }
                }
                if (out.size() + count > MAX_OBJECT_BYTES + 64) throw new Limit("Git object inflation exceeds 16 MB");
                out.write(buffer, 0, count);
            }
            raw = out.toByteArray();
        }
        int nul = 0;
        while (nul < Math.min(raw.length, 64) && raw[nul] != 0) nul++;
        if (nul == Math.min(raw.length, 64)) throw new IOException("Invalid loose object header");
        String[] header = new String(raw, 0, nul, StandardCharsets.US_ASCII).split(" ", -1);
        if (header.length != 2) throw new IOException("Invalid loose object header");
        int type = switch (header[0]) {
            case "commit" -> Constants.OBJ_COMMIT; case "tree" -> Constants.OBJ_TREE;
            case "blob" -> Constants.OBJ_BLOB; case "tag" -> Constants.OBJ_TAG;
            default -> throw new IOException("Invalid loose object type");
        };
        try {
            if (Long.parseLong(header[1]) != raw.length - nul - 1) throw new IOException("Invalid loose object size");
        } catch (NumberFormatException invalid) { throw new IOException("Invalid loose object size", invalid); }
        if (!ObjectId.fromRaw(Constants.newMessageDigest().digest(raw)).equals(id)) throw new IOException("Git object hash mismatch");
        if (hint != ObjectReader.OBJ_ANY && hint != type) throw new IncorrectObjectTypeException(id.copy(), hint);
        return new ObjectLoader.SmallObject(type, Arrays.copyOfRange(raw, nul + 1, raw.length));
    }

    @Override protected DfsPackDescription newPack(PackSource source) { throw new UnsupportedOperationException("read only"); }
    @Override protected void commitPackImpl(Collection<DfsPackDescription> add, Collection<DfsPackDescription> remove) { throw new UnsupportedOperationException("read only"); }
    @Override protected void rollbackPack(Collection<DfsPackDescription> packs) {}
    @Override protected DfsOutputStream writeFile(DfsPackDescription pack, PackExt extension) { throw new UnsupportedOperationException("read only"); }
    @Override public long getApproximateObjectCount() { return -1; }
}
