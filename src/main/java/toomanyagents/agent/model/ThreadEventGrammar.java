package toomanyagents.agent.model;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Stateful event grammar; apply only to an ordered event stream. */
public final class ThreadEventGrammar {
    public static final Set<String> STREAMING = Set.of(
            "item/agentMessage/delta", "item/plan/delta",
            "item/commandExecution/outputDelta", "item/fileChange/outputDelta",
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta",
            "item/mcpToolCall/progress", "item/toolCall/progress");

    private static final class State {
        final Set<String> open = new LinkedHashSet<>();
        final Set<String> settled = new LinkedHashSet<>();
        final Set<String> startedTurns = new HashSet<>();
        final Set<String> completedTurns = new HashSet<>();
    }

    private final Map<String, State> threads = new HashMap<>();

    public void clear() {
        threads.clear();
    }

    public void clearThread(String conversationId) {
        threads.remove(conversationId);
    }

    public void observe(JsonObject event) {
        State state = threads.computeIfAbsent(SharedModel.str(event, "threadId"), ignored -> new State());
        String type = SharedModel.str(event, "type");
        String turnId = SharedModel.str(event.getAsJsonObject("scope"), "turnId");
        switch (type) {
            case "turn/started" -> {
                if (turnId.isEmpty()) return;
                if (state.startedTurns.contains(turnId) || state.completedTurns.contains(turnId)) {
                    fail("turn/starts-once", turnId);
                }
                state.startedTurns.add(turnId);
            }
            case "turn/completed" -> {
                if (turnId.isEmpty()) return;
                if (state.completedTurns.contains(turnId)) fail("turn/settles-once", turnId);
                if (!state.startedTurns.remove(turnId)) fail("turn/known", turnId);
                state.completedTurns.add(turnId);
            }
            case "item/started" -> {
                String itemId = SharedModel.str(event.getAsJsonObject("item"), "id");
                state.open.add(itemId);
                state.settled.remove(itemId);
                trim(state.open);
            }
            case "item/completed", "item/backgroundTask/completed", "item/delegation/completed" -> {
                String itemId = SharedModel.str(event.getAsJsonObject("item"), "id");
                if (state.settled.contains(itemId)) fail("item/settles-once", itemId);
                state.open.remove(itemId);
                state.settled.add(itemId);
                trim(state.settled);
            }
            case "item/backgroundTask/progress", "item/delegation/progress" ->
                    requireOpen(state, SharedModel.str(event.getAsJsonObject("item"), "id"));
            default -> {
                if (STREAMING.contains(type) && event.has("itemId")) {
                    requireOpen(state, SharedModel.str(event, "itemId"));
                }
            }
        }
    }

    private static void requireOpen(State state, String itemId) {
        if (!state.open.contains(itemId)) fail("item/opens-before-delta", itemId);
    }

    private static void trim(Set<String> items) {
        while (items.size() > 512) items.remove(items.iterator().next());
    }

    private static void fail(String rule, String id) {
        throw new IllegalArgumentException(rule + ": " + id);
    }
}
