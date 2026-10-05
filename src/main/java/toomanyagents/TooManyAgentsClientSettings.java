package toomanyagents;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.neoforged.fml.loading.FMLPaths;

/** Display preferences belong to this installation, separately from worlds and BB roles. */
public final class TooManyAgentsClientSettings {
    private static TooManyAgentsClientSettings instance;
    private final Path path = FMLPaths.CONFIGDIR.get().resolve("too-many-agents-client.json");
    private boolean notificationSound;
    private volatile boolean showAgentCommunication = true;
    // Agent screen GUI scale; 0 follows the game.
    private int screenScale;
    private boolean sidebarCollapsed;
    private String error = "";

    public static TooManyAgentsClientSettings get() {
        if (instance == null) instance = new TooManyAgentsClientSettings();
        return instance;
    }

    private TooManyAgentsClientSettings() {
        if (!Files.exists(path)) return;
        try (var reader = Files.newBufferedReader(path)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("notificationSound")) notificationSound = json.get("notificationSound").getAsBoolean();
            if (json.has("showAgentCommunication")) showAgentCommunication = json.get("showAgentCommunication").getAsBoolean();
            if (json.has("screenScale")) screenScale = Math.max(0, json.get("screenScale").getAsInt());
            if (json.has("sidebarCollapsed")) sidebarCollapsed = json.get("sidebarCollapsed").getAsBoolean();
        } catch (Exception failure) {
            error = "Could not read display settings. Changes will replace the unreadable file.";
        }
    }

    public boolean notificationSound() { return notificationSound; }
    public boolean showAgentCommunication() { return showAgentCommunication; }
    public void setShowAgentCommunication(boolean enabled) { showAgentCommunication = enabled; save(); }
    public int screenScale() { return screenScale; }
    public void setScreenScale(int scale) { screenScale = scale; save(); }
    public boolean sidebarCollapsed() { return sidebarCollapsed; }
    public void setSidebarCollapsed(boolean collapsed) { sidebarCollapsed = collapsed; save(); }
    public String error() { return error; }
    public void setNotificationSound(boolean enabled) { notificationSound = enabled; save(); }

    public JsonObject snapshot() {
        var result = new JsonObject();
        result.addProperty("notificationSound", notificationSound);
        result.addProperty("showAgentCommunication", showAgentCommunication);
        result.addProperty("screenScale", screenScale);
        result.addProperty("sidebarCollapsed", sidebarCollapsed);
        result.addProperty("error", error);
        return result;
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            var json = new JsonObject();
            json.addProperty("notificationSound", notificationSound);
            json.addProperty("showAgentCommunication", showAgentCommunication);
            json.addProperty("screenScale", screenScale);
            json.addProperty("sidebarCollapsed", sidebarCollapsed);
            var temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n");
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            error = "";
        } catch (IOException failure) {
            error = "Changed for now, but could not save settings: " + failure.getMessage();
        }
    }
}
