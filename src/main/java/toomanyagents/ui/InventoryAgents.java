package toomanyagents.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import org.lwjgl.glfw.GLFW;
import toomanyagents.TooManyAgentsClientSettings;

/** Shared sidebar and chat, arranged around the inventory or together in a standalone screen. */
public final class InventoryAgents {
    private final Supplier<AgentUiAccess> access;
    private final Consumer<String> select;
    private final Map<String, AgentChatScreen> chats = new HashMap<>();
    private final Map<String, AgentChatScreen> drafts = new HashMap<>();
    private Object world;
    private Screen parent;
    private InventoryAgentSidebar sidebar;
    private AgentChatScreen chat;
    private String selected;
    private String inventoryTarget, inventoryPending;
    private Pane left, right, settings, formPane, focus, pressed;
    // Project or mod settings take the chat's place while open.
    private SettingsFormScreen form;
    private boolean pendingModSettings;
    private boolean hostOpen;
    private long newAgentRequest;
    // Settings sit beside the chat when the chat keeps its minimum width; unscaled units.
    private static final int SETTINGS_WIDTH = 400, SETTINGS_MIN_WIDTH = 320, CHAT_MIN_WIDTH = 360;

    private record Pane(Screen screen, int x, int y, int width, int height, float scale) {
        boolean contains(double px, double py) { return px >= x && px < x + width && py >= y && py < y + height; }
        double localX(double px) { return (px - x) / scale; }
        double localY(double py) { return (py - y) / scale; }
    }

    public InventoryAgents(Supplier<AgentUiAccess> access, Consumer<String> select) {
        this.access = access;
        this.select = select;
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::click);
        NeoForge.EVENT_BUS.addListener(this::release);
        NeoForge.EVENT_BUS.addListener(this::drag);
        NeoForge.EVENT_BUS.addListener(this::scroll);
        NeoForge.EVENT_BUS.addListener(this::key);
        NeoForge.EVENT_BUS.addListener(this::keyReleased);
        NeoForge.EVENT_BUS.addListener(this::character);
        NeoForge.EVENT_BUS.addListener(this::effects);
        NeoForge.EVENT_BUS.addListener(this::portraitNameTag);
    }

    private void portraitNameTag(RenderNameTagEvent event) {
        if (Minecraft.getInstance().screen instanceof AgentInventoryScreen inventory && inventory.isRenderingPreview())
            event.setCanRender(TriState.FALSE);
    }

    public boolean viewing(String id) {
        return Minecraft.getInstance().screen == parent && right != null && chat != null && chat.agentId().equals(id);
    }

    public boolean standalone() { return parent instanceof AgentWorkspaceScreen; }

    public void forget(String id) {
        var forgotten=chats.remove(id);
        if(forgotten!=null)forgotten.removed();
        if (id.equals(selected)) {
            chat = null; right = settings = null; focus = null; pressed = null; selected = null;
            if (sidebar != null) sidebar.selected(null);
        }
    }

    public boolean supports(Screen screen) {
        var client = Minecraft.getInstance();
        return (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen
            || screen instanceof AgentInventoryScreen || screen instanceof AgentWorkspaceScreen)
            && client.level != null && client.hasSingleplayerServer() && access.get() != null;
    }

    private boolean prepare(Screen screen) {
        var client = Minecraft.getInstance();
        if (screen != client.screen || !supports(screen)) return false;
        if (world != client.level) {
            chats.values().forEach(AgentChatScreen::removed);drafts.values().forEach(AgentChatScreen::removed);
            world = client.level;
            chats.clear(); drafts.clear(); selected = null; chat = null; parent = null; form = null;
            inventoryTarget = inventoryPending = null;
        }
        if (parent != screen) {
            newAgentRequest++;
            parent = screen;
            if (sidebar != null) sidebar.closePortraits();
            sidebar = new InventoryAgentSidebar(access.get(), parent, this::open, this::newAgent, this::openProject, this::openModSettings);
            if (form instanceof TooManyAgentsSettingsScreen) form = null;
            if (screen instanceof AgentWorkspaceScreen workspace) {
                pendingModSettings = workspace.opensModSettings();
                boolean keepDraft = workspace.initialAgentId().isBlank() && chat != null && chat.draft();
                selected = workspace.initialAgentId().isBlank() ? null : workspace.initialAgentId();
                if (!keepDraft) chat = selected == null ? null : chats.computeIfAbsent(selected, key -> new AgentChatScreen(access.get(), key));
                if (chat != null) {
                    chat.capturePointing(workspace.pointingContext());
                    select.accept(selected);
                }
                workspace.setFileDropHandler(paths -> { if (chat != null) chat.onFilesDrop(paths); });
            }
            if (screen instanceof AgentInventoryScreen inventory) {
                if (inventory.agentId().equals(inventoryPending)) {
                    inventoryPending = null;
                    if (!inventory.agentId().equals(inventoryTarget)) {
                        if (inventoryTarget == null) {
                            inventory.closeAgentInventory();
                            return prepare(client.screen);
                        }
                        requestInventory();
                    }
                } else {
                    selected = inventory.agentId();
                    inventoryTarget = selected;
                    chat = chats.computeIfAbsent(selected, key -> new AgentChatScreen(access.get(), key));
                    select.accept(selected);
                }
            }
            sidebar.selected(selected);
            if (chat != null) chat.dock(parent, this::closeChat, this::toggleInventory);
            left = right = settings = formPane = focus = pressed = null;
        }
        if (pendingModSettings) {
            pendingModSettings = false;
            openModSettings();
            return true;
        }
        if (screen instanceof AgentWorkspaceScreen) {
            prepareStandalone(screen);
            return true;
        }
        var inventory = (AbstractContainerScreen<?>) screen;
        int margin = 8;
        int leftEdge = inventory.getGuiLeft();
        if (screen instanceof InventoryScreen survival && survival.getRecipeBookComponent().isVisible()) leftEdge -= 154;
        int rightEdge = inventory.getGuiLeft() + inventory.getXSize();
        // Keep vanilla effect icons and their tooltips reachable beside the inventory.
        if (!client.player.getActiveEffects().isEmpty()) rightEdge += 36;
        int leftSpace = Math.max(0, leftEdge - margin * 2);
        int rightSpace = Math.max(0, screen.width - rightEdge - margin * 2);
        int availableHeight = Math.max(1, screen.height - margin * 2);
        int ownScale = TooManyAgentsClientSettings.get().screenScale();
        float preferred = ownScale == 0 ? 1 : (float)(client.getWindow().calculateScale(ownScale, client.isEnforceUnicode()) / client.getWindow().getGuiScale());
        float scale = Math.min(preferred, Math.min(rightSpace / 380F, availableHeight / 400F));
        scale = Math.max(.1F, scale);
        boolean collapsed = TooManyAgentsClientSettings.get().sidebarCollapsed();
        float sidebarScale = Math.min(scale, Math.max(.1F, leftSpace / 180F));
        left = layout(left, sidebar, margin, margin,
            collapsed ? Math.max(12, Math.round(28 * sidebarScale)) : Math.min(leftSpace, Math.round(260 * scale)),
            collapsed ? Math.max(12, Math.round(32 * sidebarScale)) : availableHeight, sidebarScale);
        int chatWidth = Math.min(rightSpace, Math.round(540 * scale));
        int settingsWidth = settingsWidth(rightSpace, scale);
        boolean beside = settingsWidth > 0;
        if (beside) chatWidth = Math.min(chatWidth, rightSpace - settingsWidth);
        right = chat == null || form != null ? null : layout(right, chat, rightEdge + margin, margin, chatWidth, availableHeight, scale);
        formPane = form == null ? null : layout(formPane, form, rightEdge + margin, margin,
            Math.min(rightSpace, Math.round(540 * scale)), availableHeight, scale);
        layoutSettings(rightEdge + margin + chatWidth, beside ? settingsWidth : 0);
        return true;
    }

    private boolean settingsOpen() { return form == null && chat != null && chat.settingsPanel() != null; }

    /** Width for side settings within the space shared with the chat, or 0 when they must cover the chat. */
    private int settingsWidth(int space, float scale) {
        int width = Math.min(Math.round(SETTINGS_WIDTH * scale), space - Math.round(CHAT_MIN_WIDTH * scale));
        return settingsOpen() && width >= Math.round(SETTINGS_MIN_WIDTH * scale) ? width : 0;
    }

    /** Settings sit beside the chat when both fit; otherwise they replace the chat between its top and bottom bars. */
    private void layoutSettings(int sideX, int sideWidth) {
        if (!settingsOpen() || right == null) {
            if (settings != null && focus == settings) focus = right;
            if (pressed == settings) pressed = null;
            settings = null;
            return;
        }
        var panel = chat.settingsPanel();
        boolean fresh = settings == null || settings.screen != panel;
        chat.cover(sideWidth == 0);
        int[] bounds = chat.settingsBounds();
        float scale = right.scale;
        // Side settings run the chat's full height, with the chat's own padding as the gap.
        panel.side(sideWidth > 0);
        settings = sideWidth == 0
            ? layout(settings, panel, right.x + Math.round(bounds[0] * scale), right.y + Math.round(bounds[1] * scale),
                Math.round(bounds[2] * scale), Math.round(bounds[3] * scale), scale)
            : layout(settings, panel, sideX, right.y, sideWidth, right.height, scale);
        if (fresh && settings != null) {
            if (focus != null) focus.screen.setFocused(null);
            focus = settings;
        }
    }

    private void prepareStandalone(Screen screen) {
        var client = Minecraft.getInstance();
        int ownScale = TooManyAgentsClientSettings.get().screenScale();
        float preferred = ownScale == 0 ? 1 : (float)(client.getWindow().calculateScale(ownScale, client.isEnforceUnicode()) / client.getWindow().getGuiScale());
        boolean collapsed = TooManyAgentsClientSettings.get().sidebarCollapsed();
        int height = Math.max(1, screen.height - 16);
        int availableWidth = screen.width - 16;
        float scale = Math.max(.1F, Math.min(preferred, Math.min(availableWidth / (collapsed ? 580F : 812F), height / 400F)));
        int gap = Math.round(12 * scale);
        int sidebarWidth = collapsed ? Math.max(12, Math.round(28 * scale)) : Math.round(260 * scale);
        int totalWidth = Math.min(availableWidth, Math.round((collapsed ? 824 : 1056) * scale));
        int x = (screen.width - totalWidth) / 2;
        int settingsWidth = settingsWidth(availableWidth - sidebarWidth - gap, scale);
        boolean beside = settingsWidth > 0;
        // The workspace widens for side settings as far as the window allows; the chat narrows for the rest.
        if (beside) {
            totalWidth = Math.min(availableWidth, totalWidth + settingsWidth);
            x = (screen.width - totalWidth) / 2;
        }
        boolean focusChat = right == null || collapsed && left != null && focus == left;
        left = layout(left, sidebar, x, 8, sidebarWidth, collapsed ? Math.max(12, Math.round(32 * scale)) : height, scale);
        int chatX = x + sidebarWidth + gap;
        int chatWidth = totalWidth - sidebarWidth - gap - (beside ? settingsWidth : 0);
        right = chat == null || form != null ? null : layout(right, chat, chatX, 8, chatWidth, height, scale);
        formPane = form == null ? null : layout(formPane, form, chatX, 8, totalWidth - sidebarWidth - gap, height, scale);
        if (focusChat && right != null) focus = right;
        layoutSettings(chatX + chatWidth, beside ? settingsWidth : 0);
    }

    public void openStandalone(String id, JsonObject pointing) {
        Minecraft.getInstance().setScreen(new AgentWorkspaceScreen(id, pointing));
    }

    /** Opens a project's settings, or a new project for "", in the chat's place. */
    private void openProject(String projectId) {
        showForm(projectId.isBlank() ? ProjectScreen.create(access.get(), parent) : ProjectScreen.edit(access.get(), parent, projectId));
    }

    private void openModSettings() { showForm(new TooManyAgentsSettingsScreen(parent, access.get())); }

    private void showForm(SettingsFormScreen next) {
        navigate(() -> showClosedForm(next));
    }

    private void showClosedForm(SettingsFormScreen next) {
        newAgentRequest++;
        next.dockWithHeader(() -> {
            if (form != next) return;
            form = null;
            if (focus == formPane) focus = null;
            formPane = null;
            if (prepare(parent) && focus == null) focus = right;
        });
        form = next;
        if (focus != null) focus.screen.setFocused(null);
        prepare(parent);
        focus = formPane;
        parent.setFocused(null);
    }

    /** Saves and closes the open form; a failed save leaves it open with the error. */
    private void closeForm() { if (form != null) form.onClose(); }

    /** Keep the current pane visible until any edited settings have saved. */
    private void navigate(Runnable next) {
        Object requestWorld = world;
        Screen requestParent = parent;
        Runnable guarded = () -> {
            if (world == requestWorld && parent == requestParent && Minecraft.getInstance().screen == requestParent) next.run();
        };
        if (form != null) form.closeThen(() -> navigate(guarded));
        else if (chat != null && chat.settingsPanel() != null) chat.closeSettingsThen(() -> navigate(guarded));
        else guarded.run();
    }

    public void newAgent(String projectId) {
        var client = Minecraft.getInstance();
        if (!supports(client.screen)) openStandalone(null, null);
        if (!prepare(client.screen)) return;
        navigate(() -> newClosedAgent(projectId));
    }

    private void newClosedAgent(String projectId) {
        var client = Minecraft.getInstance();
        // The global New thread action leaves project selection to the draft.
        String project = projectId == null ? "minecraft" : projectId;
        String draftProject = project;
        var requestWorld = client.level;
        var requestScreen = parent;
        var requestAccess = access.get();
        long request = ++newAgentRequest;
        requestAccess.backendConfig().thenCombine(requestAccess.projectExecutionOptions(draftProject), (config, defaults) -> {
            var result = config.deepCopy();
            result.add("draftDefaults", defaults);
            return result;
        }).whenComplete((config, failure) -> client.execute(() -> {
            if (request != newAgentRequest || client.level != requestWorld || client.screen != requestScreen
                    || world != requestWorld || parent != requestScreen || access.get() != requestAccess) return;
            if (failure != null) {
                sidebar.reportProviderError("Could not load BB defaults for a new chat: " + AgentModels.error(failure));
                return;
            }
            String provider = InventoryAgentSidebar.defaultProvider(config);
            if (provider.isBlank()) provider = AgentModels.text(AgentModels.object(config, "draftDefaults"), "providerId");
            openDraft(draftProject, provider, requestAccess);
        }));
    }

    private void openDraft(String project, String provider, AgentUiAccess draftAccess) {
        if (chat != null) { chat.closeSettings();chat.removed(); }
        chat = drafts.computeIfAbsent(project + ":" + provider, key -> {
            var settings = new JsonObject();
            settings.addProperty("projectId", project);
            if (!provider.isBlank()) settings.addProperty("providerId", provider);
            Object draftWorld = world;
            return new AgentChatScreen(draftAccess, settings, id -> {
                if (world != draftWorld) return;
                var created = drafts.remove(key);
                if (created == null) return;
                chats.put(id, created);
                if (chat == created) {
                    selected = id;
                    select.accept(id);
                    sidebar.selected(id);
                }
            });
        });
        hideInventory();
        selected = null;
        select.accept(null);
        sidebar.selected(null);
        chat.dock(parent, this::closeChat, this::toggleInventory);
        right = null;
        prepare(parent);
        focus = right;
        parent.setFocused(null);
    }

    private Pane layout(Pane old, Screen screen, int x, int y, int width, int height, float scale) {
        if (width < 12) return null;
        Pane next = new Pane(screen, x, y, width, height, scale);
        if (old == null || old.screen != screen || old.width != width || old.height != height || old.scale != scale) {
            screen.init(Minecraft.getInstance(), Math.max(1, (int)(width / scale)), Math.max(1, (int)(height / scale)));
        }
        if (focus == old && old != null) focus = next;
        if (pressed == old && old != null) pressed = next;
        return next;
    }

    private void open(String id) {
        navigate(() -> openClosed(id));
    }

    private void openClosed(String id) {
        newAgentRequest++;
        boolean keepInventory = inventoryTarget != null || parent instanceof AgentInventoryScreen;
        selected = id;
        select.accept(id);
        sidebar.selected(id);
        var next = chats.computeIfAbsent(id, key -> new AgentChatScreen(access.get(), key));
        if (chat != null && chat != next) { chat.closeSettings();chat.removed(); }
        chat = next;
        if (parent instanceof AgentWorkspaceScreen workspace) chat.capturePointing(workspace.pointingContext());
        chat.dock(parent, this::closeChat, this::toggleInventory);
        right = null;
        prepare(parent);
        focus = right;
        parent.setFocused(null);
        if (keepInventory) {
            if (chat.hasMinecraftInventory()) {
                inventoryTarget = id;
                if (!(parent instanceof AgentInventoryScreen inventory) || !id.equals(inventory.agentId())) requestInventory();
            } else hideInventory();
        }
    }

    private void toggleInventory() {
        if (inventoryTarget != null || parent instanceof AgentInventoryScreen) hideInventory();
        else {
            inventoryTarget = selected;
            requestInventory();
        }
    }

    private void hideInventory() {
        inventoryTarget = null;
        // Wait for an outstanding open before closing: native close packets do not carry a checked menu id.
        if (inventoryPending == null && Minecraft.getInstance().screen instanceof AgentInventoryScreen inventory) {
            inventory.closeAgentInventory();
            prepare(Minecraft.getInstance().screen);
        }
    }

    private void requestInventory() {
        if (inventoryPending != null || inventoryTarget == null) return;
        String id = inventoryTarget;
        inventoryPending = id;
        Object requestWorld = world;
        var operation = access.get().openInventory(id);
        if (chat != null) chat.inventoryResult(operation);
        operation.whenComplete((unused, failure) -> Minecraft.getInstance().execute(() -> {
            if (failure == null || world != requestWorld || !id.equals(inventoryPending)) return;
            inventoryPending = null;
            if (inventoryTarget != null && !id.equals(inventoryTarget)) requestInventory();
            else hideInventory();
        }));
    }

    private void closeChat() {
        navigate(this::closeClosedChat);
    }

    private void closeClosedChat() {
        newAgentRequest++;
        if(chat!=null)chat.removed();
        if (parent instanceof AgentWorkspaceScreen) {
            parent.onClose();
            return;
        }
        hideInventory();
        if (chat != null) chat.closeSettings();
        chat = null; right = settings = null; focus = null; pressed = null;
        selected = null;
        sidebar.selected(null);
    }

    private void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if (client.level == null) {
            chats.values().forEach(AgentChatScreen::removed);drafts.values().forEach(AgentChatScreen::removed);
            if (sidebar != null) sidebar.closePortraits();
            world = null; parent = null; sidebar = null; chat = null;
            left = right = settings = formPane = focus = pressed = null; selected = null; chats.clear(); drafts.clear(); form = null;
            inventoryTarget = inventoryPending = null;
            return;
        }
        boolean open = prepare(client.screen);
        // Leaving the agent screens saves open settings, once.
        if (!open && hostOpen) {
            newAgentRequest++;
            chats.values().forEach(AgentChatScreen::removed);drafts.values().forEach(AgentChatScreen::removed);
            if (chat != null) chat.closeSettings();
            // Mod settings save as they change and stay open across their linked screens.
            if (form instanceof ProjectScreen) closeForm();
        }
        hostOpen = open;
        if (!open) {
            if (client.screen == null) inventoryTarget = null;
            return;
        }
        if (!(parent instanceof AgentInventoryScreen) && inventoryPending == null) inventoryTarget = null;
        sidebar.tick();
        if (chat != null) chat.tick();
    }

    private void render(ScreenEvent.Render.Post event) {
        if (!prepare(event.getScreen())) return;
        draw(event.getGuiGraphics(), left, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        draw(event.getGuiGraphics(), right, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        draw(event.getGuiGraphics(), settings, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        draw(event.getGuiGraphics(), formPane, event.getMouseX(), event.getMouseY(), event.getPartialTick());
        if (parent instanceof AgentWorkspaceScreen && chat == null && form == null) {
            String hint = TooManyAgentsClientSettings.get().sidebarCollapsed()
                ? "Expand the sidebar to choose an agent" : "Choose an agent to start chatting";
            int start = left == null ? 8 : left.x + left.width + 12;
            event.getGuiGraphics().drawCenteredString(Minecraft.getInstance().font, hint,
                (start + parent.width) / 2, parent.height / 2, 0xAEB6B5);
        }
    }

    private void draw(GuiGraphics graphics, Pane pane, int mouseX, int mouseY, float partialTick) {
        if (pane == null) return;
        graphics.flush();
        var local = new PanelGraphics(graphics, pane);
        local.enableScissor(0, 0, pane.screen.width, pane.screen.height);
        pane.screen.renderWithTooltip(local, (int)pane.localX(mouseX), (int)pane.localY(mouseY), partialTick);
        local.flush();
        local.disableScissor();
    }

    private Pane at(double x, double y) {
        // An open chat picker may extend over covering settings.
        if (right != null && right.contains(x, y) && chat != null && chat.pickerOpen()) return right;
        if (settings != null && settings.contains(x, y)) return settings;
        if (formPane != null && formPane.contains(x, y)) return formPane;
        return left != null && left.contains(x, y) ? left : right != null && right.contains(x, y) ? right : null;
    }

    private void click(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!prepare(event.getScreen())) return;
        Pane pane = at(event.getMouseX(), event.getMouseY());
        if (pane == null) {
            if (focus != null) focus.screen.setFocused(null);
            focus = pressed = null;
            return;
        }
        event.setCanceled(true);
        if (focus != null && focus.screen != pane.screen) focus.screen.setFocused(null);
        focus = pressed = pane;
        parent.setFocused(null);
        pane.screen.mouseClicked(pane.localX(event.getMouseX()), pane.localY(event.getMouseY()), event.getButton());
    }

    private void release(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!prepare(event.getScreen())) return;
        Pane pane = pressed;
        pressed = null;
        if (pane != null) {
            event.setCanceled(true);
            pane.screen.mouseReleased(pane.localX(event.getMouseX()), pane.localY(event.getMouseY()), event.getButton());
        }
    }

    private void drag(ScreenEvent.MouseDragged.Pre event) {
        if (!prepare(event.getScreen()) || pressed == null) return;
        event.setCanceled(true);
        pressed.screen.mouseDragged(pressed.localX(event.getMouseX()), pressed.localY(event.getMouseY()),
            event.getMouseButton(), event.getDragX() / pressed.scale, event.getDragY() / pressed.scale);
    }

    private void scroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!prepare(event.getScreen())) return;
        Pane pane = at(event.getMouseX(), event.getMouseY());
        if (pane == null) return;
        event.setCanceled(true);
        pane.screen.mouseScrolled(pane.localX(event.getMouseX()), pane.localY(event.getMouseY()), event.getScrollDeltaX(), event.getScrollDeltaY());
    }

    private void key(ScreenEvent.KeyPressed.Pre event) {
        if (!prepare(event.getScreen()) || focus == null) return;
        event.setCanceled(true);
        var focused = focus.screen.getFocused();
        boolean text = focused instanceof EditBox || focused instanceof MultiLineEditBox;
        if (!(parent instanceof AgentWorkspaceScreen) && !text && Minecraft.getInstance().options.keyInventory.matches(event.getKeyCode(), event.getScanCode())) navigate(parent::onClose);
        // Escape dismisses a picker before leaving the whole screen.
        else if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE && sidebar.pickerOpen()) sidebar.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
        // Escape in settings closes a dropdown, then the settings themselves.
        else if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE && focus != settings && focus != formPane && (focus == left || chat == null || !chat.pickerOpen())) navigate(parent::onClose);
        else focus.screen.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
    }

    private void keyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (!prepare(event.getScreen()) || focus == null) return;
        event.setCanceled(true);
        focus.screen.keyReleased(event.getKeyCode(), event.getScanCode(), event.getModifiers());
    }

    private void character(ScreenEvent.CharacterTyped.Pre event) {
        if (!prepare(event.getScreen()) || focus == null) return;
        event.setCanceled(true);
        focus.screen.charTyped(event.getCodePoint(), event.getModifiers());
    }

    private void effects(ScreenEvent.RenderInventoryMobEffects event) {
        if (supports(event.getScreen())) event.setCompact(true);
    }

    public Screen inputScreen(Screen screen) { return prepare(screen) && focus != null ? focus.screen : screen; }

    /** Diagnostic coordinates are converted back to the host screen's coordinate space. */
    public JsonObject diagnostics() {
        var result = new JsonObject();
        result.addProperty("selectedAgent", selected);
        result.addProperty("draft", chat != null && chat.draft());
        var widgets = new JsonArray();
        if (prepare(Minecraft.getInstance().screen)) {
            if (left != null) describe(left, "sidebar", result, widgets);
            if (right != null) describe(right, "chat", result, widgets);
            if (settings != null) describe(settings, "settings", result, widgets);
            if (formPane != null) describe(formPane, "form", result, widgets);
            if (parent instanceof AgentWorkspaceScreen) {
                result.addProperty("sidebarCollapsed", TooManyAgentsClientSettings.get().sidebarCollapsed());
                if (chat != null) result.add("pointing", chat.pointingContext());
            }
            if (parent instanceof AbstractContainerScreen<?> inventory) {
                var bounds = new JsonObject();
                bounds.addProperty("x", inventory.getGuiLeft()); bounds.addProperty("y", inventory.getGuiTop());
                bounds.addProperty("width", inventory.getXSize()); bounds.addProperty("height", inventory.getYSize());
                var carried = inventory.getMenu().getCarried();
                bounds.addProperty("carriedCount", carried.getCount());
                var slots = new JsonArray();
                for (var slot : inventory.getMenu().slots) {
                    var row = new JsonObject();
                    row.addProperty("index", slot.index);
                    row.addProperty("x", inventory.getGuiLeft() + slot.x + 8);
                    row.addProperty("y", inventory.getGuiTop() + slot.y + 8);
                    row.addProperty("count", slot.getItem().getCount());
                    row.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString());
                    slots.add(row);
                }
                bounds.add("slots", slots);
                result.add("inventory", bounds);
            }
        }
        result.add("widgets", widgets);
        return result;
    }

    private void describe(Pane pane, String name, JsonObject result, JsonArray widgets) {
        var bounds = new JsonObject();
        bounds.addProperty("x", pane.x); bounds.addProperty("y", pane.y);
        bounds.addProperty("width", pane.width); bounds.addProperty("height", pane.height); bounds.addProperty("scale", pane.scale);
        result.add(name, bounds);
        describeWidgets(pane.screen, pane, name, widgets);
    }

    private void describeWidgets(ContainerEventHandler handler, Pane pane, String name, JsonArray widgets) {
        for (var child : handler.children()) {
            if (child instanceof AbstractWidget widget) {
                var row = new JsonObject();
                row.addProperty("panel", name); row.addProperty("type", widget.getClass().getSimpleName());
                row.addProperty("label", widget.getMessage().getString());
                row.addProperty("x", pane.x + widget.getX() * pane.scale); row.addProperty("y", pane.y + widget.getY() * pane.scale);
                row.addProperty("width", widget.getWidth() * pane.scale); row.addProperty("height", widget.getHeight() * pane.scale);
                row.addProperty("active", widget.active); row.addProperty("visible", widget.visible); row.addProperty("focused", widget.isFocused());
                if (widget instanceof EditBox edit) row.addProperty("text", edit.getValue());
                if (widget instanceof MultiLineEditBox edit) row.addProperty("text", edit.getValue());
                widgets.add(row);
            }
            if (child instanceof ContainerEventHandler nested) describeWidgets(nested, pane, name, widgets);
        }
    }

    private static final class PanelGraphics extends GuiGraphics {
        private final Pane pane;
        PanelGraphics(GuiGraphics parent, Pane pane) {
            super(Minecraft.getInstance(), Minecraft.getInstance().renderBuffers().bufferSource());
            this.pane = pane;
            pose().last().pose().set(parent.pose().last().pose());
            pose().translate(pane.x, pane.y, 400);
            pose().scale(pane.scale, pane.scale, 1);
        }
        @Override public int guiWidth() { return pane.screen.width; }
        @Override public int guiHeight() { return pane.screen.height; }
        @Override public void enableScissor(int x, int y, int right, int bottom) {
            super.enableScissor((int)Math.floor(pane.x + x * pane.scale), (int)Math.floor(pane.y + y * pane.scale),
                (int)Math.ceil(pane.x + right * pane.scale), (int)Math.ceil(pane.y + bottom * pane.scale));
        }
        @Override public boolean containsPointInScissor(int x, int y) {
            return super.containsPointInScissor((int)(pane.x + x * pane.scale), (int)(pane.y + y * pane.scale));
        }
    }
}
