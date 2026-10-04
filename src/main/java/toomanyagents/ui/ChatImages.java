package toomanyagents.ui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** Screen-owned image cache. Decode off-thread; upload and release on Minecraft's render thread. */
final class ChatImages implements AutoCloseable {
    private static final java.util.concurrent.ExecutorService DECODER = Executors.newFixedThreadPool(2, runnable -> {
        var thread = new Thread(runnable, "Chat image decoder"); thread.setDaemon(true); return thread;
    });
    private static int pending, liveTextures;
    private static long liveBytes;
    static final class Image {
        ResourceLocation texture;
        String error = "";
        int width, height;
        boolean loading = true;
    }
    private record Pixels(int width, int height, int[] rgba) {}
    private final LinkedHashMap<String, Image> images = new LinkedHashMap<>(16, 0.75f, true);
    private final BiFunction<String, String, CompletableFuture<JsonObject>> load;
    private int generation;
    private long textureBytes;

    ChatImages(BiFunction<String, String, CompletableFuture<JsonObject>> load) { this.load = load; }

    Image get(String kind, String source) {
        String key = kind + "\n" + source;
        Image image = images.get(key);
        if (image != null) return image;
        image = new Image();
        // Visible assets start as slots free up; off-screen messages never initiate downloads.
        if (pending >= 2) return image;
        images.put(key, image);
        pending++;
        int requestedGeneration = generation;
        Image target = image;
        CompletableFuture<JsonObject> request;
        try { request = load.apply(kind, source); }
        catch (Exception failure) { request = CompletableFuture.failedFuture(failure); }
        request.thenApplyAsync(ChatImages::decode, DECODER).whenComplete((pixels, failure) -> Minecraft.getInstance().execute(() -> {
            pending--;
            if (generation != requestedGeneration) return;
            target.loading = false;
            if (failure != null) { target.error = AgentModels.error(failure); trim(target); return; }
            try {
                var nativeImage = new NativeImage(pixels.width(), pixels.height(), false);
                try {
                    for (int y = 0; y < pixels.height(); y++) for (int x = 0; x < pixels.width(); x++)
                        nativeImage.setPixelRGBA(x, y, pixels.rgba()[y * pixels.width() + x]);
                    var texture = new DynamicTexture(nativeImage);
                    target.texture = Minecraft.getInstance().getTextureManager().register("bb_chat", texture);
                    target.width = pixels.width(); target.height = pixels.height();
                    textureBytes += (long)target.width * target.height * 4;
                    liveTextures++;liveBytes+=(long)target.width*target.height*4;
                } catch (Throwable error) { nativeImage.close(); throw error; }
                trim(target);
            } catch (Exception error) { target.error = "Could not upload image: " + AgentModels.error(error); }
        }));
        return image;
    }

    void retry(String kind, String source) {
        var image = images.remove(kind + "\n" + source);
        if (image != null) release(image);
    }

    private void trim(Image newest) {
        var iterator = images.entrySet().iterator();
        while ((images.size() > 24 || textureBytes > 64L * 1024 * 1024) && iterator.hasNext()) {
            var image = iterator.next().getValue();
            if (image == newest || image.loading) continue;
            iterator.remove(); release(image);
        }
    }

    private void release(Image image) {
        if (image.texture != null) {
            Minecraft.getInstance().getTextureManager().release(image.texture);
            textureBytes -= (long)image.width * image.height * 4;
            liveTextures--;liveBytes-=(long)image.width*image.height*4;
            image.texture = null;
        }
    }

    long textureBytes() { return textureBytes; }
    int size() { return images.size(); }
    static JsonObject resources() {
        var result=new JsonObject();result.addProperty("textures",liveTextures);result.addProperty("bytes",liveBytes);result.addProperty("pending",pending);return result;
    }

    @Override public void close() {
        generation++;
        images.values().forEach(this::release); images.clear(); textureBytes = 0;
    }

    static void draw(GuiGraphics graphics, Image image, int x, int y, int width, int height) {
        double scale = Math.min((double)width / image.width, (double)height / image.height);
        int w = Math.max(1, (int)(image.width * scale)), h = Math.max(1, (int)(image.height * scale));
        graphics.blit(image.texture, x + (width-w)/2, y + (height-h)/2, w, h, 0, 0,
            image.width, image.height, image.width, image.height);
    }

    private static Pixels decode(JsonObject result) {
        try {
            String encoded = AgentModels.text(result, "base64");
            if (encoded.length() > 7 * 1024 * 1024) throw new IllegalArgumentException("Image exceeds 5 MB.");
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > 5 * 1024 * 1024) throw new IllegalArgumentException("Image exceeds 5 MB or is empty.");
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                boolean webp = bytes.length > 12 && bytes[0]=='R' && bytes[1]=='I' && bytes[8]=='W' && bytes[9]=='E';
                javax.imageio.ImageReader reader;
                if (webp) reader = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi().createReaderInstance();
                else {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) throw new IllegalArgumentException("Invalid or unsupported image.");
                    reader = readers.next();
                }
                try {
                    if(!java.util.Set.of("png","jpeg","jpg","gif","webp").contains(reader.getFormatName().toLowerCase(java.util.Locale.ROOT)))
                        throw new IllegalArgumentException("Use PNG, JPEG, GIF, or WebP images.");
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long)width * height > 4_000_000)
                        throw new IllegalArgumentException("Image exceeds 4096 pixels per side or 4 megapixels.");
                    var buffered = reader.read(0); // Animated images display their first frame.
                    int[] pixels = buffered.getRGB(0,0,width,height,null,0,width);
                    for (int i = 0; i < pixels.length; i++) {
                        int color = pixels[i];
                        pixels[i] = color & 0xFF00FF00 | (color >>> 16 & 255) | (color & 255) << 16;
                    }
                    buffered.flush();
                    return new Pixels(width,height,pixels);
                } finally { reader.dispose(); }
            }
        } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
    }
}
