package toomanyagents.ui;

import net.minecraft.Util;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import toomanyagents.ManagedStorage;

/** This world's archived agents, each restorable, and the folder where archives and worktrees accumulate. */
public final class ArchiveScreen extends SettingsFormScreen {
    private final AgentUiAccess access;
    private final Screen parent;
    private String feedback = "";
    private boolean busy;

    public ArchiveScreen(AgentUiAccess access, Screen parent) {
        super(Component.literal("Archive"));
        this.access = access;
        this.parent = parent;
    }

    @Override protected void init() {
        begin();
        section("Archived agents");
        int count = 0;
        for (var value : access.list()) {
            var agent = value.getAsJsonObject();
            if (!flag(agent, "conversationArchived") || !flag(agent, "currentWorld")) continue;
            String title = AgentModels.text(agent, "taskTitle");
            String id = AgentModels.text(agent, "id");
            action(AgentModels.text(agent, "name") + (title.isBlank() ? "" : " - " + title), "Restore", () -> restore(id), !busy);
            count++;
        }
        if (count == 0) note("No archived agents in this world.");
        if (!feedback.isBlank()) note(feedback);
        section("Files");
        action(ManagedStorage.root().toString().replace(System.getProperty("user.home"), "~"), "Open folder",
            () -> Util.getPlatform().openFile(ManagedStorage.root().toFile()), true);
        done();
    }

    private void restore(String id) {
        busy = true;
        feedback = "Restoring…";
        rebuildForm();
        access.archiveConversation(id, false).whenComplete((done, failure) -> minecraft.execute(() -> {
            busy = false;
            feedback = failure == null ? "" : "Could not restore: " + AgentModels.error(failure);
            rebuildForm();
        }));
    }

    private static boolean flag(com.google.gson.JsonObject agent, String key) {
        return agent.has(key) && agent.get(key).isJsonPrimitive() && agent.get(key).getAsBoolean();
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
