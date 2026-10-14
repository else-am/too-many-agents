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

        /** Reads to the end or to {@code limit + 1} bytes, so the caller can tell the file was larger. */
        byte[] read(int limit) throws IOException;

        /** Up to {@code max} entries; a null kind means the filesystem didn't say. */
        List<Entry> list(int max) throws IOException;

        @Override
        void close();
    }

    // macOS and Linux

    /** The few libc calls we need. Paths are passed as NUL-terminated UTF-8 bytes. */
    private interface Libc extends Library {
        int open(byte[] path, int flags);

        int close(int fd);

        long read(int fd, byte[] buffer, long count);

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
            && (method.getName().equals("fdopendir") || method.getName().equals("readdir"))
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
        public List<Entry> list(int max) throws IOException {
            return os.mac ? listMac(max) : listLinux(max);
        }

        /** Reads entries from a duplicate of this descriptor, so the folder can't change underneath us. */
        private List<Entry> listMac(int max) throws IOException {
            int copy = Posix.LIBC.dup(fd);
            if (copy < 0) throw error(requested, Native.getLastError(), true);
            Pointer dir = Posix.LIBC.fdopendir(copy);
            if (dir == null) {
                int errno = Native.getLastError();
                Posix.LIBC.close(copy);
                throw error(requested, errno, true);
            }
            try {
                var entries = new ArrayList<Entry>();
                while (entries.size() < max) {
                    cancelled();
                    Native.setLastError(0);
                    Pointer entry = Posix.LIBC.readdir(dir);
                    if (entry == null) {
                        if (Native.getLastError() != 0) throw error(requested, Native.getLastError(), true);
                        break;
                    }
                    // struct dirent with 64-bit inodes: d_namlen is a u16 at 18, d_type a byte at 20, d_name at 21.
                    int length = entry.getShort(18) & 0xffff;
                    var name = new String(entry.getByteArray(21, length), StandardCharsets.UTF_8);
                    if (name.equals(".") || name.equals("..")) continue;
                    int type = entry.getByte(20) & 0xff;
                    entries.add(new Entry(name, type == DT_UNKNOWN ? null
                        : type == DT_DIR ? Kind.DIRECTORY : type == DT_REG ? Kind.FILE : type == DT_LNK ? Kind.LINK : Kind.OTHER));
                }
                return entries;
            } finally {
                Posix.LIBC.closedir(dir);
            }
        }

        /** /proc/self/fd/N opens this very folder, and child lookups go through it rather than the original path. */
        private List<Entry> listLinux(int max) throws IOException {
            var entries = new ArrayList<Entry>();
            try (var stream = Files.newDirectoryStream(self())) {
                for (var child : stream) {
                    if (entries.size() >= max) break;
                    cancelled();
                    Kind kind;
                    try {
                        var attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        kind = attributes.isSymbolicLink() ? Kind.LINK : attributes.isDirectory() ? Kind.DIRECTORY
                            : attributes.isRegularFile() ? Kind.FILE : Kind.OTHER;
                    } catch (NoSuchFileException e) {
                        continue;
                    }
                    entries.add(new Entry(child.getFileName().toString(), kind));
                }
            }
            return entries;
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

        /** Enumerates through the folder handle itself. */
        @Override
        public List<Entry> list(int max) throws IOException {
            var entries = new ArrayList<Entry>();
            var buffer = new Memory(64 * 1024);
            boolean first = true;
            while (entries.size() < max) {
                cancelled();
                if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,
                    first ? WinBase.FileFullDirectoryRestartInfo : WinBase.FileFullDirectoryInfo, buffer, new WinDef.DWORD(buffer.size()))) {
                    int error = Kernel32.INSTANCE.GetLastError();
                    if (error == 18 /* ERROR_NO_MORE_FILES */) break;
                    throw windowsError(requested, error);
                }
                first = false;
                // FILE_FULL_DIR_INFO: NextEntryOffset at 0, FileAttributes at 56, FileNameLength (bytes) at 60,
                // EaSize at 64 (the reparse tag for reparse points), FileName (UTF-16) at 68.
                for (long offset = 0; entries.size() < max; ) {
                    int next = buffer.getInt(offset);
                    int attributes = buffer.getInt(offset + 56);
                    var name = new String(buffer.getCharArray(offset + 68, buffer.getInt(offset + 60) / 2));
                    if (!name.equals(".") && !name.equals("..")) entries.add(new Entry(name, windowsKind(attributes, buffer.getInt(offset + 64))));
                    if (next == 0) break;
                    offset += next;
                }
            }
            return entries;
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
