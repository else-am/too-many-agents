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
        result.addProperty("input", ScreenInput.state());
        result.addProperty("pid", ProcessHandle.current().pid());
        var helpers = new JsonArray();
        ProcessHandle.current().descendants().forEach(process -> helpers.add(process.pid()));
        result.add("helpers", helpers);
        var screens = new JsonArray();
        for (var view : Screens.views()) {
            var screen = new JsonObject();
            screen.addProperty("anchor", view.anchor.toShortString());
            screen.addProperty("facing", view.facing.getName());
            screen.addProperty("blocks", view.blocksWide + "x" + view.blocksHigh);
            screen.addProperty("pixels", view.pixelWidth + "x" + view.pixelHeight);
            screen.addProperty("url", view.url);
            screen.addProperty("browserUrl", view.browser == null ? "" : view.browser.getURL());
            screen.addProperty("painted", view.painted());
            screen.addProperty("live", view.live());
            screen.addProperty("loading", view.loading);
            screen.addProperty("back", view.canGoBack);
            screen.addProperty("forward", view.canGoForward);
            screen.addProperty("crashed", view.crashed);
            screen.addProperty("editable", view.editable);
            screen.addProperty("cursor", view.cursor);
            screens.add(screen);
        }
        result.add("screens", screens);
        return result;
    }

    public static JsonObject develop(String action, JsonObject request) {
        var mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        switch (action) {
            case "dev_command" -> {
                var server = mc.getSingleplayerServer();
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                String command = request.get("command").getAsString();
                server.execute(() -> server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), command));
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
