package toomanyagents.screens;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

/** Checks small image headers only. No raster is decoded, and unsafe SVG stays source text. */
final class FileImages {
    record Image(String mime, int width, int height, boolean safe, long decodedPixels) {
        Image(String mime, int width, int height, boolean safe) {
            this(mime, width, height, safe, safe ? (long) width * height : 0);
        }
    }
    private static final long MAX_PIXELS = 16_000_000;
    private FileImages() {}

    static Image inspect(String path, byte[] bytes) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = switch (extension) {
            case "png" -> "image/png"; case "jpg", "jpeg" -> "image/jpeg"; case "gif" -> "image/gif";
            case "webp" -> "image/webp"; case "svg" -> "image/svg+xml"; case "avif" -> "image/avif";
            default -> null;
        };
        if (mime == null) return null;
        try {
            if (extension.equals("svg")) return svg(bytes);
            if (extension.equals("webp")) return webp(bytes);
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return new Image(mime, 0, 0, false);
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("png", "jpeg", "jpg", "gif").contains(format)) return new Image(mime, 0, 0, false);
                    mime = "image/" + (format.equals("jpg") ? "jpeg" : format);
                    reader.setInput(input, false, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    boolean safe = dimensions(width, height);
                    long frames = 1;
                    if (format.equals("gif")) {
                        frames = reader.getNumImages(true);
                        if (bytes.length < 10) return new Image(mime, 0, 0, false);
                        width = u16(bytes, 6); height = u16(bytes, 8);
                        safe &= dimensions(width, height) && frames > 0 && frames <= 256;
                        for (int i = 0; safe && i < frames; i++) {
                            int w = reader.getWidth(i), h = reader.getHeight(i);
                            safe = dimensions(w, h) && w <= width && h <= height;
                        }
                    }
                    if (format.equals("png")) frames = pngFrames(bytes, width, height);
                    long pixels = dimensions(width, height) && frames > 0 && frames <= 256
                        ? (long) width * height * frames : 0;
                    safe &= pixels > 0 && pixels <= MAX_PIXELS;
                    return new Image(mime, width, height, safe, pixels);
                } finally { reader.dispose(); }
            }
        } catch (Exception invalid) { return new Image(mime, 0, 0, false); }
    }

    private static boolean dimensions(int width, int height) {
        return width > 0 && height > 0 && width <= 8192 && height <= 8192 && (long) width * height <= MAX_PIXELS;
    }

    /** Returns the declared frame count only when animation headers agree with the bounded canvas. */
    private static long pngFrames(byte[] bytes, int width, int height) {
        var data = ByteBuffer.wrap(bytes);
        long frames = 1, controls = 0;
        boolean animation = false, imageData = false;
        for (int offset = 8; offset + 12 <= bytes.length;) {
            long length = Integer.toUnsignedLong(data.getInt(offset));
            if (length > bytes.length - offset - 12L) return 0;
            int type = data.getInt(offset + 4);
            if (type == 0x6163544c) { // acTL
                if (length != 8 || animation || imageData) return 0;
                animation = true;
                frames = Integer.toUnsignedLong(data.getInt(offset + 8));
                if (frames < 1 || frames > 256) return 0;
            }
            if (type == 0x6663544c) { // fcTL
                if (length != 26 || !animation || ++controls > frames) return 0;
                long frameWidth = Integer.toUnsignedLong(data.getInt(offset + 12));
                long frameHeight = Integer.toUnsignedLong(data.getInt(offset + 16));
                long x = Integer.toUnsignedLong(data.getInt(offset + 20));
                long y = Integer.toUnsignedLong(data.getInt(offset + 24));
                if (frameWidth < 1 || frameHeight < 1 || x + frameWidth > width || y + frameHeight > height) return 0;
            }
            if (type == 0x49444154) imageData = true; // IDAT
            offset += (int) length + 12;
            if (type == 0x49454e44) // IEND
                return length == 0 && offset == bytes.length && imageData && (!animation || controls == frames) ? frames : 0;
        }
        return 0;
    }

    /** WebP's three dimension headers are fixed-size; animated WebP needs more than this header check. */
    private static Image webp(byte[] bytes) {
        if (bytes.length < 30 || !ascii(bytes, 0, "RIFF") || !ascii(bytes, 8, "WEBP")) return new Image("image/webp", 0, 0, false);
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (Integer.toUnsignedLong(data.getInt(4)) + 8 != bytes.length) return new Image("image/webp", 0, 0, false);
        int width = 0, height = 0;
        boolean animated = false, found = false;
        for (int offset = 12; offset + 8 <= bytes.length;) {
            long length = Integer.toUnsignedLong(data.getInt(offset + 4));
            if (length > bytes.length - offset - 8L) return new Image("image/webp", 0, 0, false);
            int start = offset + 8;
            if (ascii(bytes, offset, "VP8X") && length >= 10) {
                animated |= (bytes[start] & 2) != 0;
                width = u24(bytes, start + 4) + 1; height = u24(bytes, start + 7) + 1;
            } else if (ascii(bytes, offset, "VP8L") && length >= 5 && bytes[start] == 0x2f) {
                int packed = data.getInt(start + 1);
                int w = (packed & 0x3fff) + 1, h = ((packed >>> 14) & 0x3fff) + 1;
                if (width == 0) { width = w; height = h; }
                if (!dimensions(w, h)) return new Image("image/webp", width, height, false);
                found = true;
            } else if (ascii(bytes, offset, "VP8 ") && length >= 10 && (bytes[start + 3] & 255) == 0x9d
                && bytes[start + 4] == 1 && bytes[start + 5] == 0x2a) {
                int w = u16(bytes, start + 6) & 0x3fff, h = u16(bytes, start + 8) & 0x3fff;
                if (width == 0) { width = w; height = h; }
                if (!dimensions(w, h)) return new Image("image/webp", width, height, false);
                found = true;
            } else if (ascii(bytes, offset, "ANIM") || ascii(bytes, offset, "ANMF")) animated = true;
            offset += 8 + (int) length + ((int) length & 1);
        }
        return new Image("image/webp", width, height, found && !animated && dimensions(width, height));
    }

    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8); }
    private static int u24(byte[] bytes, int at) { return u16(bytes, at) | ((bytes[at + 2] & 255) << 16); }
    private static boolean ascii(byte[] bytes, int at, String value) {
        if (at + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) if (bytes[at + i] != value.charAt(i)) return false;
        return true;
    }

    private static Image svg(byte[] bytes) throws Exception {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new javax.xml.stream.XMLStreamException("External SVG resource"); });
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        int width = 0, height = 0, nodes = 0;
        boolean root = false;
        var allowed = Set.of("svg", "g", "defs", "title", "desc", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
            "text", "tspan", "textPath", "use", "symbol", "clipPath", "mask", "linearGradient", "radialGradient", "stop", "pattern", "marker");
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE || event == XMLStreamConstants.PROCESSING_INSTRUCTION)
                    return new Image("image/svg+xml", width, height, false);
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                if (++nodes > 10_000 || !allowed.contains(reader.getLocalName())
                    || !"http://www.w3.org/2000/svg".equals(reader.getNamespaceURI())) return new Image("image/svg+xml", width, height, false);
                if (!root) {
                    if (!reader.getLocalName().equals("svg")) return new Image("image/svg+xml", 0, 0, false);
                    root = true;
                    String rawWidth = reader.getAttributeValue(null, "width"), rawHeight = reader.getAttributeValue(null, "height");
                    width = dimension(rawWidth); height = dimension(rawHeight);
                    if ((rawWidth != null && width == 0) || (rawHeight != null && height == 0)) return new Image("image/svg+xml", width, height, false);
                    if (width == 0 || height == 0) {
                        String viewBox = reader.getAttributeValue(null, "viewBox");
                        if (viewBox != null) {
                            String[] values = viewBox.trim().split("[ ,]+");
                            if (values.length == 4) { width = dimension(values[2]); height = dimension(values[3]); }
                        }
                    }
                }
                for (int i = 0; i < reader.getAttributeCount(); i++) {
                    String name = reader.getAttributeLocalName(i).toLowerCase(Locale.ROOT);
                    String value = reader.getAttributeValue(i).trim().toLowerCase(Locale.ROOT);
                    if (name.startsWith("on") || name.equals("style") || name.equals("base") || value.contains("\\") || value.contains("@")
                        || ((name.equals("href") || name.equals("src")) && !value.matches("#[a-z0-9_.:-]+")))
                        return new Image("image/svg+xml", width, height, false);
                    if (value.contains("url") && !value.matches("url\\(\\s*['\"]?#[a-z0-9_.:-]+['\"]?\\s*\\)"))
                        return new Image("image/svg+xml", width, height, false);
                }
            }
            return new Image("image/svg+xml", width, height, root && dimensions(width, height));
        } finally { reader.close(); }
    }

    private static int dimension(String value) {
        if (value == null || !value.matches("[0-9]+(?:\\.[0-9]+)?(?:px)?")) return 0;
        double number = Double.parseDouble(value.replace("px", ""));
        return number > 0 && number <= 8192 ? (int) Math.ceil(number) : 0;
    }
}
