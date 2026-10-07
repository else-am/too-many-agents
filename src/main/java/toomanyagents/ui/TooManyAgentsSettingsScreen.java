package toomanyagents.ui;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import toomanyagents.TooManyAgentsClientSettings;
import toomanyagents.BbSetup;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Installation-wide preferences; each change saves immediately. */
public final class TooManyAgentsSettingsScreen extends SettingsFormScreen {
    private final Screen parent;
    private final AgentUiAccess access;
    private final TooManyAgentsClientSettings settings = TooManyAgentsClientSettings.get();
    private final BbSetup setup = BbSetup.get();
    private BbSetup.View bb = setup.view();
    private String bbPath = BbSetup.locationLabel(bb.cli());
    private long bbPathApplyAt;
    private boolean details, choosing, confirmInstall;

    public TooManyAgentsSettingsScreen(Screen parent) { this(parent, null); }
    /** With access (from inside a world), the screen also links to this world's archive. */
    public TooManyAgentsSettingsScreen(Screen parent, AgentUiAccess access) {
        super(Component.literal("Too Many Agents settings"));
        this.parent = parent;
        this.access = access;
        setup.retry();
    }

    /** In a world, the mod settings open beside the agent sidebar instead of on their own. */
    public static Screen open(Screen parent) {
        var client = Minecraft.getInstance();
        return client.level != null && client.hasSingleplayerServer() ? AgentWorkspaceScreen.modSettings() : new TooManyAgentsSettingsScreen(parent);
    }

    @Override protected String heading() { return "Mod settings"; }

    @Override protected void init() {
        begin();
        bbConnection();
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
        action("Providers", "Agent providers…", () -> minecraft.setScreen(new ProviderSettingsScreen(back(), access)), true);
        action("Key binds", "Minecraft key binds…", () -> minecraft.setScreen(new KeyBindsScreen(back(), minecraft.options)), true);
        if (access != null) action("Archive", "Archive…", () -> minecraft.setScreen(new ArchiveScreen(access, back())), true);
        feedback = settings.error();
        done();
    }

    private void bbConnection() {
        section("BB connection");
        note(bb.message());
        String installLabel = setup.development() ? "Reload development plugin…" : "Reinstall bundled plugin…";
        boolean enabled = !bb.busy() && bb.action() != BbSetup.Action.WAIT;
        if (bb.instances().size() > 1) {
            choice("Use BB", bb.instanceId(), bb.instances().stream()
                .map(i -> new Choice(i.id(), i.label())).toList(), setup::select, enabled);
        } else if (bb.instances().size() == 1 && !bb.instanceId().equals(bb.instances().getFirst().id())) {
            // Keep the saved choice until the user deliberately switches installations.
            action("BB found", "Use detected BB", () -> setup.select(bb.instances().getFirst().id()), enabled);
        }
        if (bb.bbNotFound()) {
            note("Install and open BB, then return here. Minecraft will connect automatically.");
            action("", "Get BB…", () -> net.minecraft.Util.getPlatform().openUri(java.net.URI.create("https://getbb.app/")), true);
        } else if (bb.action() == BbSetup.Action.ALLOW_UPDATES) {
            note("Let this mod install and update its Minecraft plugin in BB.");
            action("", "Allow plugin installation and updates", () -> setup.automatic(true), enabled);
        } else if (bb.action() == BbSetup.Action.RESTORE && !confirmInstall) {
            action("", installLabel, () -> { confirmInstall = true; rebuildForm(); }, enabled);
        } else if (bb.action() == BbSetup.Action.RETRY) {
            action("", "Retry", setup::retry, enabled);
        }
        action("", details ? "Hide connection details" : "Connection details…", () -> { details = !details; rebuildForm(); }, true);
        if (details) {
            if (setup.development()) value("Plugin source", "This checkout (development)");
            else toggle("Plugin auto-updates", bb.automatic(), setup::automatic, enabled)
                .setTooltip(Tooltip.create(Component.literal("Allow this mod to install and update its Minecraft plugin in the selected BB.")));
            value("Mod version", BbSetup.version());
            value("Plugin version", bb.pluginVersion().isBlank() ? "Not connected" : bb.pluginVersion());
            bb.instances().stream().filter(i -> i.id().equals(bb.instanceId())).findFirst()
                .ifPresent(i -> value("BB data location", i.label()));
            if (!confirmInstall && bb.action() != BbSetup.Action.RESTORE)
                action("Repair", installLabel, () -> { confirmInstall = true; rebuildForm(); }, enabled && !bb.cli().isBlank());
        }
        if (confirmInstall) {
            note("Install the " + setup.pluginSourceLabel() + " " + BbSetup.version() + "? This replaces BB's Minecraft plugin, even if it is newer. Leave connected worlds first.");
            actions("", "Install plugin", () -> { confirmInstall = false; setup.install(); rebuildForm(); },
                "Cancel", () -> { confirmInstall = false; rebuildForm(); }, enabled);
        }
        if (details || bb.needsLocation()) {
            note("If detection fails, select your BB app or executable. You can also paste or drop its path here.");
            input("BB app location", bbPath, 4096, value -> {
                bbPath = value;
                bbPathApplyAt = System.currentTimeMillis() + 700;
            }, enabled && !choosing);
            action("", "Browse…", this::browseBb, enabled && !choosing);
        }
    }

    private void useBbPath() {
        bbPathApplyAt = 0;
        if (!bbPath.equals(BbSetup.locationLabel(setup.view().cli()))) setup.chooseCli(bbPath);
    }

    private void browseBb() {
        choosing = true; rebuildForm();
        CompletableFuture.supplyAsync(() -> TinyFileDialogs.tinyfd_openFileDialog("Choose BB app or executable", bbPath, null, null, false))
            .whenComplete((chosen, failure) -> minecraft.execute(() -> {
                choosing = false;
                if (chosen != null) { bbPath = chosen; useBbPath(); }
                if (failure != null) feedback = "Could not open the file picker. Paste the BB path instead.";
                rebuildForm();
            }));
    }

    @Override public void onFilesDrop(List<Path> files) {
        if ((details || bb.needsLocation()) && files.size() == 1 && !bb.busy()) {
            bbPath = files.getFirst().toString(); useBbPath(); rebuildForm();
        }
    }

    @Override public void tick() {
        super.tick();
        if (bbPathApplyAt != 0 && System.currentTimeMillis() >= bbPathApplyAt && !choosing && !setup.view().busy()) useBbPath();
        var latest = setup.view();
        if (!latest.equals(bb)) {
            boolean unchanged = bbPath.equals(BbSetup.locationLabel(bb.cli()));
            bb = latest;
            if (unchanged) bbPath = BbSetup.locationLabel(bb.cli());
            rebuildForm();
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    // Linked screens return to whatever holds this form.
    private Screen back() { return docked() ? minecraft.screen : this; }

    @Override public void onClose() {
        if (bbPathApplyAt != 0) useBbPath();
        leave(parent);
    }
}
