package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Explicit player addresses in vanilla chat. Ambient and incoming chat never enter this path. */
final class AgentMentions {
    private final Supplier<AgentService> service;
    private ChatScreen chat;
    private AgentService openedService;
    private JsonObject pointing;
    private String queryValue = "";
    private int queryCursor = -1;
    private String completedValue;
    private int completedCursor;
    private String dismissedValue;
    private List<Candidate> matches = List.of();
    private int selected;
    private int replaceEnd;

    private record Candidate(JsonObject agent, String name, String token) {}
    private record Address(String name, String idPrefix, int end) {}

    AgentMentions(Supplier<AgentService> service) {
        this.service = service;
        NeoForge.EVENT_BUS.addListener(this::opening);
        NeoForge.EVENT_BUS.addListener(this::key);
        NeoForge.EVENT_BUS.addListener(this::click);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::send);
    }

    private void opening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof ChatScreen next)) return;
        chat = next;
        openedService = service.get();
        pointing = ClientControls.capturePointing();
        queryValue = "";
        queryCursor = -1;
        completedValue = null;
        dismissedValue = null;
        matches = List.of();
        selected = 0;
    }

    private boolean active(Screen screen) {
        var client = Minecraft.getInstance();
        return screen == chat && openedService != null && openedService == service.get()
            && client.hasSingleplayerServer() && client.level != null;
    }

    private static EditBox input(Screen screen) {
        if (screen == null) return null;
        for (var child : screen.children()) if (child instanceof EditBox edit) return edit;
        return null;
    }

    private List<Candidate> candidates() {
        List<JsonObject> agents = new ArrayList<>();
        for (var value : openedService.worldAgents()) agents.add(value.getAsJsonObject());
        agents.sort(Comparator.comparing(a -> name(a).toLowerCase(Locale.ROOT)));
        List<Candidate> result = new ArrayList<>();
        for (var agent : agents) {
            String name = name(agent);
            String token = "@" + (name.matches("[\\p{L}\\p{N}_-]+") ? name : new JsonPrimitive(name));
            if (agents.stream().filter(a -> name(a).equalsIgnoreCase(name)).count() > 1) {
                String id = agent.get("id").getAsString();
                int length = Math.min(8, id.length());
                while (length < id.length()) {
                    String prefix = id.substring(0, length);
                    if (agents.stream().filter(a -> a.get("id").getAsString().startsWith(prefix)).count() == 1) break;
                    length++;
                }
                token += "#" + id.substring(0, length);
            }
            result.add(new Candidate(agent, name, token));
        }
        return result;
    }

    private static String name(JsonObject agent) {
        // Vanilla normalizes chat whitespace before dispatch.
        return agent.get("name").getAsString().trim().replaceAll("\\s+", " ");
    }

    private void refresh(EditBox edit) {
        String value = edit.getValue();
        int cursor = edit.getCursorPosition();
        if (value.equals(completedValue) && cursor == completedCursor) {
            var available = candidates();
            matches = matches.stream().filter(c -> available.stream()
                .anyMatch(a -> a.token.equals(c.token) && a.agent.get("id").equals(c.agent.get("id")))).toList();
            selected = matches.isEmpty() ? 0 : Math.min(selected, matches.size() - 1);
            return;
        }
        completedValue = null;
        if (!value.equals(queryValue) || cursor != queryCursor) selected = 0;
        queryValue = value;
        queryCursor = cursor;
        matches = List.of();
        if (value.equals(dismissedValue) || cursor < 1 || !value.startsWith("@")) return;
        Address address = address(value);
        int end = address == null ? value.length() : address.end;
        if (cursor > end) return;
        String prefix = value.substring(1, cursor);
        if (!prefix.startsWith("\"") && prefix.chars().anyMatch(Character::isWhitespace)) return;
        String partial = prefix.startsWith("\"") ? prefix.substring(1) : prefix;
        String lowered = partial.toLowerCase(Locale.ROOT);
        matches = candidates().stream().filter(c -> c.name.toLowerCase(Locale.ROOT).startsWith(lowered)
            || c.token.substring(1).toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
        replaceEnd = end;
    }

    private void key(ScreenEvent.KeyPressed.Pre event) {
        if (!active(event.getScreen())) return;
        var edit = input(event.getScreen());
        if (edit == null) return;
        refresh(edit);
        int key = event.getKeyCode();
        if (matches.isEmpty()) {
            // An unknown @address must not turn into a vanilla player completion.
            if (key == GLFW.GLFW_KEY_TAB && edit.getValue().startsWith("@")
                && edit.getCursorPosition() <= mentionEnd(edit.getValue())) event.setCanceled(true);
            return;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            if (completedValue != null) selected = Math.floorMod(selected + ((event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1), matches.size());
            complete(edit);
        } else if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            selected = Math.floorMod(selected + (key == GLFW.GLFW_KEY_UP ? -1 : 1), matches.size());
        } else if (key == GLFW.GLFW_KEY_ESCAPE) {
            dismissedValue = edit.getValue();
            completedValue = null;
            matches = List.of();
        } else return;
        event.setCanceled(true);
    }

    private void complete(EditBox edit) {
        String value = edit.getValue();
        String suffix = value.substring(Math.min(replaceEnd, value.length()));
        String token = matches.get(selected).token;
        if (!suffix.startsWith(" ")) suffix = " " + suffix;
        edit.setValue(token + suffix);
        edit.setCursorPosition(token.length() + 1);
        edit.setHighlightPos(edit.getCursorPosition());
        completedValue = edit.getValue();
        completedCursor = edit.getCursorPosition();
        replaceEnd = token.length();
        Minecraft.getInstance().getNarrator().sayNow(Component.literal(token));
    }

    private int firstRow() { return Math.max(0, selected - 7); }
    private int rows() { return Math.min(8, matches.size()); }
    private int popupY() { return chat.height - 16 - rows() * 12; }
    private int popupWidth() {
        var font = Minecraft.getInstance().font;
        return Math.min(chat.width - 8, matches.stream().mapToInt(c -> font.width(c.token) + 8).max().orElse(0));
    }

    private void render(ScreenEvent.Render.Post event) {
        if (!active(event.getScreen())) return;
        var edit = input(event.getScreen());
        if (edit == null) return;
        refresh(edit);
        if (matches.isEmpty()) return;
        var graphics = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        int width = popupWidth();
        int y = popupY();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        graphics.fill(3, y, 3 + width, y + rows() * 12, 0xE0202020);
        for (int row = 0; row < rows(); row++) {
            int index = firstRow() + row;
            if (index == selected) graphics.fill(3, y + row * 12, 3 + width, y + (row + 1) * 12, 0xFF505050);
            graphics.drawString(font, font.plainSubstrByWidth(matches.get(index).token, width - 8), 7, y + row * 12 + 2,
                0xFFE1E7DF, false);
        }
        graphics.pose().popPose();
    }

    private void click(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!active(event.getScreen()) || event.getButton() != 0) return;
        var edit = input(event.getScreen());
        if (edit == null) return;
        refresh(edit);
        if (matches.isEmpty() || event.getMouseX() < 3 || event.getMouseX() >= 3 + popupWidth()
            || event.getMouseY() < popupY() || event.getMouseY() >= popupY() + rows() * 12) return;
        selected = firstRow() + (int)(event.getMouseY() - popupY()) / 12;
        complete(edit);
        event.setCanceled(true);
    }

    private static int mentionEnd(String value) {
        Address result = address(value);
        return result == null ? value.length() : result.end;
    }

    private static Address address(String value) {
        if (!value.startsWith("@") || value.length() == 1) return null;
        int end = 1;
        String name;
        if (value.charAt(1) == '"') {
            boolean escaped = false;
            end = 2;
            for (; end < value.length(); end++) {
                char c = value.charAt(end);
                if (c == '"' && !escaped) break;
                escaped = c == '\\' && !escaped;
            }
            if (end == value.length()) return null;
            try { name = JsonParser.parseString(value.substring(1, ++end)).getAsString(); }
            catch (RuntimeException invalid) { return null; }
        } else {
            while (end < value.length() && !Character.isWhitespace(value.charAt(end)) && value.charAt(end) != '#') end++;
            name = value.substring(1, end);
        }
        String id = "";
        if (end < value.length() && value.charAt(end) == '#') {
            int start = ++end;
            while (end < value.length() && !Character.isWhitespace(value.charAt(end))) end++;
            id = value.substring(start, end);
            if (id.isEmpty()) return null;
        }
        if (end < value.length() && !Character.isWhitespace(value.charAt(end))) return null;
        return new Address(name, id, end);
    }

    private void send(ClientChatEvent event) {
        var client = Minecraft.getInstance();
        if (!active(client.screen) || !event.getMessage().startsWith("@")) return;
        event.setCanceled(true);
        Address address = address(event.getMessage());
        if (address == null) { error("Use Tab to complete an agent mention."); return; }
        var found = candidates().stream().filter(c -> c.name.equalsIgnoreCase(address.name)
            && (address.idPrefix.isEmpty() || c.agent.get("id").getAsString().startsWith(address.idPrefix))).toList();
        if (found.size() != 1) {
            error(found.isEmpty() ? "No matching agent in this world. Use Tab to choose one." : "That name matches several agents. Use Tab to choose one.");
            return;
        }
        String body = event.getMessage().substring(address.end).trim();
        if (body.isBlank()) { error("Add a message after the agent mention."); return; }
        var agent = found.getFirst().agent;
        client.gui.getChat().addMessage(Component.translatable("chat.type.text",
            client.player.getDisplayName(), Component.literal(event.getMessage())));
        openedService.sendMention(agent.get("id").getAsString(), body, pointing == null ? null : pointing.deepCopy())
            .whenComplete((unused,failure) -> client.execute(() -> {
                if(failure!=null)error("Agent message was not delivered: " + failure.getMessage());
            }));
    }

    private static void error(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(message), false);
    }

    /** The live popup and context, for diagnostics driving the actual vanilla screen. */
    JsonObject diagnostics(Screen screen) {
        var result = new JsonObject();
        if (!active(screen)) return result;
        var edit = input(screen);
        if (edit != null) refresh(edit);
        if (pointing != null) result.add("pointing", pointing.deepCopy());
        var suggestions = new JsonArray();
        for (var candidate : matches) {
            var entry = new JsonObject();
            entry.addProperty("agentId", candidate.agent.get("id").getAsString());
            entry.addProperty("mention", candidate.token);
            suggestions.add(entry);
        }
        result.add("suggestions", suggestions);
        result.addProperty("selected", selected);
        return result;
    }
}
