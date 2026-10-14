package toomanyagents.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Worlds whose screens may load pages. A world you built a screen in, or allowed once, is trusted; a downloaded
 * world's screens wait for a click. Kept outside the save so a shared world can't carry its own consent.
 */
final class Trust {
    private static final Path FILE = System.getProperty("too_many_agents.devClient", "").isBlank()
        ? Path.of(System.getProperty("user.home"), ".too-many-agents", "screens.json")
        : net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("too-many-agents/screens.json");
    private static Set<String> worlds;
    private static MinecraftServer checkedServer;
    private static boolean checkedAllowed;

    static synchronized boolean allowed(MinecraftServer server) {
        return server != null && load().contains(key(server));
    }

    static synchronized void allow(MinecraftServer server) {
        if (server == null || !load().add(key(server))) return;
        checkedServer = null;
        var list = new JsonArray();
        worlds.forEach(list::add);
        var data = new JsonObject();
        data.add("trustedWorlds", list);
        try {
            Files.createDirectories(FILE.getParent());
            var temp = FILE.resolveSibling("screens.json.tmp");
            Files.writeString(temp, data.toString());
            Files.move(temp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LogUtils.getLogger().warn("Could not save trusted screen worlds", e);
        }
    }

    /** Checked every frame, so the answer is kept until the world or the list changes. */
    static synchronized boolean allowedHere() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != checkedServer) {
            checkedServer = server;
            checkedAllowed = allowed(server);
        }
        return checkedAllowed;
    }

    private static String key(MinecraftServer server) {
        var folder = server.getWorldPath(LevelResource.ROOT);
        try { return folder.toRealPath().toString(); } catch (IOException e) { return folder.toAbsolutePath().normalize().toString(); }
    }

    private static Set<String> load() {
        if (worlds != null) return worlds;
        worlds = new HashSet<>();
        try {
            if (Files.exists(FILE))
                for (var world : JsonParser.parseString(Files.readString(FILE)).getAsJsonObject().getAsJsonArray("trustedWorlds")) worlds.add(world.getAsString());
        } catch (Exception e) {
            LogUtils.getLogger().warn("Could not read trusted screen worlds", e);
        }
        return worlds;
    }
}
