package toomanyagents.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Image copies live in the OS temporary directory, never in the agent's project. */
final class ChatAttachments {
    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    static boolean isImage(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp)$");
    }

    static Path imagePath(String clipboard) {
        String value = clipboard.strip();
        if (value.length() > 1 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'")))
            value = value.substring(1, value.length() - 1);
        try {
            Path path = value.startsWith("file:") ? Path.of(java.net.URI.create(value)) : Path.of(value);
            return path.isAbsolute() && isImage(path) && Files.isRegularFile(path) ? path : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    static String fileText(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path temporaryImage(String suffix) throws IOException {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "too-many-agents-images");
        Files.createDirectories(directory);
        return Files.createTempFile(directory, "image-", suffix);
    }

    static String copyImage(Path source) throws IOException {
        if (!isImage(source)) throw new IOException("Use a PNG, JPEG, GIF, or WebP image.");
        if (Files.size(source) > MAX_BYTES) throw new IOException("Images must be 5 MB or smaller.");
        String name = source.getFileName().toString();
        Path copy = temporaryImage(name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT));
        try {
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            validate(copy);
            return copy.toString();
        } catch (IOException failure) { Files.deleteIfExists(copy); throw failure; }
    }

    static void removeCopies(List<String> copies) {
        for (String copy : copies) {
            try {
                Path path = Path.of(copy);
                if (!path.getFileName().toString().matches("image-\\d+\\.(png|jpe?g|gif|webp)")) continue;
                Path directory = Path.of(System.getProperty("java.io.tmpdir"), "too-many-agents-images").toRealPath();
                if (path.getParent().toRealPath().equals(directory)) Files.deleteIfExists(path);
            } catch (IOException failure) {
                com.mojang.logging.LogUtils.getLogger().warn("Could not remove a Minecraft temporary image", failure);
            }
        }
    }

    private static void validate(Path path) throws IOException {
        long size = Files.size(path);
        if (size == 0 || size > MAX_BYTES) throw new IOException("Images must be between 1 byte and 5 MB.");
        byte[] header;
        try (var stream = Files.newInputStream(path)) { header = stream.readNBytes(12); }
        boolean png = header.length >= 8 && header[0] == (byte)137 && header[1] == 80 && header[2] == 78 && header[3] == 71;
        boolean jpeg = header.length >= 3 && header[0] == (byte)255 && header[1] == (byte)216 && header[2] == (byte)255;
        boolean gif = header.length >= 6 && new String(header, 0, 3, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF");
        boolean webp = header.length >= 12 && new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
            && new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
        String name = path.toString();
        if (!(name.endsWith(".png") && png || (name.endsWith(".jpg") || name.endsWith(".jpeg")) && jpeg
                || name.endsWith(".gif") && gif || name.endsWith(".webp") && webp))
            throw new IOException("The file does not contain a supported image matching its extension.");
    }

    /** What the macOS clipboard holds besides text: copied files (Finder) or image data (Preview, browsers). */
    record Pasted(List<Path> files, String image) {}

    /** Finder and browsers put bare file names or a URL beside the real files or image; other text is just text. */
    static boolean mayHoldFiles(String clipboard) {
        String value = clipboard.strip();
        if (value.isEmpty()) return true;
        if (!MAC) return false;
        String[] lines = value.split("\\R");
        if (lines.length > 20) return false;
        for (String line : lines)
            if (!line.strip().matches("(?i)[a-z][a-z0-9+.-]*://\\S+|.*\\.[a-z0-9]{1,5}")) return false;
        return true;
    }

    // Runs in osascript so AWT's macOS event loop never starts inside GLFW's application.
    private static final String PASTEBOARD = """
        ObjC.import('AppKit');
        function run(argv) {
          const pb = $.NSPasteboard.generalPasteboard, items = pb.pasteboardItems, lines = [];
          for (let i = 0; i < items.count; i++) {
            const url = items.objectAtIndex(i).stringForType('public.file-url');
            if (!url.isNil()) lines.push('file\\t' + $.NSURL.URLWithString(url).path.js);
          }
          if (lines.length) return lines.join('\\n');
          let data = pb.dataForType('public.png');
          if (data.isNil()) {
            const image = $.NSImage.alloc.initWithPasteboard(pb);
            if (image.isNil()) return 'none';
            const rep = $.NSBitmapImageRep.imageRepWithData(image.TIFFRepresentation);
            if (rep.isNil()) return 'none';
            data = rep.representationUsingTypeProperties($.NSBitmapImageFileTypePNG, $());
          }
          if (!data.writeToFileAtomically(argv[0], true)) throw new Error('Could not save the clipboard image.');
          return 'image';
        }
        """;

    static Pasted pasteboard() throws Exception {
        if (!MAC) return new Pasted(List.of(), null);
        Path image = temporaryImage(".png");
        Path output = Files.createTempFile("too-many-agents-pasteboard-", ".txt");
        boolean keep = false;
        try {
            Process process = new ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", "-e", PASTEBOARD, image.toString())
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new IOException("Clipboard read timed out. Try dropping the file instead.");
            }
            if (process.exitValue() != 0) throw new IOException("Could not read the clipboard. Try dropping the file instead.");
            String result = Files.readString(output).strip();
            if (result.equals("image")) {
                validate(image);
                keep = true;
                return new Pasted(List.of(), image.toString());
            }
            var files = new ArrayList<Path>();
            for (String line : result.split("\\R")) if (line.startsWith("file\t")) files.add(Path.of(line.substring(5)));
            return new Pasted(files, null);
        } finally {
            if (!keep) Files.deleteIfExists(image);
            Files.deleteIfExists(output);
        }
    }
}
