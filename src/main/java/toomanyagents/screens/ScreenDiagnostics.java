package toomanyagents.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Development checks: screen state, and input through Minecraft's own handlers. Callers guard the world. */
public final class ScreenDiagnostics {
    private ScreenDiagnostics() {}

    public static JsonObject status() {
        var result = new JsonObject();
        result.addProperty("chromium", Chromium.phase().name());
        result.addProperty("progress", Chromium.progress());
        result.addProperty("trusted", Trust.allowedHere());
        result.addProperty("browsers", Chromium.browsers());
        result.addProperty("input", ScreenInput.state());
        result.addProperty("pid", ProcessHandle.current().pid());
        var helpers = new JsonArray();
        ProcessHandle.current().descendants().forEach(process -> helpers.add(process.pid()));
        result.add("helpers", helpers);
        var mc = Minecraft.getInstance();
        // Every screen entity the client has, drawn or not, with the browser its view holds.
        var screens = new JsonArray();
        if (mc.level != null) for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ScreenEntity placed)) continue;
            var record = placed.record();
            var screen = new JsonObject();
            screen.addProperty("id", record.id());
            screen.addProperty("name", record.name());
            screen.addProperty("url", record.url());
            screen.addProperty("openedBy", record.openedBy());
            screen.addProperty("entity", placed.getUUID().toString());
            screen.addProperty("pos", placed.getPos().toShortString());
            screen.addProperty("facing", placed.facing().getName());
            screen.addProperty("blocks", placed.width() + "x" + placed.height());
            var view = Screens.views().stream().filter(candidate -> candidate.screen == placed).findFirst().orElse(null);
            screen.addProperty("view", view != null);
            screen.addProperty("browser", view != null && view.browser != null);
            if (view != null) {
                screen.addProperty("pixels", view.pixelWidth + "x" + view.pixelHeight);
                screen.addProperty("viewUrl", view.url);
                screen.addProperty("browserUrl", view.browser == null ? "" : view.browser.getURL());
                screen.addProperty("painted", view.painted());
                screen.addProperty("live", view.live());
                screen.addProperty("loading", view.loading);
                screen.addProperty("back", view.canGoBack);
                screen.addProperty("forward", view.canGoForward);
                screen.addProperty("crashed", view.crashed);
                screen.addProperty("editable", view.editable);
                screen.addProperty("cursor", view.cursor);
                screen.addProperty("stage", ScreenBreaking.stage(placed));
                // Each bar control's local span and world-space centre, for aiming real clicks at it.
                var controls = new JsonObject();
                for (var part : ScreenView.Part.values()) {
                    double x = (view.start(part) + view.end(part)) / 2;
                    var center = view.topLeft.add(view.rightVector.scale(x)).add(0, view.barHeight / 2, 0);
                    var control = new JsonObject();
                    control.addProperty("start", view.start(part));
                    control.addProperty("end", view.end(part));
                    control.addProperty("x", center.x);
                    control.addProperty("y", center.y);
                    control.addProperty("z", center.z);
                    controls.add(part.name().toLowerCase(), control);
                }
                screen.add("controls", controls);
                var page = view.center();
                screen.addProperty("pageCenter", page.x + "," + page.y + "," + page.z);
            }
            screens.add(screen);
        }
        result.add("screens", screens);
        result.addProperty("views", Screens.views().size());
        result.add("placement", ScreenPlacement.state());
        result.add("breaking", ScreenBreaking.state());
        // The server's index, as last published from its own thread.
        var server = mc.getSingleplayerServer();
        var index = server == null ? null : ScreenIndex.snapshot(server);
        if (index != null) result.add("index", index);
        // Records carried by screen items in the player's inventory.
        var items = new JsonArray();
        if (mc.player != null) {
            var inventory = mc.player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                var stack = inventory.getItem(slot);
                if (!(stack.getItem() instanceof ScreenItem)) continue;
                var record = ScreenItem.record(stack);
                var item = new JsonObject();
                item.addProperty("slot", slot);
                item.addProperty("count", stack.getCount());
                item.addProperty("id", record.id());
                item.addProperty("name", record.name());
                item.addProperty("url", record.url());
                item.addProperty("openedBy", record.openedBy());
                items.add(item);
            }
        }
        result.add("items", items);
        return result;
    }

    public static JsonObject develop(String action, JsonObject request) {
        var mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        switch (action) {
            case "dev_command" -> {
                var server = mc.getSingleplayerServer();
                var playerId = mc.player.getUUID();
                String command = request.get("command").getAsString();
                long deadline = System.nanoTime() + 2_000_000_000L;
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(playerId);
                    if (System.nanoTime() > deadline || !server.isRunning() || player == null) return;
                    server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command);
                });
            }
            case "dev_aim" -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES,
                new Vec3(request.get("x").getAsDouble(), request.get("y").getAsDouble(), request.get("z").getAsDouble()));
            case "dev_mouse" -> {
                int button = request.has("button") ? request.get("button").getAsInt() : 0;
                String phase = request.has("phase") ? request.get("phase").getAsString() : "click";
                if (!phase.equals("release")) mc.mouseHandler.onPress(window, button, GLFW.GLFW_PRESS, 0);
                if (!phase.equals("press")) mc.mouseHandler.onPress(window, button, GLFW.GLFW_RELEASE, 0);
            }
            case "dev_scroll" -> mc.mouseHandler.onScroll(window, 0, request.get("y").getAsDouble());
            case "dev_char" -> request.get("text").getAsString().codePoints().forEach(c -> mc.keyboardHandler.charTyped(window, c, 0));
            default -> throw new IllegalArgumentException("Unknown screen check " + action);
        }
        return status();
    }
}
