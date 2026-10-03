package toomanyagents;

import toomanyagents.ui.AgentChatScreen;
import toomanyagents.ui.AgentSettingsScreen;
import toomanyagents.ui.AgentApprovalScreen;
import toomanyagents.ui.AgentQuestionScreen;
import toomanyagents.ui.AgentQueueScreen;
import toomanyagents.ui.AgentInventoryScreen;
import toomanyagents.ui.TooManyAgentsSettingsScreen;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

/** Native client entry points. All screen and framebuffer access runs on the client thread. */
public final class ClientControls {
    private final Supplier<AgentService> service;
    private final AgentMentions mentions;
    private final toomanyagents.ui.InventoryAgents inventoryAgents;
    private final toomanyagents.ui.SurveyMode survey;
    private final KeyMapping chatKey = new KeyMapping("Talk to selected agent", KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "Too Many Agents");
    private boolean openInventoryOnTick;
    private String openChatOnTick;
    private String selectedAgent;
    private CompletableFuture<JsonObject> pendingScreenshot;
    private InputConstants.Key heldButton;
    private int heldTicks;

    public ClientControls(IEventBus modBus, Supplier<AgentService> service) {
        this.service = service;
        mentions = new AgentMentions(service);
        modBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::interactSpecific);
        NeoForge.EVENT_BUS.addListener(this::interact);
        NeoForge.EVENT_BUS.addListener(this::frame);
        toomanyagents.ui.ScreenScale.register();
        inventoryAgents = new toomanyagents.ui.InventoryAgents(() -> service.get(), id -> selectedAgent = id);
        survey = new toomanyagents.ui.SurveyMode(() -> service.get());
        new AgentNotifications(service, id -> Minecraft.getInstance().isWindowActive()
            && (Minecraft.getInstance().screen instanceof AgentChatScreen chat && chat.agentId().equals(id)
                || inventoryAgents.viewing(id)));
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(chatKey);
        event.register(survey.key);
    }

    private void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("agents").executes(context -> {
            // ChatScreen closes after command dispatch; wait until it has finished.
            openInventoryOnTick = true;
            return 1;
        }).then(Commands.literal("open").then(Commands.argument("agent", com.mojang.brigadier.arguments.StringArgumentType.word())
            .executes(context -> {
                openChatOnTick = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "agent");
                return 1;
            }))));
    }

    private void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if (heldButton != null && --heldTicks <= 0) { KeyMapping.set(heldButton, false); heldButton = null; }
        if (openChatOnTick != null) {
            String id = openChatOnTick;
            openChatOnTick = null;
            var agents = service.get();
            if (agents != null && client.hasSingleplayerServer() && client.level != null && !"unavailable".equals(agents.snapshot(id).get("status").getAsString())) {
                selectedAgent = id;
                inventoryAgents.openStandalone(id, capturePointing());
            }
        }
        String archived = AgentChatScreen.archived;
        if (archived != null) {
            AgentChatScreen.archived = null;
            if (archived.equals(selectedAgent)) selectedAgent = null;
            inventoryAgents.forget(archived);
            var agents = service.get();
            if (agents != null) {
                if (inventoryAgents.standalone()) inventoryAgents.openStandalone(null, null);
                else openInventory();
            }
            else client.setScreen(null);
        }
        while (chatKey.consumeClick()) {
            if (client.screen != null) continue;
            var agents = service.get();
            if (agents == null || !client.hasSingleplayerServer() || client.level == null) continue;
            JsonObject pointing = capturePointing();
            var selected = selectedAgent == null ? new JsonObject() : agents.snapshot(selectedAgent);
            String lifecycle = selected.has("lifecycle") ? selected.get("lifecycle").getAsString() : "";
            boolean hidden = lifecycle.equals("removed") || lifecycle.equals("archived");
            if (hidden) selectedAgent = null;
            if (selectedAgent != null && !"unavailable".equals(selected.get("status").getAsString())) {
                inventoryAgents.openStandalone(selectedAgent, pointing);
            } else {
                inventoryAgents.openStandalone(null, pointing);
            }
        }
        if (client.level == null) selectedAgent = null;
        if (openInventoryOnTick) {
            openInventoryOnTick = false;
            var agents = service.get();
            if (agents != null && client.hasSingleplayerServer() && client.level != null) {
                openInventory();
            } else if (client.player != null) {
                client.player.displayClientMessage(Component.literal("Agents are available in a local singleplayer world."), false);
            }
        }
    }

    private void openInventory() {
        var client = Minecraft.getInstance();
        if (client.player != null && client.hasSingleplayerServer())
            client.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
    }

    private void interactSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        String agent = managed(event.getTarget());
        if (agent == null) return;
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        openChat(event, agent);
    }

    private void interact(PlayerInteractEvent.EntityInteract event) {
        String agent = managed(event.getTarget());
        if (agent == null) return;
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        openChat(event, agent);
    }

    private String managed(Entity entity) {
        var agents = service.get();
        return agents == null ? null : agents.findByBody(entity.getUUID());
    }

    private void openChat(PlayerInteractEvent event, String agent) {
        if (!event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND) return;
        var client = Minecraft.getInstance();
        var agents = service.get();
        if (agents != null && event.getEntity() == client.player && client.hasSingleplayerServer()) {
            selectedAgent = agent;
            if (client.screen == null) inventoryAgents.openStandalone(agent, capturePointing());
        }
    }

    /** Capture on the client thread before opening any menu, never when Send is pressed. */
    static JsonObject capturePointing() {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return null;
        var result = new JsonObject();
        result.addProperty("capturedAtMs", System.currentTimeMillis());
        result.addProperty("dimension", client.level.dimension().location().toString());
        var player = new JsonObject();
        player.addProperty("uuid", client.player.getStringUUID());
        player.add("position", vector(client.player.position()));
        result.add("player", player);
        var camera = client.gameRenderer.getMainCamera();
        var view = new JsonObject();
        view.add("position", vector(camera.getPosition()));
        var direction = camera.getLookVector();
        view.add("direction", vector(new Vec3(direction.x(), direction.y(), direction.z())));
        result.add("camera", view);
        var target = new JsonObject();
        var hit = client.hitResult;
        target.addProperty("type", "miss");
        if (hit != null) target.add("position", vector(hit.getLocation()));
        if (hit instanceof BlockHitResult block && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            target.addProperty("type", "block");
            var pos = block.getBlockPos();
            target.add("position", vector(new Vec3(pos.getX(), pos.getY(), pos.getZ())));
            target.addProperty("block", BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString());
            target.addProperty("face", block.getDirection().getName());
        } else if (hit instanceof EntityHitResult entity) {
            target.addProperty("type", "entity");
            target.addProperty("entity", entity.getEntity().getStringUUID());
            target.addProperty("name", entity.getEntity().getName().getString());
        }
        result.add("target", target);
        return result;
    }

    private static JsonObject vector(Vec3 value) {
        var result = new JsonObject();
        result.addProperty("x", value.x);
        result.addProperty("y", value.y);
        result.addProperty("z", value.z);
        return result;
    }

    /** Local diagnostics used to inspect real rendered screens; never submits an agent prompt. */
    public CompletableFuture<JsonObject> inspect(JsonObject request) {
        String action = request.get("action").getAsString();
        String agentId = request.has("agentId") ? request.get("agentId").getAsString() : null;
        var result = new CompletableFuture<JsonObject>();
        var client = Minecraft.getInstance();
        client.execute(() -> {
            if (result.isDone()) return;
            try {
                if ("screenshot".equals(action)) {
                    if (pendingScreenshot != null && !pendingScreenshot.isDone()) {
                        throw new IllegalStateException("A screenshot is already pending");
                    }
                    pendingScreenshot = result;
                    return;
                }
                if ("widgets".equals(action)) {
                    result.complete(widgets());
                    return;
                }
                var agents = service.get();
                if (agents == null || !client.hasSingleplayerServer() || client.level == null) {
                    throw new IllegalStateException("Open a local singleplayer world first");
                }
                switch (action) {
                    case "usage" -> {
                        agents.usage().whenComplete((value, failure) -> {
                            if (failure != null) result.completeExceptionally(failure);
                            else result.complete(value);
                        });
                        return;
                    }
                    case "dev_switch_world" -> {
                        var server = client.getSingleplayerServer();
                        String current = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
                        if (!DevelopmentWorld.ENABLED || (!current.equals(DevelopmentWorld.NAME) && !current.equals(DevelopmentWorld.OTHER_NAME)))
                            throw new IllegalStateException("World switching requires one of the two development worlds");
                        String next = current.equals(DevelopmentWorld.NAME) ? DevelopmentWorld.OTHER_NAME : DevelopmentWorld.NAME;
                        client.level.disconnect();
                        client.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                        client.setScreen(new TitleScreen());
                        DevelopmentWorld.open(next);
                    }
                    case "vanilla_chat" -> client.setScreen(new ChatScreen(""));
                    case "mod_settings" -> client.setScreen(new TooManyAgentsSettingsScreen(null, agents));
                    case "archive_view" -> client.setScreen(new toomanyagents.ui.ArchiveScreen(agents, null));
                    case "inventory" -> {
                        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
                        agents.openInventory(agentId).whenComplete((unused, failure) -> {
                            if (failure != null) result.completeExceptionally(failure);
                            else { var opened = new JsonObject(); opened.addProperty("requested", true); result.complete(opened); }
                        });
                        return;
                    }
                    case "survey" -> {
                        boolean on = request.has("on") ? request.get("on").getAsBoolean() : !survey.on();
                        if (on && request.has("projectId")) toomanyagents.ui.SurveyMode.start(request.get("projectId").getAsString());
                        else survey.setOn(on);
                    }
                    case "survey_corner" -> {
                        if (client.screen != null) throw new IllegalStateException("Close the open screen first");
                        survey.corner(blockPos(request));
                    }
                    case "survey_edit" -> { if (!survey.editAt(blockPos(request))) throw new IllegalArgumentException("No box holds that block"); }
                    case "dev_pause", "dev_resume", "dev_leave", "dev_gui_scale", "dev_window_size", "dev_focus", "dev_key", "dev_input_state", "dev_inventory_click", "dev_look", "dev_press" -> {
                        var server = client.getSingleplayerServer();
                        if (!DevelopmentWorld.ENABLED || server == null || !server.getWorldPath(LevelResource.ROOT)
                            .toAbsolutePath().normalize().getFileName().toString().equals(DevelopmentWorld.NAME)) {
                            throw new IllegalStateException("Development controls require the exact development world");
                        }
                        if (action.equals("dev_input_state")) {
                            long window = client.getWindow().getWindow();
                            var input = new JsonObject();
                            input.addProperty("leftCommand", GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS);
                            input.addProperty("rightCommand", GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS);
                            input.addProperty("narrator", client.options.narrator().get().toString());
                            input.addProperty("inKeyDispatch", KeyboardModifiers.CURRENT.get() != null);
                            result.complete(input);
                            return;
                        }
                        else if (action.equals("dev_focus")) GLFW.glfwFocusWindow(client.getWindow().getWindow());
                        else if (action.equals("dev_window_size")) {
                            int width = request.get("width").getAsInt(), height = request.get("height").getAsInt();
                            if (width < 640 || width > 2560 || height < 480 || height > 1600) throw new IllegalArgumentException("Use a window between 640×480 and 2560×1600");
                            client.getWindow().setWindowed(width, height);
                        }
                        else if (action.equals("dev_inventory_click")) {
                            if (!(client.screen instanceof AgentInventoryScreen) || !(client.player.containerMenu instanceof AgentInventoryMenu menu))
                                throw new IllegalStateException("Open an agent inventory first");
                            int slot = request.get("slot").getAsInt();
                            if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) throw new IllegalArgumentException("Invalid slot");
                            var click = net.minecraft.world.inventory.ClickType.valueOf(request.get("click").getAsString());
                            client.gameMode.handleInventoryMouseClick(menu.containerId, slot, request.get("button").getAsInt(), click, client.player);
                        }
                        else if (action.equals("dev_key")) {
                            int key = request.get("key").getAsInt();
                            if (key < GLFW.GLFW_KEY_SPACE || key > GLFW.GLFW_KEY_LAST) throw new IllegalArgumentException("Invalid key");
                            long window = client.getWindow().getWindow();
                            int modifiers = request.has("modifiers") ? request.get("modifiers").getAsInt() : 0;
                            client.keyboardHandler.keyPress(window, key, 0, GLFW.GLFW_PRESS, modifiers);
                            client.keyboardHandler.keyPress(window, key, 0, GLFW.GLFW_RELEASE, modifiers);
                        }
                        else if (action.equals("dev_look")) client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atBottomCenterOf(blockPos(request).above()));
                        else if (action.equals("dev_press")) {
                            // Drives the attack/use key mappings the way a mouse button does, for survey click checks.
                            int button = request.get("button").getAsInt();
                            if (button != 0 && button != 1) throw new IllegalArgumentException("Button must be 0 (attack) or 1 (use)");
                            if (heldButton != null) KeyMapping.set(heldButton, false);
                            heldButton = (button == 0 ? client.options.keyAttack : client.options.keyUse).getKey();
                            heldTicks = request.has("ticks") ? Math.clamp(request.get("ticks").getAsInt(), 1, 100) : 2;
                            KeyMapping.set(heldButton, true);
                            KeyMapping.click(heldButton);
                        }
                        else if (action.equals("dev_gui_scale")) {
                            int scale = request.get("scale").getAsInt();
                            if (scale < 1 || scale > 4) throw new IllegalArgumentException("GUI scale must be 1–4");
                            client.options.guiScale().set(scale);
                            client.resizeDisplay();
                        }
                        else if (action.equals("dev_pause")) client.setScreen(new PauseScreen(true));
                        else if (action.equals("dev_leave")) {
                            // Follow the singleplayer PauseScreen disconnect lifecycle, including saving.
                            client.level.disconnect();
                            client.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                            client.setScreen(new TitleScreen());
                        }
                        else {
                            if (!(client.screen instanceof PauseScreen)) throw new IllegalStateException("The vanilla pause screen is not open");
                            client.setScreen(null);
                        }
                    }
                    case "spawn" -> inventoryAgents.newAgent(request.has("projectId") ? request.get("projectId").getAsString() : "");
                    case "list" -> openInventory();
                    case "settings" -> {
                        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
                        client.setScreen(new AgentSettingsScreen(agents, null, AgentSettingsScreen.settings(agents.snapshot(agentId)), settings -> agents.updateSettings(agentId, settings), agentId));
                    }
                    case "chat" -> {
                        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
                        selectedAgent = agentId;
                        inventoryAgents.openStandalone(agentId, capturePointing());
                    }
                    default -> throw new IllegalArgumentException("Unknown screen action: " + action);
                }
                var reply = new JsonObject();
                reply.addProperty("screen", client.screen == null ? "game" : client.screen.getClass().getSimpleName());
                reply.addProperty("gamePaused", client.isPaused());
                result.complete(reply);
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.orTimeout(5, TimeUnit.SECONDS);
    }

    /** Exercise only the mod's native screens through their real widget event handlers. */
    public CompletableFuture<JsonObject> input(JsonObject request) {
        var result = new CompletableFuture<JsonObject>();
        Minecraft.getInstance().execute(() -> {
            if (result.isDone()) return;
            try {
                Screen screen = Minecraft.getInstance().screen;
                if (!ours(screen) && !inventoryAgents.supports(screen)) throw new IllegalStateException("Open a Too Many Agents screen first");
                switch (request.get("action").getAsString()) {
                    case "click" -> {
                        double x = request.get("x").getAsDouble();
                        double y = request.get("y").getAsDouble();
                        int button = request.has("button") ? request.get("button").getAsInt() : 0;
                        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x >= screen.width || y >= screen.height) {
                            throw new IllegalArgumentException("Click coordinates must be inside the GUI-scaled screen bounds");
                        }
                        // Events carry game GUI coordinates; ScreenScale converts them back for scaled screens.
                        float factor = toomanyagents.ui.ScreenScale.factor(screen);
                        var before = new ScreenEvent.MouseButtonPressed.Pre(screen, x * factor, y * factor, button);
                        NeoForge.EVENT_BUS.post(before);
                        if (!before.isCanceled()) screen.mouseClicked(x, y, button);
                        var released = new ScreenEvent.MouseButtonReleased.Pre(screen, x * factor, y * factor, button);
                        NeoForge.EVENT_BUS.post(released);
                        if (!released.isCanceled()) screen.mouseReleased(x, y, button);
                    }
                    case "hover" -> {
                        double x = request.get("x").getAsDouble(), y = request.get("y").getAsDouble();
                        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x >= screen.width || y >= screen.height) {
                            throw new IllegalArgumentException("Hover coordinates must be inside the GUI-scaled screen bounds");
                        }
                        var window = Minecraft.getInstance().getWindow();
                        var mouse = Minecraft.getInstance().mouseHandler;
                        float factor = toomanyagents.ui.ScreenScale.factor(screen);
                        mouse.xpos = x * factor * window.getScreenWidth() / window.getGuiScaledWidth();
                        mouse.ypos = y * factor * window.getScreenHeight() / window.getGuiScaledHeight();
                    }
                    case "text" -> {
                        var inputScreen = inventoryAgents.inputScreen(screen);
                        String value = request.get("text").getAsString();
                        boolean append = request.has("append") && request.get("append").getAsBoolean();
                        if (inputScreen.getFocused() instanceof net.minecraft.client.gui.components.MultiLineEditBox multiline) {
                            if (!multiline.active) throw new IllegalStateException("The focused field is not editable");
                            multiline.setValue((append ? multiline.getValue() : "") + value);
                            break;
                        }
                        if (!(inputScreen.getFocused() instanceof EditBox edit)) {
                            throw new IllegalStateException("Focus a text field first");
                        }
                        if (!edit.canConsumeInput()) throw new IllegalStateException("The focused field is not editable");
                        if (!append) {
                            edit.moveCursorToEnd(false);
                            edit.setHighlightPos(0);
                        }
                        edit.insertText(value);
                    }
                    case "key" -> {
                        int key = request.get("key").getAsInt();
                        int modifiers = request.has("modifiers") ? request.get("modifiers").getAsInt() : 0;
                        var before = new ScreenEvent.KeyPressed.Pre(screen, key, 0, modifiers);
                        NeoForge.EVENT_BUS.post(before);
                        if (!before.isCanceled()) screen.keyPressed(key, 0, modifiers);
                        screen.keyReleased(key, 0, modifiers);
                    }
                    case "close" -> screen.onClose();
                    case "scroll" -> {
                        double x = request.get("x").getAsDouble(), y = request.get("y").getAsDouble();
                        double delta = request.get("delta").getAsDouble();
                        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(delta)) throw new IllegalArgumentException("Invalid scroll");
                        float factor = toomanyagents.ui.ScreenScale.factor(screen);
                        var before = new ScreenEvent.MouseScrolled.Pre(screen, x * factor, y * factor, 0, delta);
                        NeoForge.EVENT_BUS.post(before);
                        if (!before.isCanceled()) screen.mouseScrolled(x,y,0,delta);
                    }
                    default -> throw new IllegalArgumentException("Unknown input action");
                }
                result.complete(widgets());
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.orTimeout(5, TimeUnit.SECONDS);
    }

    private static net.minecraft.core.BlockPos blockPos(JsonObject request) {
        return new net.minecraft.core.BlockPos(request.get("x").getAsInt(), request.get("y").getAsInt(), request.get("z").getAsInt());
    }

    private static boolean ours(Screen screen) {
        return screen instanceof toomanyagents.ui.ProviderSettingsScreen || screen instanceof toomanyagents.ui.ProjectScreen || screen instanceof AgentChatScreen
            || screen instanceof toomanyagents.ui.SurveyScreen
            || screen instanceof AgentSettingsScreen || screen instanceof toomanyagents.ui.ArchiveScreen
            || screen instanceof AgentApprovalScreen || screen instanceof AgentQuestionScreen || screen instanceof AgentQueueScreen
            || screen instanceof AgentInventoryScreen || screen instanceof TooManyAgentsSettingsScreen || screen instanceof ChatScreen || screen instanceof KeyBindsScreen;
    }

    private JsonObject widgets() {
        var client = Minecraft.getInstance();
        var screen = client.screen;
        var reply = new JsonObject();
        reply.addProperty("screen", screen == null ? "game" : screen.getClass().getSimpleName());
        reply.addProperty("selectedAgent", selectedAgent);
        reply.addProperty("gamePaused", client.isPaused());
        reply.addProperty("windowActive", client.isWindowActive());
        reply.addProperty("mouseX", client.mouseHandler.xpos() * client.getWindow().getGuiScaledWidth() / client.getWindow().getScreenWidth());
        reply.addProperty("mouseY", client.mouseHandler.ypos() * client.getWindow().getGuiScaledHeight() / client.getWindow().getScreenHeight());
        reply.addProperty("guiScale", client.options.guiScale().get());
        reply.addProperty("chatKey", chatKey.saveString());
        reply.add("survey", survey.diagnostics());
        if (screen instanceof ChatScreen) reply.add("mentions", mentions.diagnostics(screen));
        if (screen instanceof AgentChatScreen chat) reply.add("pointing", chat.pointingContext());
        if (screen instanceof AgentInventoryScreen inventory) reply.add("inventory", inventory.inventoryState());
        if (screen instanceof AgentApprovalScreen approval) reply.add("approval", approval.diagnostics());
        if (screen instanceof AgentQuestionScreen question) reply.add("question", question.diagnostics());
        if (inventoryAgents.supports(screen)) reply.add("inventoryAgents", inventoryAgents.diagnostics());
        reply.addProperty("width", client.getWindow().getGuiScaledWidth());
        reply.addProperty("height", client.getWindow().getGuiScaledHeight());
        var children = new JsonArray();
        if (ours(screen) || inventoryAgents.supports(screen)) {
            for (var widget : allWidgets(screen)) {
                var item = new JsonObject();
                item.addProperty("index", children.size());
                item.addProperty("type", widget.getClass().getSimpleName());
                item.addProperty("label", widget.getMessage().getString());
                item.addProperty("x", widget.getX());
                item.addProperty("y", widget.getY());
                item.addProperty("width", widget.getWidth());
                item.addProperty("height", widget.getHeight());
                item.addProperty("active", widget.active);
                item.addProperty("visible", widget.visible);
                item.addProperty("focused", widget.isFocused());
                if (widget instanceof EditBox edit) item.addProperty("text", edit.getValue());
                if (widget instanceof net.minecraft.client.gui.components.MultiLineEditBox edit) item.addProperty("text", edit.getValue());
                children.add(item);
            }
        }
        reply.add("widgets", children);
        return reply;
    }

    private static java.util.List<AbstractWidget> allWidgets(ContainerEventHandler parent) {
        var widgets = new java.util.ArrayList<AbstractWidget>();
        for (GuiEventListener child : parent.children()) {
            if (child instanceof AbstractWidget widget) widgets.add(widget);
            if (child instanceof ContainerEventHandler container) widgets.addAll(allWidgets(container));
        }
        return widgets;
    }

    private void frame(RenderFrameEvent.Post event) {
        var result = pendingScreenshot;
        if (result == null) return;
        pendingScreenshot = null;
        if (result.isDone()) return;
        var client = Minecraft.getInstance();
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
            var path = FMLPaths.GAMEDIR.get().resolve("too-many-agents/screen.png").toAbsolutePath();
            Files.createDirectories(path.getParent());
            image.writeToFile(path);
            var reply = new JsonObject();
            reply.addProperty("path", path.toString());
            reply.addProperty("width", image.getWidth());
            reply.addProperty("height", image.getHeight());
            reply.addProperty("guiWidth", client.getWindow().getGuiScaledWidth());
            reply.addProperty("guiHeight", client.getWindow().getGuiScaledHeight());
            reply.addProperty("capturedAtMs", System.currentTimeMillis());
            reply.addProperty("screen", client.screen == null ? "game" : client.screen.getClass().getSimpleName());
            result.complete(reply);
        } catch (Exception failure) {
            result.completeExceptionally(failure);
        }
    }
}
