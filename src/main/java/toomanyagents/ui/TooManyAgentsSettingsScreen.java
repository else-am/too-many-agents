package toomanyagents.ui;

import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import toomanyagents.TooManyAgentsClientSettings;

/** Installation-wide preferences; each change saves immediately. */
public final class TooManyAgentsSettingsScreen extends SettingsFormScreen {
    private final Screen parent;
    private final AgentUiAccess access;
    private final TooManyAgentsClientSettings settings = TooManyAgentsClientSettings.get();

    public TooManyAgentsSettingsScreen(Screen parent) { this(parent, null); }
    /** With access (from inside a world), the screen also links to this world's archive. */
    public TooManyAgentsSettingsScreen(Screen parent, AgentUiAccess access) {
        super(Component.literal("Too Many Agents settings"));
        this.parent = parent;
        this.access = access;
    }

    /** In a world, the mod settings open beside the agent sidebar instead of on their own. */
    public static Screen open(Screen parent) {
        var client = Minecraft.getInstance();
        return client.level != null && client.hasSingleplayerServer() ? AgentWorkspaceScreen.modSettings() : new TooManyAgentsSettingsScreen(parent);
    }

    @Override protected String heading() { return "Mod settings"; }

    @Override protected void init() {
        begin();
        section("Notifications");
        toggle("Notification sound", settings.notificationSound(), v -> { settings.setNotificationSound(v); rebuildForm(); }, true)
            .setTooltip(Tooltip.create(Component.literal("A quiet sound for a new unread reply or request for attention.")));
        toggle("Agent messages in chat", settings.showAgentCommunication(), v -> { settings.setShowAgentCommunication(v); rebuildForm(); }, true)
            .setTooltip(Tooltip.create(Component.literal("Show short summaries of delivered agent messages. Full messages stay in their conversations. This does not change who can communicate.")));
        section("Display");
        var scales = new ArrayList<Choice>();
        scales.add(new Choice("0", "Same as game"));
        for (int scale = 1; scale <= ScreenScale.max(); scale++) scales.add(new Choice(String.valueOf(scale), String.valueOf(scale)));
        choice("Agent screen scale", String.valueOf(Math.min(settings.screenScale(), ScreenScale.max())), scales,
            v -> { settings.setScreenScale(Integer.parseInt(v)); rebuildForm(); }, true)
            .setTooltip(Tooltip.create(Component.literal("GUI scale for the agent sidebar, chat and settings. Agent inventories use the game's scale.")));
        section("More");
        action("Providers", "Agent providers…", () -> minecraft.setScreen(new ProviderSettingsScreen(back())), true);
        action("Key binds", "Minecraft key binds…", () -> minecraft.setScreen(new KeyBindsScreen(back(), minecraft.options)), true);
        if (access != null) action("Archive", "Archive…", () -> minecraft.setScreen(new ArchiveScreen(access, back())), true);
        feedback = settings.error();
        done();
    }

    // Linked screens return to whatever holds this form.
    private Screen back() { return docked() ? minecraft.screen : this; }

    @Override public void onClose() { leave(parent); }
}
