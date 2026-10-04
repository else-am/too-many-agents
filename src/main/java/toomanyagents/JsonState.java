package toomanyagents;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Small JSON helpers for Minecraft-owned records. */
final class JsonState {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    static String text(JsonObject row,String key) { var value=row.get(key); return value!=null && value.isJsonPrimitive()?value.getAsString():""; }
    static JsonObject obj(JsonObject row,String key) { return row.get(key) instanceof JsonObject value?value:new JsonObject(); }
    static JsonArray array(JsonObject row,String key) { return row.get(key) instanceof JsonArray value?value:new JsonArray(); }
    static boolean flag(JsonObject row,String key) { return row.has(key) && !row.get(key).isJsonNull() && row.get(key).getAsBoolean(); }
    static JsonObject object(Object... pairs) {
        var result=new JsonObject();
        for(int i=0;i<pairs.length;i+=2) result.add((String)pairs[i],JSON.toJsonTree(pairs[i+1]));
        return result;
    }
    static void write(Path file,JsonObject data) throws IOException {
        Files.createDirectories(file.getParent());
        var temp=Files.createTempFile(file.getParent(),"state-",".tmp");
        try {
            try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)) {
                var bytes=java.nio.ByteBuffer.wrap(JSON.toJson(data).getBytes(StandardCharsets.UTF_8));
                while(bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private JsonState() {}
}
