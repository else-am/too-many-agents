package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import toomanyagents.ProjectColor;
import toomanyagents.TooManyAgentsClientSettings;

/** An inventory companion; the host owns its position, scale, and input routing. */
public final class InventoryAgentSidebar extends Screen {
    static final int DEFAULT_WIDTH = 260, CARD_MARGINS = 31;
    private static final int LIST_TOP = 39;
    private static final int ROW_HEIGHT = AgentCard.HEIGHT + 11;
    private final AgentUiAccess access;
    private final Screen parent;
    private final Consumer<String> select;
    private final Consumer<String> newAgent;
    private final Consumer<String> editProject;
    private final Runnable openSettings;
    private final Set<String> collapsed = new HashSet<>();
    private final Map<String, JsonObject> projects = new LinkedHashMap<>();
    private final AgentPortraits portraits = new AgentPortraits();
    private final List<SidebarButton> rows = new ArrayList<>();
    private List<Entry> entries = List.of();
    private String selected = "", structure = "";
    private boolean revealSelected;
    private int scroll, contentHeight;
    private long projectsReadMs, hoverStartedNs;
    private String hoveredAgent = "";
    private Button providerButton, usageButton;
    private final List<Button> providerChoices = new ArrayList<>();
    private boolean providerMenu;
    private JsonObject catalog = new JsonObject();
    private JsonObject backendConfig = new JsonObject();
    private boolean catalogRequested, catalogLoaded, providerLoading, providerSaving;
    private long providerRequest;
    private String providerError = "";
    private ProviderUsagePopup usagePopup;

    /** editProject receives a project id, or "" to create one. */
    public InventoryAgentSidebar(AgentUiAccess access, Screen parent, Consumer<String> select, Consumer<String> newAgent, Consumer<String> editProject, Runnable openSettings) {
        super(Component.literal("Agents"));
        this.access = access;
        this.parent = parent;
        this.select = select;
        this.newAgent = newAgent;
        this.editProject = editProject;
        this.openSettings = openSettings;
    }

    public void selected(String id) {
        String next = id == null ? "" : id;
        if (!next.equals(selected)) {
            revealSelected = !next.isBlank();
            for (var value : access.list()) {
                var agent = value.getAsJsonObject();
                if (!text(agent, "id").equals(next)) continue;
                break;
            }
        }
        selected = next;
    }
    void closePortraits() { portraits.close(); }

    @Override protected void init() {
        portraits.close();
        structure = "";
        projectsReadMs = 0;
        hoveredAgent = "";
        if (usagePopup == null) usagePopup = new ProviderUsagePopup(access);
        rebuild();
        refresh();
        loadProviders(false);
    }

    private void loadProviders(boolean refresh) {
        if (providerLoading || providerSaving || catalogRequested && !refresh) return;
        catalogRequested = true;
        providerLoading = true;
        providerError = "";
        long request = ++providerRequest;
        var requestWorld = minecraft.level;
        updateProviderButton();
        // This sidebar is embedded, so Screen.screenExecutor would discard the result.
        var providers = catalogLoaded ? java.util.concurrent.CompletableFuture.completedFuture(catalog) : access.catalog();
        providers.thenCombine(access.backendConfig(), (providerCatalog, config) -> {
            var result = new JsonObject();
            result.add("catalog", providerCatalog);
            result.add("config", config);
            return result;
        }).whenComplete((result, failure) -> minecraft.execute(() -> {
            if (request != providerRequest) return;
            providerLoading = false;
            if (minecraft.level != requestWorld || minecraft.screen != parent) { catalogRequested = false; return; }
            if (failure != null) {
                catalogRequested = false;
                catalogLoaded = false;
                setProviderMenu(false);
                reportProviderError("Could not load BB provider defaults: " + AgentModels.error(failure) + ". Click to retry.");
                return;
            }
            boolean wasOpen = providerMenu;
            catalog = result.getAsJsonObject("catalog");
            catalogLoaded = true;
            backendConfig = result.getAsJsonObject("config");
            rebuild();
            setProviderMenu(wasOpen);
        }));
    }

    private int listBottom() { return height - 47; }

    private boolean sidebarCollapsed() { return TooManyAgentsClientSettings.get().sidebarCollapsed(); }

    private void rebuild() {
        clearWidgets();
        rows.clear();
        providerChoices.clear();
        providerMenu = false;
        var toggle = addRenderableWidget(new Button(7, 7, 18, 18,
            Component.literal(sidebarCollapsed() ? "Expand sidebar" : "Collapse sidebar"), button ->
                TooManyAgentsClientSettings.get().setSidebarCollapsed(!sidebarCollapsed()), message -> message.get()) {
            @Override protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
                if (isHoveredOrFocused()) g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF363636);
                int color = isHoveredOrFocused() ? 0xFFEEEEEE : 0xFFB3B3B3;
                int x = getX() + 4, y = getY() + 5;
                g.fill(x, y, x + 10, y + 1, color);
                g.fill(x, y + 8, x + 10, y + 9, color);
                g.fill(x, y + 1, x + 1, y + 8, color);
                g.fill(x + 9, y + 1, x + 10, y + 8, color);
                g.fill(x + 3, y + 1, x + 4, y + 8, color);
                if (!sidebarCollapsed()) g.fill(x + 1, y + 1, x + 3, y + 8, 0x88777777);
            }
        });
        toggle.setTooltip(net.minecraft.client.gui.components.Tooltip.create(toggle.getMessage()));
        if (sidebarCollapsed()) return;
        providerButton = addRenderableWidget(new Button(32, 7, 24, 20, Component.literal(providerName(defaultProvider())),
            button -> {
                boolean open = !providerMenu;
                setProviderMenu(open);
                if (open) loadProviders(true);
                usagePopup.close();
            }, message -> message.get()) {
            @Override protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
                g.blitSprite(ResourceLocation.withDefaultNamespace(isHoveredOrFocused() ? "widget/button_highlighted" : "widget/button"), getX(), getY(), getWidth(), getHeight());
                String provider = defaultProvider();
                if (provider.isBlank()) g.drawString(font, "BB", getX() + (getWidth() - font.width("BB")) / 2, getY() + 6, 0xFFFFFFFF, false);
                else ProviderIcon.render(g, provider, getX() + (getWidth() - ProviderIcon.SIZE) / 2, getY() + 6);
            }
        });
        updateProviderButton();
        addRenderableWidget(Button.builder(Component.literal("+ Project"), button ->
            editProject.accept("")).bounds(62, 7, (width - 79) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Thread"), button -> startAgent(""))
            .bounds(68 + (width - 79) / 2, 7, (width - 79) / 2, 20).build());
        int longest=0;
        for(var item:AgentModels.array(catalog,"providers"))longest=Math.max(longest,font.width(AgentModels.text(item.getAsJsonObject(),"displayName")));
        int choiceWidth = Math.min(width - 43, longest + font.width("✓ ") + ProviderIcon.SIZE + 24);
        var providerIds = new ArrayList<String>();
        for (var item : AgentModels.array(catalog,"providers")) {
            var info=item.getAsJsonObject();
            if(!info.has("available")||!info.get("available").getAsBoolean())continue;
            providerIds.add(AgentModels.text(info,"id"));
        }
        for (String provider : providerIds) {
            var choice = new Button(36, 33 + providerChoices.size() * 24, choiceWidth - 8, 22,
                Component.literal(providerName(provider)), button -> saveDefaultProvider(provider), message -> message.get()) {
                @Override protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
                    if (isHoveredOrFocused()) g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF454545);
                    int x = getX() + 6, y = getY() + 6;
                    if (provider.equals(defaultProvider())) g.drawString(font, "✓", x, y, 0xFFFFFFFF);
                    x += font.width("✓ ");
                    x += ProviderIcon.render(g, provider, x, y) + 4;
                    g.drawString(font, getMessage(), x, y, 0xFFFFFFFF);
                }
            };
            choice.visible = false;
            choice.active = !providerLoading && !providerSaving;
            providerChoices.add(addWidget(choice));
        }
        for (Entry entry : entries) {
            var row = addRenderableWidget(new SidebarButton(entry, width));
            rows.add(row);
            if (entry.agent == null) {
                row.add = addRenderableWidget(new ProjectActionButton(row, false));
                row.edit = addRenderableWidget(new ProjectActionButton(row, true));
            }
        }
        int footerY = height - 33;
        int footerWidth = (width - 26) / 2;
        addRenderableWidget(footer(10, footerY, footerWidth, "Settings…", openSettings));
        usageButton = addRenderableWidget(footer(16 + footerWidth, footerY, footerWidth, "Usage", () -> {
            setProviderMenu(false);
            usagePopup.toggle();
        }));
        layout();
    }

    static String defaultProvider(JsonObject config) {
        return text(AgentModels.object(config, "generalSettings"), "defaultProviderId");
    }

    private String defaultProvider() { return defaultProvider(backendConfig); }

    void reportProviderError(String message) {
        providerError = message;
        updateProviderButton();
    }

    private void updateProviderButton() {
        if (providerButton == null) return;
        providerButton.setMessage(Component.literal(providerName(defaultProvider())));
        String tooltip = !providerError.isBlank() ? providerError : providerSaving ? "Saving BB default provider…"
            : providerLoading ? "Loading BB provider defaults…" : "BB default provider for new chats";
        providerButton.setTooltip(Tooltip.create(Component.literal(tooltip)));
        for (var choice : providerChoices) choice.active = !providerLoading && !providerSaving;
    }

    private void saveDefaultProvider(String provider) {
        if (providerLoading || providerSaving) return;
        providerSaving = true;
        providerError = "";
        long request = ++providerRequest;
        var requestWorld = minecraft.level;
        setProviderMenu(false);
        updateProviderButton();
        access.setDefaultProvider(provider).whenComplete((config, failure) -> minecraft.execute(() -> {
            if (request != providerRequest) return;
            providerSaving = false;
            if (minecraft.level != requestWorld || minecraft.screen != parent) { catalogRequested = false; return; }
            if (failure != null) {
                reportProviderError("Could not save BB default provider: " + AgentModels.error(failure) + ". Click to retry.");
                return;
            }
            backendConfig = config;
            updateProviderButton();
        }));
    }
    private String providerName(String provider) {
        if (provider.isBlank()) return "Choose default provider";
        for(var item:AgentModels.array(catalog,"providers")) {
            var info=item.getAsJsonObject();
            if(AgentModels.text(info,"id").equals(provider))return AgentModels.text(info,"displayName");
        }
        return provider;
    }
    public boolean pickerOpen() { return providerMenu || usagePopup != null && usagePopup.visible(); }
    private void setProviderMenu(boolean open) {
        boolean restoreFocus = providerMenu && !open && getFocused() != null;
        providerMenu = open;
        for (var choice : providerChoices) choice.visible = open;
        if (restoreFocus) setFocused(providerButton);
    }

    private void startAgent(String projectId) {
        newAgent.accept(projectId);
    }

    private Button footer(int x, int y, int w, String label, Runnable action) {
        return new Button(x, y, w, 22, Component.literal(label), button -> action.run(), message -> message.get()) {
            @Override protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
                if (isHoveredOrFocused()) g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF363636);
                g.drawString(font, getMessage(), getX() + 5, getY() + 7, label.startsWith("+") ? 0xDDDDDD : 0xB5B5B5, false);
            }
        };
    }

    private String parent(JsonObject agent) {
        String parentThread = text(agent, "parentThreadId");
        if (parentThread.isBlank()) return "";
        for (var row : access.list()) {
            var candidate = row.getAsJsonObject();
            if (text(candidate, "threadId").equals(parentThread)) return text(candidate, "id");
        }
        return "";
    }

    @Override public void tick() { refresh(); }

    private void refresh() {
        if (sidebarCollapsed()) return;
        for (var choice : providerChoices) choice.visible = providerMenu;
        long now = System.currentTimeMillis();
        if (now - projectsReadMs >= 1_000) {
            projectsReadMs = now;
            projects.clear();
            JsonObject data = access.projects();
            for (var value : AgentModels.array(data, "projects")) {
                var project = value.getAsJsonObject();
                String id = text(project, "id");
                projects.put(id, project);
            }
        }
        var visible = new LinkedHashMap<String, JsonObject>();
        for (var value : access.list()) {
            var agent = value.getAsJsonObject();
            // Only this world's active agents; archived ones live in Mod settings → Archive.
            if (flag(agent, "conversationArchived") || !flag(agent, "currentWorld") || Set.of("removed","archived").contains(text(agent, "lifecycle"))) continue;
            visible.put(text(agent, "id"), agent);
        }
        var groups = new LinkedHashMap<String, List<JsonObject>>();
        for (var agent : visible.values()) {
            var root = agent;
            var ancestors = new HashSet<String>();
            while (visible.containsKey(parent(root)) && ancestors.add(text(root, "id")))
                root = visible.get(parent(root));
            String projectId = text(root, "projectId");
            if (projectId.isBlank()) projectId = "proj_personal";
            groups.computeIfAbsent(projectId, key -> new ArrayList<>()).add(agent);
        }
        for (var project : projects.values()) {
            if (text(project, "kind").equals("world"))
                groups.computeIfAbsent(text(project, "id"), key -> new ArrayList<>());
        }
        var projectIds = new ArrayList<>(groups.keySet());
        // This world's project first and no project last, around BB's projects.
        projectIds.sort(Comparator.comparing((String id) -> text(projects.getOrDefault(id, new JsonObject()), "kind").equals("world") ? 0 : text(projects.getOrDefault(id, new JsonObject()), "kind").equals("personal") ? 2 : 1)
            .thenComparing(id -> projectName(id, groups.get(id)), String.CASE_INSENSITIVE_ORDER));
        var next = new ArrayList<Entry>();
        var nextStructure = new StringBuilder();
        for (String id : projectIds) {
            var agents = groups.get(id);
            if (revealSelected && agents.stream().anyMatch(agent -> text(agent, "id").equals(selected))) collapsed.remove(id);
            next.add(new Entry(id, projectName(id, agents), null, 0));
            nextStructure.append("group:").append(id).append(';');
            if (collapsed.contains(id)) continue;
            var members = new HashSet<String>();
            var children = new LinkedHashMap<String, List<JsonObject>>();
            for (var agent : agents) {
                members.add(text(agent, "id"));
                children.computeIfAbsent(parent(agent), key -> new ArrayList<>()).add(agent);
            }
            var added = new HashSet<String>();
            for (var agent : agents) if (!members.contains(parent(agent)))
                appendFamily(id, agent, 0, children, added, next, nextStructure);
            // Keep every agent reachable even if an incomplete snapshot has a broken parent link.
            for (var agent : agents) appendFamily(id, agent, 0, children, added, next, nextStructure);
        }
        entries = next;
        String signature = nextStructure.toString();
        if (!signature.equals(structure) || rows.size() != entries.size()) {
            structure = signature;
            rebuild();
        } else {
            for (int i = 0; i < rows.size(); i++) rows.get(i).entry = entries.get(i);
        }
        layout();
    }

    private void appendFamily(String projectId, JsonObject agent, int depth, Map<String, List<JsonObject>> children,
                              Set<String> added, List<Entry> next, StringBuilder signature) {
        String id = text(agent, "id");
        if (!added.add(id)) return;
        next.add(new Entry(projectId, "", agent, depth));
        signature.append("agent:").append(id).append(':').append(depth).append(';');
        for (var child : children.getOrDefault(id, List.of()))
            appendFamily(projectId, child, depth + 1, children, added, next, signature);
    }

    private String projectName(String id, List<JsonObject> agents) {
        String name = projects.containsKey(id) ? text(projects.get(id), "name")
            : agents.isEmpty() ? "" : text(agents.getFirst(), "projectName");
        return name.isBlank() ? (id.isBlank() || id.equals("proj_personal") ? "No project" : "Missing project") : name;
    }

    private String projectTooltip(Entry entry) {
        var project = projects.get(entry.projectId);
        if (project == null) return entry.name;
        String folder = text(project, "folder");
        return folder.isBlank() ? entry.name : entry.name + "\n\n" + folder;
    }

    private void layout() {
        contentHeight = entries.stream().mapToInt(Entry::height).sum();
        if (revealSelected) {
            int top = 0, viewport = listBottom() - LIST_TOP;
            for (Entry entry : entries) {
                if (entry.agent != null && text(entry.agent, "id").equals(selected)) {
                    if (top < scroll) scroll = top;
                    else if (top + entry.height() > scroll + viewport) scroll = top + entry.height() - viewport;
                    revealSelected = false;
                    break;
                }
                top += entry.height();
            }
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - (listBottom() - LIST_TOP)));
        int y = LIST_TOP - scroll;
        for (SidebarButton button : rows) {
            button.rowTop = y;
            int visibleTop = Math.max(LIST_TOP, y), visibleBottom = Math.min(listBottom(), y + button.entry.height());
            button.setY(visibleTop);
            button.setHeight(Math.max(0, visibleBottom - visibleTop));
            button.visible = visibleBottom > visibleTop;
            button.active = button.visible;
            button.setMessage(Component.literal(button.entry.agent == null
                ? button.entry.name + " - " + (collapsed.contains(button.entry.projectId) ? "Expand" : "Collapse")
                : details(button.entry.agent)));
            if (button.add != null) {
                button.add.layout();
                button.edit.layout();
            }
            y += button.entry.height();
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (sidebarCollapsed()) return false;
        if (usagePopup.scroll(x, y, vertical)) return true;
        if (providerMenu) return true;
        if (y >= LIST_TOP && y < listBottom() && vertical != 0) {
            hoveredAgent = "";
            scroll -= (int)Math.round(ROW_HEIGHT * vertical);
            layout();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    // The host has a different scale. Keep widget tooltips on this panel, as the chat does.
    private Tooltip renderWidgets(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var tooltips = new LinkedHashMap<AbstractWidget, Tooltip>();
        Tooltip hovered = null;
        for (var child : children()) {
            if (!(child instanceof AbstractWidget widget) || widget.getTooltip() == null) continue;
            var tooltip = widget.getTooltip();
            tooltips.put(widget, tooltip);
            widget.setTooltip(null);
            if (widget.visible && (widget.isMouseOver(mouseX, mouseY)
                    || widget.isFocused() && minecraft.getLastInputType().isKeyboard())) hovered = tooltip;
        }
        try { super.render(g, mouseX, mouseY, partialTick); }
        finally { tooltips.forEach(AbstractWidget::setTooltip); }
        return hovered;
    }

    private void widgetTooltip(GuiGraphics g, Tooltip tooltip, int mouseX, int mouseY) {
        if (tooltip == null) return;
        g.renderTooltip(font, tooltip.toCharSequence(minecraft),
            (screenWidth, screenHeight, x, y, tooltipWidth, tooltipHeight) -> new org.joml.Vector2i(
                Math.max(4, Math.min(x + 10, width - tooltipWidth - 4)),
                Math.max(4, Math.min(y + 10, height - tooltipHeight - 4))), mouseX, mouseY);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (sidebarCollapsed()) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        g.fill(11, height - 42, width - 11, height - 41, 0xFF353535);
        Tooltip tooltip = renderWidgets(g, mouseX, mouseY, partialTick);
        if (entries.isEmpty()) g.drawString(font, "No projects here", 14, LIST_TOP + 12, 0x949494, false);
        int viewport = listBottom() - LIST_TOP;
        if (contentHeight > viewport) {
            int thumb = Math.max(14, viewport * viewport / contentHeight);
            int thumbY = LIST_TOP + scroll * (viewport - thumb) / (contentHeight - viewport);
            g.fill(width - 5, LIST_TOP, width - 3, listBottom(), 0xFF2B2B2B);
            g.fill(width - 5, thumbY, width - 3, thumbY + thumb, 0xFF808080);
        }
        String hoverKey = "", hoverText = "";
        for (SidebarButton button : rows) {
            if (button.visible && button.isMouseOver(mouseX, mouseY)) {
                hoverKey = button.entry.agent == null ? "project:" + button.entry.projectId : text(button.entry.agent, "id");
                hoverText = button.entry.agent == null ? projectTooltip(button.entry) : "";
                if (button.entry.agent != null) {
                    int inset = indent(button.entry, width);
                    float iconX = 13 + inset + AgentCard.worktreeOffset(button.entry.agent, width - CARD_MARGINS - inset);
                    int iconY = button.rowTop + 5 + AgentCard.CONTEXT_Y;
                    String path = AgentCard.worktreePath(button.entry.agent);
                    if (!path.isBlank() && mouseX >= iconX && mouseX < iconX + 9 * AgentCard.DETAIL && mouseY >= iconY && mouseY < iconY + 9 * AgentCard.DETAIL) {
                        hoverKey += ":worktree";
                        hoverText = "worktree: " + path;
                    }
                }
                break;
            }
            if (button.add == null) continue;
            for (var action : List.of(button.add, button.edit)) {
                if (!action.visible || !action.isMouseOver(mouseX, mouseY)) continue;
                hoverKey = (action.edit ? "edit:" : "add:") + button.entry.projectId;
                hoverText = action.getMessage().getString();
            }
        }
        long now = System.nanoTime();
        if (!hoverKey.equals(hoveredAgent)) {
            hoveredAgent = hoverKey;
            hoverStartedNs = now;
        }
        if (!providerMenu && !usagePopup.visible() && !hoverText.isBlank() && now - hoverStartedNs >= 500_000_000L) {
            g.renderTooltip(font, font.split(Component.literal(hoverText), Math.min(220, width - 16)),
                (screenWidth, screenHeight, x, y, tooltipWidth, tooltipHeight) -> new org.joml.Vector2i(
                    Math.max(6, Math.min(x + 10, width - tooltipWidth - 6)),
                    Math.max(6, Math.min(y + 10, height - tooltipHeight - 6))), mouseX, mouseY);
        }
        if (providerMenu && !providerChoices.isEmpty()) {
            int menuWidth = providerChoices.getFirst().getWidth() + 8;
            int menuHeight = providerChoices.size() * 24 + 8;
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.fill(32, 29, 32 + menuWidth, 29 + menuHeight, 0xFF202020);
            g.renderOutline(32, 29, menuWidth, menuHeight, 0xFF777777);
            for (var choice : providerChoices) choice.render(g, mouseX, mouseY, partialTick);
            g.pose().popPose();
        } else if (!usagePopup.visible()) widgetTooltip(g, tooltip, mouseX, mouseY);
        usagePopup.render(g, font, width, height, mouseX, mouseY, !providerMenu && usageButton.isMouseOver(mouseX, mouseY));
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (sidebarCollapsed()) return super.mouseClicked(x, y, button);
        if (providerMenu) {
            for (var choice : providerChoices) if (choice.mouseClicked(x, y, button)) return true;
            if (!providerChoices.isEmpty() && x >= 32 && x < 40 + providerChoices.getFirst().getWidth()
                    && y >= 29 && y < 37 + providerChoices.size() * 24) return true;
            if (!providerButton.isMouseOver(x, y)) setProviderMenu(false);
        }
        if (usagePopup.contains(x, y)) return true;
        if (!usageButton.isMouseOver(x, y)) usagePopup.close();
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && (providerMenu || usagePopup.visible())) {
            setProviderMenu(false); usagePopup.close(); return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private record Entry(String projectId, String name, JsonObject agent, int depth) {
        int height() { return agent == null ? 24 : ROW_HEIGHT; }
    }

    private static int indent(Entry entry, int width) {
        return Math.min(entry.depth * 14, Math.max(0, width - 160));
    }

    private final class SidebarButton extends Button {
        private Entry entry;
        private int rowTop;
        private ProjectActionButton add, edit;

        SidebarButton(Entry entry, int panelWidth) {
            super(7 + indent(entry, panelWidth), 0, panelWidth - (entry.agent == null ? 59 : 17) - indent(entry, panelWidth), entry.height(), Component.empty(), button -> {}, message -> message.get());
            this.entry = entry;
        }

        @Override public void onPress() {
            if (entry.agent == null) {
                if (!collapsed.add(entry.projectId)) collapsed.remove(entry.projectId);
                refresh();
            } else {
                selected(text(entry.agent, "id"));
                select.accept(selected);
            }
        }

        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int panelWidth = InventoryAgentSidebar.this.width;
            g.enableScissor(5, LIST_TOP, panelWidth - 6, listBottom());
            try {
                if (entry.agent == null) {
                    if (isHoveredOrFocused()) g.fill(getX(), rowTop + 2, getX() + getWidth(), rowTop + 22, 0xFF2D2D2D);
                    drawChevron(g, 13, rowTop + 9, !collapsed.contains(entry.projectId), 0x9B9B9B);
                    int labelX = 35;
                    var project = projects.get(entry.projectId);
                    int projectColor = ProjectColor.rgb(project == null ? ProjectColor.forId(entry.projectId) : ProjectColor.of(project));
                    g.fill(25, rowTop + 9, 30, rowTop + 14, 0xFF000000 | projectColor);
                    if (project != null && text(project, "kind").equals("world")) {
                        g.renderItem(new ItemStack(Items.GRASS_BLOCK), labelX, rowTop + 4);
                        labelX += 20;
                    }
                    g.drawString(font, ellipsis(entry.name, getX() + getWidth() - labelX - 6), labelX, rowTop + 8, 0xC3C3C3, false);
                    return;
                }
                var agent = entry.agent;
                boolean chosen = text(agent, "id").equals(selected);
                if (chosen || isHoveredOrFocused()) g.fill(getX(), rowTop + 1, getX() + getWidth(), rowTop + ROW_HEIGHT - 1, AgentCard.background());
                int inset = indent(entry, panelWidth);
                if (entry.depth > 0) {
                    g.fill(getX() - 7, rowTop, getX() - 6, rowTop + 21, 0xFF6E6E6E);
                    g.fill(getX() - 6, rowTop + 20, getX() - 2, rowTop + 21, 0xFF6E6E6E);
                }
                portraits.render(g, text(agent, "bodyType"), 13 + inset, rowTop + 4, 10);
                AgentCard.render(g, agent, 13 + inset, rowTop + 5, panelWidth - CARD_MARGINS - inset, 14);
            } finally {
                g.disableScissor();
            }
        }
    }

    private final class ProjectActionButton extends Button {
        private final SidebarButton row;
        private final boolean edit;

        ProjectActionButton(SidebarButton row, boolean edit) {
            super(InventoryAgentSidebar.this.width - (edit ? 50 : 30), 0, 18, 18, Component.empty(), button -> {}, message -> message.get());
            this.row = row;
            this.edit = edit;
        }

        private String projectId() {
            return row.entry.projectId;
        }

        void layout() {
            int top = row.rowTop + 3;
            int visibleTop = Math.max(LIST_TOP, top), visibleBottom = Math.min(listBottom(), top + 18);
            setY(visibleTop);
            setHeight(Math.max(0, visibleBottom - visibleTop));
            visible = visibleBottom > visibleTop;
            active = visible && (!edit || projects.containsKey(projectId()));
            var label = Component.literal((edit ? "Edit project " : "New agent in ") + row.entry.name);
            setMessage(label);
        }

        @Override public void onPress() {
            if (edit) editProject.accept(projectId());
            else startAgent(projectId());
        }

        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.enableScissor(5, LIST_TOP, InventoryAgentSidebar.this.width - 6, listBottom());
            try {
                int y = row.rowTop + 3;
                if (active && isHoveredOrFocused()) g.fill(getX(), y, getX() + getWidth(), y + 18, 0xFF383838);
                String icon = edit ? "…" : "+";
                g.drawString(font, icon, getX() + (getWidth() - font.width(icon)) / 2, y + 5,
                    !active ? 0x5E5E5E : edit ? 0xACACAC : 0xCCCCCC, false);
            } finally {
                g.disableScissor();
            }
        }
    }

    private String ellipsis(String value, int available) {
        if (font.width(value) <= available) return value;
        return font.plainSubstrByWidth(value, Math.max(0, available - font.width("…"))) + "…";
    }

    private static void drawChevron(GuiGraphics g, int x, int y, boolean expanded, int color) {
        int ink = 0xFF000000 | color;
        if (expanded) {
            g.fill(x, y, x + 1, y + 1, ink);
            g.fill(x + 1, y + 1, x + 2, y + 2, ink);
            g.fill(x + 2, y + 2, x + 3, y + 3, ink);
            g.fill(x + 3, y + 1, x + 4, y + 2, ink);
            g.fill(x + 4, y, x + 5, y + 1, ink);
        } else {
            g.fill(x + 1, y - 1, x + 2, y, ink);
            g.fill(x + 2, y, x + 3, y + 1, ink);
            g.fill(x + 3, y + 1, x + 4, y + 2, ink);
            g.fill(x + 2, y + 2, x + 3, y + 3, ink);
            g.fill(x + 1, y + 3, x + 2, y + 4, ink);
        }
    }

    private static String details(JsonObject agent) {
        String result = text(agent, "name");
        String task = text(agent, "taskTitle");
        if (!task.isBlank()) result += "\n" + task;
        String state = text(agent, "status").equals("disconnected") ? "BB disconnected"
            : flag(agent, "waitingForGame") ? "Waiting for game to resume"
            : flag(agent, "hasPendingInteraction") ? "Input needed"
            : text(agent, "status").equals("error") ? "Needs attention"
            : List.of("active", "pending", "starting", "stopping").contains(text(agent, "status")) ? "Working" : "Ready";
        result += "\n" + state;
        if (unread(agent)) result += " - Unread reply";
        if (!flag(agent, "currentWorld")) result += "\nOther world";
        return result;
    }

    private static boolean unread(JsonObject agent) { return AgentCard.unread(agent); }

    private static String text(JsonObject object, String key) { return AgentModels.text(object, key); }
    private static boolean flag(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}
    @Override public void onClose() { minecraft.setScreen(parent); }
}
