package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import toomanyagents.ProjectColor;

/** The sidebar's three-row agent card. The sidebar supplies the avatar and backdrop. */
final class AgentCard {
    static final int TASK_Y = 12, CONTEXT_Y = 23, HEIGHT = 30;
    static final float AGE = 0.7F, DETAIL = 0.775F;
    private static final int MUTED = 0xFFAAAAAA;
    private static final String[] WORKTREE = {
        "......###", ".......##", "......#.#", ".....#...", "#####....",
        ".....#...", "......#.#", ".......##", "......###"
    };

    static void render(GuiGraphics graphics, JsonObject agent, int x, int y, int width, int avatarSpace) {
        var ink = new Ink(graphics);
        var font = Minecraft.getInstance().font;
        int right = x + width;
        String age = age(agent);
        float ageX = right - font.width(age) * AGE;
        ink.text(age, ageX, y + 2, MUTED, AGE);
        var provider = ProviderIcon.textureFor(AgentModels.text(agent, "providerId"));
        int nameX = x + avatarSpace;
        String name = ellipsis(AgentModels.text(agent, "name"), (int)(ageX - 6 - nameX - (provider == null ? 0 : 13)));
        ink.text(name, nameX, y, 0xFFFFFFFF, 1);
        if (provider != null) ink.icon(provider, nameX + font.width(name) + 4, y - 1);
        ink.text(ellipsis(AgentModels.text(agent, "taskTitle"), (int)((width - 14) / DETAIL)), x, y + TASK_Y, MUTED, DETAIL);

        var environment = AgentModels.object(agent, "environment");
        var context = context(agent, width);
        String color = AgentModels.text(agent, "projectColor");
        if (!ProjectColor.valid(color)) color = ProjectColor.forId(AgentModels.text(agent, "projectId"));
        ink.text(context.project(), x, y + CONTEXT_Y, 0xFF000000 | ProjectColor.rgb(color), DETAIL);
        float branchX = x + context.gitX() * DETAIL;
        if (flag(environment, "isWorktree")) {
            for (int row = 0; row < 9; row++) for (int col = 0; col < 9; col++)
                if (WORKTREE[row].charAt(col) == '#') ink.fill(branchX + col * DETAIL, y + CONTEXT_Y + row * DETAIL,
                    branchX + (col + 1) * DETAIL, y + CONTEXT_Y + (row + 1) * DETAIL, MUTED);
            branchX += 13 * DETAIL;
        }
        if (flag(environment, "isGitRepo"))
            ink.text(ellipsis(AgentModels.text(environment, "branchName"), (int)((right - 14 - branchX) / DETAIL)),
                branchX, y + CONTEXT_Y, MUTED, DETAIL);
        signals(ink, agent, right - 8, y + (TASK_Y + CONTEXT_Y) / 2);
    }

    private record Context(String project, int gitX) {}

    private record Ink(GuiGraphics graphics) {
        void text(String text, float x, float y, int color, float scale) {
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.drawString(Minecraft.getInstance().font, text, 0, 0, color, false);
            graphics.pose().popPose();
        }
        void fill(float x0, float y0, float x1, float y1, int color) {
            graphics.pose().pushPose();
            graphics.pose().translate(x0, y0, 0);
            graphics.pose().scale(x1 - x0, y1 - y0, 1);
            graphics.fill(0, 0, 1, 1, color);
            graphics.pose().popPose();
        }
        void icon(ResourceLocation texture, int x, int y) { graphics.blit(texture, x, y, 0, 0, 9, 9, 9, 9); }
    }

    // Measure the untruncated rows, including the space reserved for age and status.
    static int fittedWidth(JsonObject agent, int maximum) {
        var font = Minecraft.getInstance().font;
        int provider = ProviderIcon.textureFor(AgentModels.text(agent, "providerId")) == null ? 0 : 13;
        float heading = textWidth(AgentModels.text(agent, "name")) + provider + 6 + font.width(age(agent)) * AGE;
        float task = textWidth(AgentModels.text(agent, "taskTitle")) * DETAIL + 14;
        int project = textWidth(AgentModels.text(agent, "projectName"));
        int git = gitWidth(AgentModels.object(agent, "environment"));
        float context = (project + (project > 0 && git > 0 ? 8 : 0) + git) * DETAIL + 14;
        return Math.min(maximum, Math.max(32, (int)Math.ceil(Math.max(heading, Math.max(task, context)))));
    }

    private static int gitWidth(JsonObject environment) {
        return (flag(environment, "isWorktree") ? 13 : 0)
            + (flag(environment, "isGitRepo") ? textWidth(AgentModels.text(environment, "branchName")) : 0);
    }

    private static int textWidth(String text) {
        return Minecraft.getInstance().font.width(text.replace('\n', ' ').replace('\r', ' '));
    }

    private static Context context(JsonObject agent, int width) {
        var environment = AgentModels.object(agent, "environment");
        boolean git = flag(environment, "isWorktree") || !AgentModels.text(environment, "branchName").isBlank() && flag(environment, "isGitRepo");
        int available = (int)((width - 14) / DETAIL);
        String projectName = AgentModels.text(agent, "projectName");
        boolean overflow = textWidth(projectName) + (projectName.isBlank() ? 0 : 8) + gitWidth(environment) > available;
        String project = ellipsis(projectName, git && overflow ? (int)(available * 0.45F) : available);
        int gitX = !git || project.isBlank() ? 0 : Minecraft.getInstance().font.width(project) + 8;
        return new Context(project, gitX);
    }

    static float worktreeOffset(JsonObject agent, int width) { return context(agent, width).gitX() * DETAIL; }

    static int background() { return Minecraft.getInstance().options.getBackgroundColor(0.25F); }

    static String worktreePath(JsonObject agent) {
        var environment = AgentModels.object(agent, "environment");
        return flag(environment, "isWorktree") ? AgentModels.text(environment, "path") : "";
    }

    static String ellipsis(String value, int available) {
        var font = Minecraft.getInstance().font;
        value = value.replace('\n', ' ').replace('\r', ' ');
        if (font.width(value) <= available) return value;
        if (available < font.width("…")) return "";
        return font.plainSubstrByWidth(value, available - font.width("…")) + "…";
    }

    private static void signals(Ink ink, JsonObject agent, int x, int y) {
        boolean working = List.of("active", "pending", "starting", "stopping").contains(AgentModels.text(agent, "status"));
        boolean attention = flag(agent, "hasPendingInteraction") || flag(agent, "waitingForGame");
        if (attention || !working && unread(agent)) {
            ink.fill(x + 2, y + 1, x + 6, y + 7, 0xFFE6AC62);
            ink.fill(x + 1, y + 2, x + 7, y + 6, 0xFFE6AC62);
        } else if (working) {
            int phase = (int)((System.nanoTime() / 130_000_000L) % 8);
            int[][] points = {{0, 0}, {3, 0}, {6, 0}, {6, 3}, {6, 6}, {3, 6}, {0, 6}, {0, 3}};
            for (int i = 0; i < points.length; i++) {
                int opacity = 255 - Math.floorMod(phase - i, 8) * 25;
                ink.fill(x + points[i][0], y + points[i][1], x + 2 + points[i][0], y + 2 + points[i][1], opacity << 24 | 0xA0A0A0);
            }
        }
    }

    static boolean unread(JsonObject agent) {
        return agent.has("latestAttentionAt") && !agent.get("latestAttentionAt").isJsonNull() && agent.get("latestAttentionAt").getAsLong()
            > (agent.has("lastReadAt") && !agent.get("lastReadAt").isJsonNull() ? agent.get("lastReadAt").getAsLong() : 0);
    }

    static String age(JsonObject agent) {
        var thread = AgentModels.object(agent, "thread");
        if (!thread.has("createdAt") || thread.get("createdAt").isJsonNull()) return "";
        long createdAt = thread.get("createdAt").getAsLong();
        if (createdAt <= 0) return "";
        long seconds = Math.max(0, (System.currentTimeMillis() - createdAt) / 1000);
        if (seconds < 3_600) return seconds / 60 + "m";
        if (seconds < 86_400) return seconds / 3_600 + "h";
        long days = seconds / 86_400;
        if (days < 7) return days + "d";
        if (days < 365) return days / 7 + "w";
        return days / 365 + "y";
    }

    private static boolean flag(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }
}
