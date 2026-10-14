package toomanyagents.screens;

import com.sun.jna.FunctionMapper;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads files and folders under a granted root without following a swapped symlink out of it.
 *
 * <p>Each request opens the path normally (so in-root symlinks work), asks the OS where the open handle really is,
 * and only reads or lists through that handle when it is inside the root. The check is repeated after reading, so a
 * folder or root renamed mid-request returns nothing. Checking a path and then opening it again would race.
 *
 * <p>Uses Minecraft's bundled JNA. Supported: macOS, Linux (x86-64, arm64) and Windows, all 64-bit.
 */
public final class ConfinedFiles {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    /** The most entries one listing returns. Larger folders report {@code truncated}. */
    public static final int MAX_ENTRIES = 10_000;

    public enum Kind { FILE, DIRECTORY, LINK, OTHER }

    /** The real path of a granted folder. Only {@link #grant} makes one, so a URL or request can't widen it. */
    public static final class Root {
        private final String path;

        private Root(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /** {@code path} is the real absolute path, {@code relative} the same under the root with {@code /} separators. */
    public record Info(String path, String relative, Kind kind, long size) {}

    /** {@code bytes} is null when the file is over the limit. */
    public record Content(Info info, byte[] bytes) {}

    /** Entries are in directory order; {@code truncated} means there were more than requested. */
    public record Listing(Info info, List<Entry> entries, boolean truncated) {}

    /** A link's kind is the link itself; resolve it with {@link #info}. */
    public record Entry(String name, Kind kind) {}

    /** The request resolved outside the root. The message names only the requested path. */
    public static final class OutsideRoot extends FileSystemException {
        public OutsideRoot(String path) {
            super(path, null, "outside the viewed folder");
        }
    }

    private ConfinedFiles() {}

    /** Pins a folder's real path. Later requests stay under that path even if the folder is moved or replaced. */
    public static Root grant(Path folder) throws IOException {
        var os = os();
        try (var handle = os.open(folder.toAbsolutePath().toString())) {
            var real = handle.realPath();
            if (handle.kind() != Kind.DIRECTORY) throw new FileSystemException(folder.toString(), null, "not a folder");
            return new Root(real);
        }
    }

    /** Pins an expected real directory without accepting a symlink redirect during metadata discovery. */
    public static Root grantExact(Path folder) throws IOException {
        String expected = folder.toAbsolutePath().normalize().toString();
        try (var handle = os().open(expected)) {
            if (!expected.equals(handle.realPath())) throw new OutsideRoot(expected);
            if (handle.kind() != Kind.DIRECTORY) throw new FileSystemException(expected, null, "not a folder");
            if (!expected.equals(handle.realPath())) throw new OutsideRoot(expected);
            return new Root(expected);
        }
    }

    /** Size, modification time and Git's executable bit, read from the held file. */
    public record Stamp(long size, long modified, boolean executable) {}

    /** A read-only positional channel, retaining and checking the original OS handle for every read. */
    public static ReadChannel channel(Root root, String path) throws IOException {
        return channel(root, path, false);
    }

    /** Metadata callers can reject redirects even when the target remains inside the root. */
    public static ReadChannel channel(Root root, String path, boolean exactPath) throws IOException {
        Handle handle = openInside(root, path);
        try {
            var found = info(root, path, handle);
            if (found.kind() != Kind.FILE) throw new FileSystemException(path, null, "not a file");
            String expected = Path.of(root.path).resolve(path).normalize().toString();
            if (exactPath && !expected.equals(found.path())) throw new OutsideRoot(path);
            return new ReadChannel(root, path, exactPath ? expected : null, handle);
        } catch (Throwable failure) { handle.close(); throw failure; }
    }

    public static final class ReadChannel implements SeekableByteChannel {
        private final Root root;
        private final String path, expected;
        private final Handle handle;
        private boolean open = true;
        private long position;
        private ReadChannel(Root root, String path, String expected, Handle handle) {
            this.root = root; this.path = path; this.expected = expected; this.handle = handle;
        }
        private void validate() throws IOException {
            if (!open) throw new ClosedChannelException();
            cancelled();
            checkInside(root, path, handle);
            if (expected != null && !expected.equals(handle.realPath())) throw new OutsideRoot(path);
        }
        @Override public synchronized int read(ByteBuffer target) throws IOException {
            validate();
            if (!target.hasRemaining()) return 0;
            // Keep bytes private until the post-read check has succeeded.
            byte[] bytes = new byte[Math.min(target.remaining(), 64 * 1024)];
            int count = handle.readAt(position, bytes);
            validate();
            if (count > 0) { target.put(bytes, 0, count); position += count; }
            return count;
        }
        @Override public synchronized long position() throws IOException { validate(); return position; }
        @Override public synchronized ReadChannel position(long value) throws IOException {
            validate();
            if (value < 0) throw new IllegalArgumentException("negative position");
            position = value;
            return this;
        }
        public synchronized Stamp stamp() throws IOException {
            validate(); var stamp = handle.stamp(); validate(); return stamp;
        }
        @Override public synchronized long size() throws IOException { return stamp().size(); }
        @Override public int write(ByteBuffer bytes) { throw new NonWritableChannelException(); }
        @Override public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }
        @Override public synchronized boolean isOpen() { return open; }
        @Override public synchronized void close() { if (open) { open = false; handle.close(); } }
    }

    /** Reads the link text through its held parent directory, without opening or reading the target. */
    public static byte[] readLink(Root root, String path) throws IOException {
        Path requested = Path.of(root.path).resolve(path).normalize();
        if (requested.getParent() == null) throw new NoSuchFileException(path);
        try (var parent = openInside(root, requested.getParent().toString())) {
            info(root, path, parent);
            byte[] text = parent.readLink(requested.getFileName().toString());
            checkInside(root, path, parent);
            return text;
        }
    }

    /** Where {@code path} (absolute, or relative to the root) leads, following links. */
    public static Info info(Root root, String path) throws IOException {
        try (var handle = openInside(root, path)) {
            var info = info(root, path, handle);
            checkInside(root, path, handle);
            return info;
        }
    }

    /** A regular file's bytes, up to {@code maxBytes} (at most {@link #MAX_BYTES}). */
    public static Content read(Root root, String path, int maxBytes) throws IOException {
        int limit = Math.min(Math.max(0, maxBytes), MAX_BYTES);
        try (var handle = openInside(root, path)) {
            var info = info(root, path, handle);
            if (info.kind() != Kind.FILE) throw new FileSystemException(path, null, "not a file");
            byte[] bytes = info.size() > limit ? null : handle.read(limit);
            // The file can grow after the size check.
            if (bytes != null && bytes.length > limit) bytes = null;
            checkInside(root, path, handle);
            return new Content(info, bytes);
        }
    }

    public static Content read(Root root, String path) throws IOException {
        return read(root, path, MAX_BYTES);
    }

    /** Up to {@code maxEntries} (1 to {@link #MAX_ENTRIES}) entries of a folder, without {@code .} and {@code ..}. */
    public static Listing list(Root root, String path, int maxEntries) throws IOException {
        if (maxEntries < 1 || maxEntries > MAX_ENTRIES) throw new IllegalArgumentException("maxEntries " + maxEntries);
        try (var handle = openInside(root, path)) {
            var info = info(root, path, handle);
            if (info.kind() != Kind.DIRECTORY) throw new FileSystemException(path, null, "not a folder");
            var entries = handle.list(maxEntries + 1);
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                // Some filesystems don't report a type; ask for it through a checked child handle instead.
                if (entry.kind() == null) entries.set(i, new Entry(entry.name(), childKind(root, info.path(), entry.name())));
            }
            checkInside(root, path, handle);
            boolean truncated = entries.size() > maxEntries;
            return new Listing(info, truncated ? List.copyOf(entries.subList(0, maxEntries)) : List.copyOf(entries), truncated);
        }
    }

    /** A bounded batch from a retained directory handle. The last full batch may require one empty read for done. */
    public record DirectoryBatch(Info info, List<Entry> entries, boolean done) {}

    public static Directory directory(Root root, String path) throws IOException {
        Handle handle = openInside(root, path);
        try {
            var found = info(root, path, handle);
            if (found.kind() != Kind.DIRECTORY) throw new FileSystemException(path, null, "not a folder");
            return new Directory(root, path, handle, handle.entries());
        } catch (Throwable failure) { handle.close(); throw failure; }
    }

    /** Owns both the original verified handle and its enumeration cursor. Callers must close it. */
    public static final class Directory implements AutoCloseable {
        private final Root root;
        private final String path;
        private final Handle handle;
        private final EntryCursor cursor;
        private boolean closed;
        private Directory(Root root, String path, Handle handle, EntryCursor cursor) {
            this.root = root; this.path = path; this.handle = handle; this.cursor = cursor;
        }
        public synchronized DirectoryBatch next(int max) throws IOException {
            if (closed) throw new ClosedChannelException();
            if (max < 1 || max > MAX_ENTRIES) throw new IllegalArgumentException("maxEntries " + max);
            cancelled();
            var found = info(root, path, handle);
            var entries = cursor.next(max);
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                if (entry.kind() == null) entries.set(i, new Entry(entry.name(), childKind(root, found.path(), entry.name())));
            }
            checkInside(root, path, handle);
            return new DirectoryBatch(found, List.copyOf(entries), cursor.done());
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try { cursor.close(); } finally { handle.close(); }
        }
    }

    private interface EntryCursor extends AutoCloseable {
        List<Entry> next(int max) throws IOException;
        boolean done();
        @Override void close() throws IOException;
    }

    private static Kind childKind(Root root, String folder, String name) throws InterruptedIOException {
        try {
            return info(root, Path.of(folder).resolve(name).toString()).kind();
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return Kind.OTHER;
        }
    }

    private static Handle openInside(Root root, String path) throws IOException {
        if (path.indexOf('\0') >= 0) throw new NoSuchFileException(path);
        cancelled();
        String full;
        try {
            full = Path.of(root.path).resolve(path).toString();
        } catch (InvalidPathException e) {
            throw new NoSuchFileException(path);
        }
        return os().open(full);
    }

    /** Type and size come from the handle, so they describe the file whose real path was just checked. */
    private static Info info(Root root, String path, Handle handle) throws IOException {
        var real = handle.realPath();
        var relative = relative(root, real);
        if (relative == null) throw new OutsideRoot(path);
        var kind = handle.kind();
        return new Info(real, relative, kind, kind == Kind.FILE ? handle.size() : 0);
    }

    private static void checkInside(Root root, String path, Handle handle) throws IOException {
        if (relative(root, handle.realPath()) == null) throw new OutsideRoot(path);
    }

    /**
     * The real path under the root, or null when outside. Both sides come from the same OS call, which reports the
     * stored name, so an exact comparison keeps each volume's own case rules.
     */
    private static String relative(Root root, String real) {
        if (real.equals(root.path)) return "";
        char separator = Platform.isWindows() ? '\\' : '/';
        var prefix = root.path.charAt(root.path.length() - 1) == separator ? root.path : root.path + separator;
        if (!real.startsWith(prefix)) return null;
        var relative = real.substring(prefix.length());
        return separator == '/' ? relative : relative.replace(separator, '/');
    }

    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
    }

    private static Os os() {
        if (Platform.is64Bit()) {
            if (Platform.isMac()) return MAC;
            if (Platform.isLinux() && (Platform.ARCH.equals("x86-64") || Platform.ARCH.equals("aarch64"))) return LINUX;
            if (Platform.isWindows()) return WINDOWS;
        }
        throw new UnsupportedOperationException("Files can't be shown safely on " + Platform.ARCH + " " + System.getProperty("os.name"));
    }

    private interface Os {
        Handle open(String path) throws IOException;
    }

    private interface Handle extends AutoCloseable {
        String realPath() throws IOException;

        Kind kind() throws IOException;

        long size() throws IOException;

        int readAt(long offset, byte[] bytes) throws IOException;

        Stamp stamp() throws IOException;

        default byte[] readLink(String name) throws IOException {
            throw new IOException("Symbolic link comparison is unavailable on this platform");
        }

        /** Reads to the end or to {@code limit + 1} bytes, so the caller can tell the file was larger. */
        byte[] read(int limit) throws IOException;

        /** Up to {@code max} entries; a null kind means the filesystem didn't say. */
        default List<Entry> list(int max) throws IOException {
            try (var cursor = entries()) { return cursor.next(max); }
        }

        EntryCursor entries() throws IOException;

        @Override
        void close();
    }

    // macOS and Linux

    /** The few libc calls we need. Paths are passed as NUL-terminated UTF-8 bytes. */
    private interface Libc extends Library {
        int open(byte[] path, int flags);

        int close(int fd);

        int fstat(int fd, Pointer stat);

        long read(int fd, byte[] buffer, long count);

        long pread(int fd, byte[] buffer, long count, long offset);

        long readlinkat(int fd, byte[] name, byte[] buffer, long size);

        /** Variadic; JNA passes the trailing arguments the right way on Apple arm64. */
        int fcntl(int fd, int command, Object... arguments);

        int dup(int fd);

        Pointer fdopendir(int fd);

        Pointer readdir(Pointer dir);

        int closedir(Pointer dir);
    }

    private static final class Posix {
        static final Libc LIBC = libc();
    }

    private static Libc libc() {
        // Intel macOS keeps a 32-bit-inode dirent under the plain names; the 64-bit-inode versions are suffixed.
        FunctionMapper mapper = (library, method) -> Platform.isMac() && Platform.isIntel()
            && (method.getName().equals("fdopendir") || method.getName().equals("readdir") || method.getName().equals("fstat"))
            ? method.getName() + "$INODE64" : method.getName();
        return Native.load("c", Libc.class, Map.of(Library.OPTION_FUNCTION_MAPPER, mapper));
    }

    private static final class Unix implements Os {
        private final boolean mac;
        private final int flags;

        Unix(boolean mac, int flags) {
            this.mac = mac;
            this.flags = flags;
        }

        @Override
        public Handle open(String path) throws IOException {
            var utf8 = path.getBytes(StandardCharsets.UTF_8);
            int fd = Posix.LIBC.open(Arrays.copyOf(utf8, utf8.length + 1), flags);
            if (fd < 0) throw error(path, Native.getLastError(), mac);
            return new UnixHandle(this, fd, path);
        }
    }

    /*
     * open() flags: read-only, close on exec, don't wait on a FIFO or device, and never become a controlling
     * terminal. Opening a FIFO with O_NONBLOCK returns at once; we then reject it by type before reading.
     * macOS values: <sys/fcntl.h>. Linux values: asm-generic/fcntl.h, shared by x86-64 and arm64.
     */
    private static final Os MAC = new Unix(true, 0x0004 /* O_NONBLOCK */ | 0x20000 /* O_NOCTTY */ | 0x01000000 /* O_CLOEXEC */);
    private static final Os LINUX = new Unix(false, 0x800 /* O_NONBLOCK */ | 0x100 /* O_NOCTTY */ | 0x80000 /* O_CLOEXEC */);

    /** macOS fcntl command that writes the real path of an open descriptor (<sys/fcntl.h>), into MAXPATHLEN bytes. */
    private static final int F_GETPATH = 50;
    private static final int MAXPATHLEN = 1024;

    /** readdir d_type values, the same on macOS and Linux (<sys/dirent.h>). */
    private static final int DT_UNKNOWN = 0, DT_DIR = 4, DT_REG = 8, DT_LNK = 10;

    private static final class UnixHandle implements Handle {
        private final Unix os;
        private final int fd;
        private final String requested;
        private BasicFileAttributes attributes;

        UnixHandle(Unix os, int fd, String requested) {
            this.os = os;
            this.fd = fd;
            this.requested = requested;
        }

        /** The descriptor as a path: macOS /dev/fd/N duplicates it, Linux /proc/self/fd/N points at the same file. */
        private Path self() {
            return Path.of(os.mac ? "/dev/fd/" + fd : "/proc/self/fd/" + fd);
        }

        @Override
        public String realPath() throws IOException {
            if (os.mac) {
                byte[] buffer = new byte[MAXPATHLEN];
                if (Posix.LIBC.fcntl(fd, F_GETPATH, buffer) < 0) throw error(requested, Native.getLastError(), true);
                return Native.toString(buffer, StandardCharsets.UTF_8.name());
            }
            var real = Files.readSymbolicLink(self()).toString();
            // Linux marks a removed file this way; treat it as gone.
            if (real.endsWith(" (deleted)") || !real.startsWith("/")) throw new NoSuchFileException(requested);
            return real;
        }

        /** Stats the open file itself; nothing here looks the path up again. */
        private BasicFileAttributes attributes() throws IOException {
            if (attributes == null) attributes = Files.readAttributes(self(), BasicFileAttributes.class);
            return attributes;
        }

        @Override
        public Kind kind() throws IOException {
            var attributes = attributes();
            return attributes.isRegularFile() ? Kind.FILE : attributes.isDirectory() ? Kind.DIRECTORY : Kind.OTHER;
        }

        @Override
        public long size() throws IOException {
            return attributes().size();
        }

        @Override
        public Stamp stamp() throws IOException {
            if (os.mac) {
                // Darwin stat64: mode at 4, mtime at 48/56, size at 96. /dev/fd masks executable bits.
                try (var stat = new Memory(256)) {
                    if (Posix.LIBC.fstat(fd, stat) < 0) throw error(requested, Native.getLastError(), true);
                    return new Stamp(stat.getLong(96), stat.getLong(48) * 1_000_000_000L + stat.getLong(56),
                        (stat.getShort(4) & 0100) != 0);
                }
            }
            var attributes = Files.readAttributes(self(), BasicFileAttributes.class);
            int mode = (Integer) Files.getAttribute(self(), "unix:mode");
            return new Stamp(attributes.size(), attributes.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS), (mode & 0100) != 0);
        }

        @Override
        public byte[] readLink(String name) throws IOException {
            byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
            byte[] buffer = new byte[4096];
            long count = Posix.LIBC.readlinkat(fd, Arrays.copyOf(utf8, utf8.length + 1), buffer, buffer.length);
            if (count < 0) throw error(requested, Native.getLastError(), os.mac);
            if (count == buffer.length) throw new IOException("Symbolic link text exceeds the read limit");
            return Arrays.copyOf(buffer, (int) count);
        }

        @Override
        public int readAt(long offset, byte[] bytes) throws IOException {
            long count = Posix.LIBC.pread(fd, bytes, bytes.length, offset);
            if (count < 0) throw error(requested, Native.getLastError(), os.mac);
            return count == 0 ? -1 : (int) count;
        }

        @Override
        public byte[] read(int limit) throws IOException {
            var out = new ByteArrayOutputStream((int) Math.min(size(), limit) + 1);
            var buffer = new byte[64 * 1024];
            while (out.size() <= limit) {
                cancelled();
                long count = Posix.LIBC.read(fd, buffer, Math.min(buffer.length, limit + 1 - out.size()));
                if (count < 0) throw error(requested, Native.getLastError(), os.mac);
                if (count == 0) break;
                out.write(buffer, 0, (int) count);
            }
            return out.toByteArray();
        }

        @Override
        public EntryCursor entries() throws IOException { return os.mac ? entriesMac() : entriesLinux(); }

        private EntryCursor entriesMac() throws IOException {
            int copy = Posix.LIBC.dup(fd);
            if (copy < 0) throw error(requested, Native.getLastError(), true);
            Pointer dir = Posix.LIBC.fdopendir(copy);
            if (dir == null) {
                int errno = Native.getLastError(); Posix.LIBC.close(copy); throw error(requested, errno, true);
            }
            return new EntryCursor() {
                private boolean done;
                @Override public List<Entry> next(int max) throws IOException {
                    var entries = new ArrayList<Entry>();
                    while (!done && entries.size() < max) {
                        cancelled(); Native.setLastError(0);
                        Pointer entry = Posix.LIBC.readdir(dir);
                        if (entry == null) {
                            if (Native.getLastError() != 0) throw error(requested, Native.getLastError(), true);
                            done = true; break;
                        }
                        // Darwin dirent64: name length at 18, type at 20, name at 21.
                        var name = new String(entry.getByteArray(21, entry.getShort(18) & 0xffff), StandardCharsets.UTF_8);
                        if (name.equals(".") || name.equals("..")) continue;
                        int type = entry.getByte(20) & 255;
                        entries.add(new Entry(name, type == DT_UNKNOWN ? null : type == DT_DIR ? Kind.DIRECTORY
                            : type == DT_REG ? Kind.FILE : type == DT_LNK ? Kind.LINK : Kind.OTHER));
                    }
                    return entries;
                }
                @Override public boolean done() { return done; }
                @Override public void close() { Posix.LIBC.closedir(dir); }
            };
        }

        private EntryCursor entriesLinux() throws IOException {
            var stream = Files.newDirectoryStream(self());
            var iterator = stream.iterator();
            return new EntryCursor() {
                private boolean done;
                @Override public List<Entry> next(int max) throws IOException {
                    var entries = new ArrayList<Entry>();
                    while (entries.size() < max) {
                        cancelled();
                        if (!iterator.hasNext()) { done = true; break; }
                        var child = iterator.next();
                        try {
                            var attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            entries.add(new Entry(child.getFileName().toString(), attributes.isSymbolicLink() ? Kind.LINK
                                : attributes.isDirectory() ? Kind.DIRECTORY : attributes.isRegularFile() ? Kind.FILE : Kind.OTHER));
                        } catch (NoSuchFileException removed) { /* A concurrent removal contributes no entry. */ }
                    }
                    return entries;
                }
                @Override public boolean done() { return done; }
                @Override public void close() throws IOException { stream.close(); }
            };
        }

        @Override
        public void close() {
            Posix.LIBC.close(fd);
        }
    }

    /** errno values: ENOENT, ENOTDIR and EACCES/EPERM match on macOS and Linux; ELOOP differs. */
    private static IOException error(String path, int errno, boolean mac) {
        return switch (errno) {
            case 2, 20 -> new NoSuchFileException(path);
            case 1, 13 -> new AccessDeniedException(path);
            default -> errno == (mac ? 62 : 40)
                ? new FileSystemException(path, null, "too many symbolic links")
                : new FileSystemException(path, null, "error " + errno);
        };
    }

    // Windows

    /** Not in jna-platform 5.14. */
    private interface Kernel32Paths extends Library {
        boolean SetFilePointerEx(WinNT.HANDLE handle, long offset, Pointer newPosition, int origin);

        int GetFinalPathNameByHandleW(WinNT.HANDLE handle, char[] buffer, int length, int flags);
    }

    private static final class Windows {
        static final Kernel32Paths PATHS = Native.load("kernel32", Kernel32Paths.class);
    }

    private static final Os WINDOWS = new Os() {
        /**
         * Only local drive paths, opened in the \\?\ form: no length limit, and names like CON or COM1 stay files.
         * Network and device paths are refused before CreateFile can contact anything.
         */
        @Override
        public Handle open(String requested) throws IOException {
            String path;
            try {
                var normalized = Path.of(requested).normalize();
                var root = normalized.getRoot();
                // The \\?\ form skips Win32's own ".." handling, which normalize() has already done the same way.
                if (root == null || !root.toString().matches("[A-Za-z]:\\\\")) throw new NoSuchFileException(requested);
                path = "\\\\?\\" + normalized;
            } catch (InvalidPathException e) {
                throw new NoSuchFileException(requested);
            }
            // BACKUP_SEMANTICS lets CreateFile open folders. The SQOS flags stop a named pipe reached through a
            // link from impersonating us. Sharing everything means we never block the user's own edits.
            var handle = Kernel32.INSTANCE.CreateFile(path, WinNT.GENERIC_READ,
                WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE, null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS | 0x00100000 /* SECURITY_SQOS_PRESENT */ | 0x00010000 /* SECURITY_IDENTIFICATION */,
                null);
            if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw windowsError(requested, Kernel32.INSTANCE.GetLastError());
            return new WindowsHandle(handle, requested);
        }
    };

    private static final class WindowsHandle implements Handle {
        private final WinNT.HANDLE handle;
        private final String requested;
        private WinBase.FILE_STANDARD_INFO standard;

        WindowsHandle(WinNT.HANDLE handle, String requested) {
            this.handle = handle;
            this.requested = requested;
        }

        /** FILE_NAME_NORMALIZED | VOLUME_NAME_DOS (both 0): long names in their stored case, e.g. \\?\C:\Users\me. */
        @Override
        public String realPath() throws IOException {
            var buffer = new char[1024];
            int length = Windows.PATHS.GetFinalPathNameByHandleW(handle, buffer, buffer.length, 0);
            if (length >= buffer.length) {
                buffer = new char[length + 1];
                length = Windows.PATHS.GetFinalPathNameByHandleW(handle, buffer, buffer.length, 0);
            }
            if (length <= 0 || length >= buffer.length) throw windowsError(requested, Kernel32.INSTANCE.GetLastError());
            var real = new String(buffer, 0, length);
            // Network shares (\\?\UNC\...) aren't supported.
            if (!real.startsWith("\\\\?\\") || real.startsWith("\\\\?\\UNC\\")) throw new OutsideRoot(requested);
            return real.substring(4);
        }

        private WinBase.FILE_STANDARD_INFO standard() throws IOException {
            if (standard == null) {
                var info = new WinBase.FILE_STANDARD_INFO();
                if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileStandardInfo, info.getPointer(),
                    new WinDef.DWORD(info.size()))) throw windowsError(requested, Kernel32.INSTANCE.GetLastError());
                info.read();
                if (info.DeletePending) throw new NoSuchFileException(requested);
                standard = info;
            }
            return standard;
        }

        @Override
        public Kind kind() throws IOException {
            // FILE_TYPE_DISK = 1: anything else (a pipe, console or device) isn't a file we show.
            if (Kernel32.INSTANCE.GetFileType(handle) != 1) return Kind.OTHER;
            return standard().Directory ? Kind.DIRECTORY : Kind.FILE;
        }

        @Override
        public long size() throws IOException {
            return standard().EndOfFile.getValue();
        }

        @Override
        public Stamp stamp() throws IOException {
            var info = new WinBase.FILE_BASIC_INFO();
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileBasicInfo, info.getPointer(),
                new WinDef.DWORD(info.size()))) throw windowsError(requested, Kernel32.INSTANCE.GetLastError());
            info.read();
            standard = null;
            return new Stamp(size(), info.LastWriteTime.getValue(), false);
        }

        @Override
        public int readAt(long offset, byte[] bytes) throws IOException {
            if (!Windows.PATHS.SetFilePointerEx(handle, offset, null, 0))
                throw windowsError(requested, Kernel32.INSTANCE.GetLastError());
            var count = new IntByReference();
            if (!Kernel32.INSTANCE.ReadFile(handle, bytes, bytes.length, count, null))
                throw windowsError(requested, Kernel32.INSTANCE.GetLastError());
            return count.getValue() == 0 ? -1 : count.getValue();
        }

        @Override
        public byte[] read(int limit) throws IOException {
            var out = new ByteArrayOutputStream((int) Math.min(size(), limit) + 1);
            var buffer = new byte[64 * 1024];
            var count = new IntByReference();
            while (out.size() <= limit) {
                cancelled();
                if (!Kernel32.INSTANCE.ReadFile(handle, buffer, Math.min(buffer.length, limit + 1 - out.size()), count, null))
                    throw windowsError(requested, Kernel32.INSTANCE.GetLastError());
                if (count.getValue() == 0) break;
                out.write(buffer, 0, count.getValue());
            }
            return out.toByteArray();
        }

        /** Retains unread records from the native buffer across page boundaries. */
        @Override public EntryCursor entries() {
            return new EntryCursor() {
                private final Memory buffer = new Memory(64 * 1024);
                private final java.util.ArrayDeque<Entry> pending = new java.util.ArrayDeque<>();
                private boolean first = true, done;
                @Override public List<Entry> next(int max) throws IOException {
                    var entries = new ArrayList<Entry>();
                    while (entries.size() < max) {
                        cancelled();
                        if (!pending.isEmpty()) { entries.add(pending.removeFirst()); continue; }
                        if (done) break;
                        if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,
                            first ? WinBase.FileFullDirectoryRestartInfo : WinBase.FileFullDirectoryInfo,
                            buffer, new WinDef.DWORD(buffer.size()))) {
                            int error = Kernel32.INSTANCE.GetLastError();
                            if (error == 18) { done = true; break; }
                            throw windowsError(requested, error);
                        }
                        first = false;
                        for (long offset = 0;;) {
                            int next = buffer.getInt(offset);
                            int length = buffer.getInt(offset + 60);
                            if (length < 0 || offset + 68L + length > buffer.size()) throw new IOException("Invalid directory record");
                            var name = new String(buffer.getCharArray(offset + 68, length / 2));
                            if (!name.equals(".") && !name.equals(".."))
                                pending.add(new Entry(name, windowsKind(buffer.getInt(offset + 56), buffer.getInt(offset + 64))));
                            if (next == 0) break;
                            if (next < 68 || offset + next + 68 > buffer.size()) throw new IOException("Invalid directory record");
                            offset += next;
                        }
                    }
                    return entries;
                }
                @Override public boolean done() { return done && pending.isEmpty(); }
                @Override public void close() { pending.clear(); buffer.close(); }
            };
        }

        @Override
        public void close() {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    /** Symlinks and junctions are links. Other reparse points (cloud placeholders, dedup) behave as files or folders. */
    private static Kind windowsKind(int attributes, int reparseTag) {
        if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0
            && (reparseTag == WinNT.IO_REPARSE_TAG_SYMLINK || reparseTag == WinNT.IO_REPARSE_TAG_MOUNT_POINT)) return Kind.LINK;
        return (attributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) != 0 ? Kind.DIRECTORY : Kind.FILE;
    }

    private static IOException windowsError(String path, int error) {
        return switch (error) {
            case 2, 3 -> new NoSuchFileException(path); // ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND
            case 5, 32 -> new AccessDeniedException(path); // ERROR_ACCESS_DENIED, ERROR_SHARING_VIOLATION
            default -> new FileSystemException(path, null, "error " + error);
        };
    }
}
