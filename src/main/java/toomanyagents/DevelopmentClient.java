package toomanyagents;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import toomanyagents.ui.AgentChatScreen;
import toomanyagents.ui.InventoryAgents;

/** The game requests updates; tools/dev owns builds, plugin replacement and processes. */
public final class DevelopmentClient {
    private static final String CLIENT = System.getProperty("too_many_agents.devClient", "");
    private static final Path DIRECTORY = FMLPaths.GAMEDIR.get().resolve("too-many-agents");
    private static final Path STATUS = DIRECTORY.resolve("development.json");
    private static final Path RESUME = DIRECTORY.resolve("development-resume.json");
    private static volatile JsonObject status = new JsonObject();
    private static volatile String error = "";
    private static JsonObject resume;
    private static boolean startupHandled, resumeOpened;
    private static int shutdownTicks;
    private static long resumeReadyAt;
    private static long titleUpdatedAt;
    private static JsonObject pendingRequest;
    private static Button reloadButton;
    private static String reloadTooltip = "";

    static {
        if (enabled()) {
            resume = read(RESUME);
            if (!CLIENT.equals(JsonState.text(resume, "client"))) resume = new JsonObject();
            var reader = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("minecraft-development-status").factory());
            reader.scheduleWithFixedDelay(() -> status = read(STATUS), 0, 500, TimeUnit.MILLISECONDS);
        } else resume = new JsonObject();
    }

    public static boolean enabled() { return !CLIENT.isBlank(); }

    static void pauseMenu(ScreenEvent.Init.Post event) {
        if (!enabled() || !(event.getScreen() instanceof PauseScreen pause) || !pause.showsPauseMenu()) return;
        Button quit = null;
        for (var listener : event.getListenersList())
            if (listener instanceof Button button && button.getMessage().equals(Component.translatable("menu.returnToMenu"))) quit = button;
        if (quit == null) return;
        int row = quit.getHeight() + 4;
        // Keep the expanded menu at vanilla's quarter-height alignment.
        for (var listener : event.getListenersList())
            if (listener instanceof Button button) button.setY(button.getY() - row / 4);
        reloadButton = Button.builder(Component.literal("Reload changes"), button -> {
            try { requestReload(); }
            catch (IllegalStateException failure) { error = failure.getMessage(); }
            updateReloadButton();
        }).bounds(quit.getX(), quit.getY(), quit.getWidth(), quit.getHeight()).build();
        quit.setY(quit.getY() + row);
        event.addListener(reloadButton);
        reloadTooltip = "";
        updateReloadButton();
    }

    private static void updateReloadButton() {
        reloadButton.active = canReload();
        reloadButton.setMessage(Component.literal(busy() ? "Reloading..." : "Reload changes"));
        String tooltip = "Build this checkout, then reload its plugin or save and restart this client as needed.\n\n" + message();
        if (!tooltip.equals(reloadTooltip)) {
            reloadButton.setTooltip(Tooltip.create(Component.literal(tooltip)));
            reloadTooltip = tooltip;
        }
    }

    private static JsonObject read(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 2 * 1024 * 1024) return new JsonObject();
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception failure) { return new JsonObject(); }
    }

    private static boolean live(JsonObject current) {
        try {
            return CLIENT.equals(JsonState.text(current, "client"))
                && System.currentTimeMillis() - current.get("updatedAt").getAsLong() < 15_000
                && ProcessHandle.of(current.get("supervisorPid").getAsLong()).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception missing) { return false; }
    }

    public static boolean busy() {
        if (pendingRequest != null) {
            var current = JsonState.obj(status, "request");
            if (!JsonState.text(pendingRequest, "id").equals(JsonState.text(current, "id"))
                && System.currentTimeMillis() - pendingRequest.get("requestedAt").getAsLong() < 15_000) return true;
            pendingRequest = null;
        }
        String state = JsonState.text(JsonState.obj(status, "request"), "state");
        return java.util.Set.of("queued", "building", "applying").contains(state);
    }

    public static boolean canReload() { return enabled() && live(status) && !busy(); }

    public static String message() {
        if (!error.isBlank()) return error;
        if (!live(status)) return "Development launcher is unavailable. Run tools/dev start " + CLIENT + ".";
        return JsonState.text(status, "message");
    }

    public static JsonObject snapshot() {
        var result = status.deepCopy();
        result.addProperty("enabled", enabled());
        result.addProperty("launcherAvailable", live(status));
        result.addProperty("message", message());
        result.addProperty("pid", ProcessHandle.current().pid());
        var mc = Minecraft.getInstance();
        result.addProperty("ready", enabled() && live(status) && BbSetup.get().view().ready()
            && (!DevelopmentWorld.ENABLED || mc.hasSingleplayerServer() && mc.level != null));
        return result;
    }

    public static JsonObject requestReload() {
        if (!enabled()) throw new IllegalStateException("This game is not managed by tools/dev");
        status = read(STATUS);
        if (!live(status)) throw new IllegalStateException(message());
        if (busy()) return pendingRequest != null ? pendingRequest.deepCopy() : JsonState.obj(status, "request").deepCopy();
        var request = JsonState.object("protocol", 1, "id", UUID.randomUUID().toString(),
            "requestedAt", System.currentTimeMillis());
        try {
            JsonState.write(DIRECTORY.resolve("reload.request"), request);
            pendingRequest = request;
            error = "";
            return request;
        } catch (Exception failure) {
            error = "Could not request reload: " + failure.getMessage();
            throw new IllegalStateException(error, failure);
        }
    }

    private static void requireRequest(JsonObject request) {
        if (!enabled()) throw new IllegalStateException("This game is not managed by tools/dev");
        status = read(STATUS);
        if (!live(status)) throw new IllegalStateException("Development launcher is unavailable");
        String id = JsonState.text(request, "request");
        if (id.isBlank() || !id.equals(JsonState.text(JsonState.obj(status, "request"), "id")))
            throw new IllegalArgumentException("Development request no longer belongs to this client");
        if (!status.has("gamePid") || status.get("gamePid").getAsLong() != ProcessHandle.current().pid())
            throw new IllegalStateException("Development request targets a different game process");
    }

    /** Called on the client thread, before disconnect destroys UI state. */
    public static JsonObject shutdown(JsonObject request, InventoryAgents panels) {
        requireRequest(request);
        var mc = Minecraft.getInstance();
        if (mc.level != null && !mc.hasSingleplayerServer())
            throw new IllegalStateException("Development reload supports singleplayer worlds only");
        var server = mc.getSingleplayerServer();
        if (server != null) {
            String name = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
            var saved = JsonState.object("client", CLIENT, "request", JsonState.text(request, "request"), "world", name);
            saved.add("panels", panels.developmentState());
            if (mc.screen instanceof AgentChatScreen chat) saved.add("chat", chat.developmentState());
            try { JsonState.write(RESUME, saved); }
            catch (Exception failure) { throw new IllegalStateException("Could not preserve restart state", failure); }
            mc.level.disconnect();
            mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
        }
        // Let the HTTP acknowledgement leave before the JVM closes its bridge.
        shutdownTicks = 10;
        return JsonState.object("ok", true);
    }

    /** Prevent the default test-world opener from racing a saved restart. */
    static boolean hasStartupWorld() { return enabled() && !JsonState.text(resume, "world").isBlank(); }

    static void tick(AgentService agents, InventoryAgents panels) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance();
        if (reloadButton != null) {
            if (mc.screen instanceof PauseScreen && mc.screen.children().contains(reloadButton)) updateReloadButton();
            else reloadButton = null;
        }
        long now = System.currentTimeMillis();
        if (now - titleUpdatedAt > 1000) {
            String checkout = JsonState.text(status, "checkout");
            mc.getWindow().setTitle("Minecraft - " + CLIENT
                + (checkout.isBlank() ? "" : " - " + Path.of(checkout).getFileName()));
            titleUpdatedAt = now;
        }
        if (shutdownTicks > 0 && --shutdownTicks == 0) { mc.stop(); return; }
        if (!startupHandled && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            startupHandled = true;
            String name = JsonState.text(resume, "world");
            if (!name.isBlank()) {
                var saves = mc.gameDirectory.toPath().resolve("saves").toAbsolutePath().normalize();
                var world = saves.resolve(name).normalize();
                if (world.getParent().equals(saves) && Files.isRegularFile(world.resolve("level.dat"))) {
                    resumeOpened = true;
                    mc.createWorldOpenFlows().openWorld(name, () -> {
                        resumeOpened = false;
                        mc.setScreen(new TitleScreen());
                    });
                } else error = "The saved development world is missing. Choose a world to continue.";
            }
        }
        if (!resumeOpened || mc.level == null || agents == null || !BbSetup.get().view().ready()) return;
        if (resumeReadyAt == 0) resumeReadyAt = System.currentTimeMillis();
        // Wait for Java to load body bindings and BB to publish its first world sync.
        var worldInfo = JsonState.obj(agents.projects(), "world");
        if (worldInfo.isEmpty()) return;
        var expected = mc.gameDirectory.toPath().resolve("saves").resolve(JsonState.text(resume, "world")).toAbsolutePath().normalize();
        if (!expected.toString().equals(JsonState.text(worldInfo, "directory"))) return;
        var panelState = JsonState.obj(resume, "panels");
        String selected = JsonState.text(panelState, "selected");
        if (!selected.isBlank() && JsonState.text(agents.snapshot(selected), "status").equals("unavailable")
            && System.currentTimeMillis() - resumeReadyAt < 30_000) return;
        try {
            if (!panelState.isEmpty()) panels.restoreDevelopmentState(panelState);
            var chatState = JsonState.obj(resume, "chat");
            String agent = JsonState.text(chatState, "agentId");
            if (!agent.isBlank()) {
                var chat = new AgentChatScreen(agents, agent);
                chat.restoreDevelopmentState(chatState);
                mc.setScreen(chat);
            }
            Files.deleteIfExists(RESUME);
        } catch (Exception failure) {
            error = "World reopened; could not restore the previous view: " + failure.getMessage();
            LogUtils.getLogger().warn(error, failure);
        }
        resumeOpened = false;
    }

    private DevelopmentClient() {}
}
