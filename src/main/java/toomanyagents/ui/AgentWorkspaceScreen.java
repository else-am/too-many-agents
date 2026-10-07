package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A game-scaled shell for the shared agent sidebar and conversation panes. */
public final class AgentWorkspaceScreen extends Screen {
    private final String initialAgentId;
    private final JsonObject pointing;
    private Consumer<List<Path>> fileDropHandler;
    private boolean modSettings;
    private boolean offeredSetup;

    public AgentWorkspaceScreen(String agentId, JsonObject pointing) {
        super(Component.literal("Agents"));
        initialAgentId = agentId == null ? "" : agentId;
        this.pointing = pointing == null ? null : pointing.deepCopy();
    }

    /** Opens with the mod settings in the chat's place. */
    public static AgentWorkspaceScreen modSettings() {
        var screen = new AgentWorkspaceScreen(null, null);
        screen.modSettings = true;
        return screen;
    }

    public boolean opensModSettings() { return modSettings; }
    @Override protected void init() {
        if (!offeredSetup && !modSettings && !toomanyagents.BbSetup.get().view().ready()) {
            offeredSetup = true;
            modSettings = true;
        }
    }
    public String initialAgentId() { return initialAgentId; }
    public JsonObject pointingContext() { return pointing == null ? null : pointing.deepCopy(); }
    public void setFileDropHandler(Consumer<List<Path>> handler) { fileDropHandler = handler; }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
    }

    @Override public void onFilesDrop(List<Path> paths) {
        if (fileDropHandler != null) fileDropHandler.accept(paths);
    }

    @Override public boolean isPauseScreen() { return false; }
}
