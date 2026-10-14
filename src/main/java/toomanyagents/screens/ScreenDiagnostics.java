package toomanyagents.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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
        result.addProperty("windowActive", mc.isWindowActive());
        result.addProperty("mouseGrabbed", mc.mouseHandler.isMouseGrabbed());
        if (mc.player != null) {
            var player = new JsonObject();
            player.addProperty("x", mc.player.getX());
            player.addProperty("y", mc.player.getY());
            player.addProperty("z", mc.player.getZ());
            player.addProperty("yaw", mc.player.getYRot());
            player.addProperty("pitch", mc.player.getXRot());
            result.add("player", player);
        }
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
            screen.addProperty("up", placed.up().getName());
            screen.addProperty("blocks", placed.width() + "x" + placed.height());
            screen.addProperty("holder", placed.holder());
            var view = Screens.views().stream().filter(candidate -> candidate.screen == placed).findFirst().orElse(null);
            screen.addProperty("view", view != null);
            screen.addProperty("browser", view != null && view.browser != null);
            if (view != null) {
                screen.addProperty("pixels", view.pixelWidth + "x" + view.pixelHeight);
                screen.addProperty("pageWidth", view.pageWidth);
                screen.addProperty("pageHeight", view.pageHeight);
                screen.addProperty("barHeight", view.barHeight);
                screen.addProperty("viewUrl", view.url);
                screen.addProperty("browserUrl", view.browser == null ? "" : view.browser.getURL());
                screen.addProperty("painted", view.painted());
                screen.addProperty("live", view.live());
                screen.addProperty("loading", view.loading);
                screen.addProperty("title", view.title == null ? "" : view.title);
                screen.addProperty("pendingCalls", view.pendingCalls());
                screen.addProperty("back", view.canGoBack);
                screen.addProperty("forward", view.canGoForward);
                screen.addProperty("crashed", view.crashed);
                screen.addProperty("editable", view.editable);
                screen.addProperty("cursor", view.cursor);
                // Each bar control's local span and world-space centre, for aiming real clicks at it.
                var controls = new JsonObject();
                for (var part : ScreenView.Part.values()) {
                    var center = view.barCenter(part);
                    var control = new JsonObject();
                    control.addProperty("start", view.start(part));
                    control.addProperty("end", view.end(part));
                    control.addProperty("x", center.x);
                    control.addProperty("y", center.y);
                    control.addProperty("z", center.z);
                    controls.add(part.name().toLowerCase(), control);
                }
                screen.add("controls", controls);
                // The menu's rows, where they are whether or not it's open.
                var rows = new JsonArray();
                for (var action : ScreenView.Action.values()) {
                    var center = view.menuCenter(action);
                    var row = new JsonObject();
                    row.addProperty("action", switch (action) { case OFF -> "turn_off"; case PICKUP -> "pick_up"; case RESET -> "reset"; });
                    row.addProperty("label", action.label);
                    row.addProperty("x", center.x);
                    row.addProperty("y", center.y);
                    row.addProperty("z", center.z);
                    rows.add(row);
                }
                screen.add("menuRows", rows);
                var page = view.center();
                screen.addProperty("pageCenter", page.x + "," + page.y + "," + page.z);
            }
            screens.add(screen);
        }
        result.add("screens", screens);
        result.addProperty("views", Screens.views().size());
        var browsers = new JsonArray();
        for (var view : Screens.views()) {
            var browser = new JsonObject();
            browser.addProperty("screen", view.entry.screen().id());
            browser.addProperty("tracked", view.screen != null && !view.screen.isRemoved());
            browser.addProperty("browser", view.browser != null);
            browser.addProperty("generation", view.generation);
            browser.addProperty("live", view.live());
            browser.addProperty("pendingCalls", view.pendingCalls());
            browsers.add(browser);
        }
        result.add("browserViews", browsers);
        result.add("placement", ScreenPlacement.state());
        var menu = new JsonObject();
        var open = Screens.views().stream().filter(ScreenInput::menuOpen).findFirst().orElse(null);
        menu.addProperty("screen", open == null ? "" : open.screen.record().id());
        menu.addProperty("entity", open == null ? "" : open.screen.getUUID().toString());
        var hovered = open == null ? null : ScreenInput.menuHovered(open);
        menu.addProperty("hovered", hovered == null ? -1 : hovered.ordinal());
        menu.addProperty("confirmingReset", ScreenInput.confirmingReset());
        result.add("menu", menu);
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
                var item = describe(stack);
                item.addProperty("slot", slot);
                items.add(item);
            }
        }
        result.add("items", items);
        // Screen items lying in the world, e.g. one just popped off a wall. age is client ticks since it appeared.
        var dropped = new JsonArray();
        if (mc.level != null) for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity lying) || !(lying.getItem().getItem() instanceof ScreenItem)) continue;
            var item = describe(lying.getItem());
            item.addProperty("entity", lying.getUUID().toString());
            item.addProperty("x", lying.getX());
            item.addProperty("y", lying.getY());
            item.addProperty("z", lying.getZ());
            item.addProperty("age", lying.getAge());
            dropped.add(item);
        }
        result.add("droppedItems", dropped);
        return result;
    }

    /** A screen item: what it saves, exactly as stored, and its tooltip as the player sees it. */
    private static JsonObject describe(ItemStack stack) {
        var mc = Minecraft.getInstance();
        var item = new JsonObject();
        item.addProperty("count", stack.getCount());
        item.add("saved", stack.has(Screens.RECORD.get())
            ? Screen.CODEC.encodeStart(JsonOps.INSTANCE, stack.get(Screens.RECORD.get())).result().orElse(JsonNull.INSTANCE) : JsonNull.INSTANCE);
        var record = ScreenItem.record(stack);
        item.addProperty("name", record.name());
        item.addProperty("url", record.url());
        var tooltip = new JsonArray();
        for (var line : stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.Default.NORMAL)) tooltip.add(line.getString());
        item.add("tooltip", tooltip);
        return item;
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
            case "dev_mouse_move" -> {
                double dx = request.get("x").getAsDouble(), dy = request.get("y").getAsDouble();
                if (!Double.isFinite(dx) || !Double.isFinite(dy) || Math.abs(dx) > 10000 || Math.abs(dy) > 10000)
                    throw new IllegalArgumentException("Mouse deltas must be finite and at most 10000");
                mc.mouseHandler.onMove(window, mc.mouseHandler.xpos(), mc.mouseHandler.ypos());
                mc.mouseHandler.onMove(window, mc.mouseHandler.xpos() + dx, mc.mouseHandler.ypos() + dy);
                mc.mouseHandler.handleAccumulatedMovement();
            }
            case "dev_scroll" -> mc.mouseHandler.onScroll(window, 0, request.get("y").getAsDouble());
            case "dev_char" -> request.get("text").getAsString().codePoints().forEach(c -> mc.keyboardHandler.charTyped(window, c, 0));
            default -> throw new IllegalArgumentException("Unknown screen check " + action);
        }
        return status();
    }
}
