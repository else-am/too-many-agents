package toomanyagents.agent.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One projection for live normalized events and offline replay; never executes provider work. */
public final class ConversationProjection {
    private final Map<String, JsonObject> rows = new LinkedHashMap<>();
    private final Map<String, JsonObject> items = new LinkedHashMap<>();
    private final Map<String, JsonObject> requests = new LinkedHashMap<>();
    private final Map<String, JsonObject> backgroundItems = new LinkedHashMap<>();
    private final Map<String, JsonObject> interactions = new LinkedHashMap<>();
    private final Set<String> openTurns = new LinkedHashSet<>();
    private final Set<String> finalizedItems = new LinkedHashSet<>();
    private final Map<String, String> turnParents = new LinkedHashMap<>();
    private final JsonObject state = new JsonObject();
    private final JsonObject extensions = new JsonObject();
    private final JsonArray diagnostics = new JsonArray();

    public ConversationProjection() {
        state.addProperty("execution", "idle");
        state.add("extensions", extensions);
    }

    public void accept(JsonObject envelope) {
        JsonObject data = envelope.getAsJsonObject("data");
        String type = string(envelope, "type");
        String turn = turn(envelope);
        if (!string(data,"providerThreadId").isBlank()) state.add("providerThreadId", data.get("providerThreadId").deepCopy());
        if (data.has("providerId")) state.add("providerId", data.get("providerId").deepCopy());
        switch (type) {
            case "thread/started" -> state.addProperty("started", true);
            case "thread/identity" -> state.add("identity", data.deepCopy());
            case "client/thread/start", "client/turn/start" -> state.add("lastClientLifecycle", data.deepCopy());
            case "client/turn/requested" -> request(envelope, data);
            case "client/turn/rejected" -> {
                JsonObject row = requests.get(string(data, "requestId"));
                if (row != null) {
                    row.addProperty("status", "rejected");
                    row.add("rejection", data.deepCopy());
                    row.addProperty("detail", string(data, "message"));
                }
            }
            case "turn/input/accepted" -> {
                JsonObject row = requests.get(string(data, "clientRequestId"));
                if (row != null) {
                    row.addProperty("status", "accepted");
                    row.add("scope", envelope.get("scope").deepCopy());
                    row.addProperty("turnId", turn);
                    touch(row, envelope);
                }
            }
            case "turn/started" -> {
                openTurns.add(turn);
                if (data.has("parentToolCallId")) turnParents.put(turn, string(data, "parentToolCallId"));
                state.addProperty("execution", "running");
                state.addProperty("activeTurnId", turn);
            }
            case "turn/completed" -> {
                openTurns.remove(turn);
                String status = string(data, "status");
                finishTurn(turn, status);
                state.addProperty("lastTurnStatus", status);
                state.add("lastTurn", data.deepCopy());
                state.addProperty("execution", openTurns.isEmpty() ? "idle" : "running");
                if (openTurns.isEmpty()) state.remove("activeTurnId");
            }
            case "item/started", "item/completed", "item/backgroundTask/progress",
                    "item/backgroundTask/completed", "item/delegation/progress", "item/delegation/completed" ->
                    item(envelope, data.getAsJsonObject("item"), type.endsWith("/completed"));
            case "item/agentMessage/delta", "item/plan/delta", "item/reasoning/summaryTextDelta",
                    "item/reasoning/textDelta", "item/commandExecution/outputDelta", "item/fileChange/outputDelta" ->
                    delta(envelope, data, type);
            case "item/toolCall/progress", "item/mcpToolCall/progress" -> {
                JsonObject row = items.get(string(data, "itemId"));
                if (row != null) {
                    row.addProperty("progress", string(data, "message"));
                    touch(row, envelope);
                }
            }
            case "system/interaction/lifecycle" -> interaction(envelope, data.getAsJsonObject("interaction"));
            case "system/permissionGrant/lifecycle", "system/userQuestion/lifecycle" -> {
                JsonObject lifecycle = data.deepCopy();
                lifecycle.addProperty("id", string(data, "interactionId"));
                if (!lifecycle.has("payload")) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("kind", "approval");
                    payload.add("subject", data.get("subject"));
                    lifecycle.add("payload", payload);
                }
                interaction(envelope, lifecycle);
            }
            case "system/thread/interrupted" -> {
                interruptAll("Provider connection interrupted");
                diagnosticRow(envelope, "interrupted", string(data, "reason"));
            }
            case "thread/name/updated" -> state.add("name", data.get("threadName"));
            case "thread/tokenUsage/updated" -> state.add("tokenUsage", data.get("tokenUsage").deepCopy());
            case "thread/contextWindowUsage/updated" -> contextUsage(data.getAsJsonObject("contextWindowUsage"));
            case "provider/rateLimits/updated" -> state.add("rateLimits", data.get("rateLimits").deepCopy());
            case "provider.env-resolved" -> state.add("environment", data.get("entries").deepCopy());
            case "thread/extensionState/updated" -> extensions.add(string(data, "kind"), data.get("payload").deepCopy());
            case "thread/goal/updated" -> state.add("goal", data.deepCopy());
            case "thread/goal/cleared" -> state.remove("goal");
            case "turn/plan/updated" -> {
                state.add("plan", data.deepCopy());
                JsonObject row = row("plan:" + turn, "planSteps", envelope);
                row.add("plan", data.get("plan").deepCopy());
                row.addProperty("text", planText(data.getAsJsonArray("plan")));
                row.addProperty("title", "Plan");
                row.addProperty("status", "pending");
            }
            case "turn/diff/updated" -> state.add("diff", data.deepCopy());
            case "thread/compacted", "thread/context/cleared" -> {
                state.addProperty("lastContextOperation", type);
                boolean matched = false;
                for (JsonObject row : items.values()) {
                    if ("contextCompaction".equals(string(row, "kind")) && turn.equals(string(row, "turnId"))) {
                        row.addProperty("status", "completed");
                        matched = true;
                    }
                }
                if (!matched) diagnosticRow(envelope, "operation", type.equals("thread/compacted") ? "Context compacted" : "Context cleared");
            }
            case "system/operation" -> {
                JsonObject row = row("operation:" + string(data, "operationId"), "operation", envelope);
                row.addProperty("title", string(data, "operation"));
                row.addProperty("status", string(data, "status"));
                row.addProperty("text", string(data, "message"));
                row.add("operation", data.deepCopy());
            }
            case "system/thread-provisioning" -> {
                JsonObject row = row("provision:" + string(data, "provisioningId"), "provisioning", envelope);
                row.addProperty("title", "Workspace preparation");
                row.addProperty("status", string(data, "status"));
                row.add("provisioning", data.deepCopy());
                StringBuilder text = new StringBuilder();
                for (JsonElement entry : data.getAsJsonArray("entries")) text.append(string(entry.getAsJsonObject(), "text")).append('\n');
                row.addProperty("text", text.toString().stripTrailing());
            }
            case "provider/error", "system/error" -> {
                diagnosticRow(envelope, "error", string(data, "message"));
                state.add("lastError", data.deepCopy());
            }
            case "provider/warning" -> {
                var row = diagnosticRow(envelope, "warning", string(data, "summary") + "\n" + string(data, "details"));
                if (string(data, "summary").equals("Approval review")) row.addProperty("title", "Approval review");
            }
            case "provider/modelFallback" -> {
                JsonObject fallback=data.deepCopy();
                fallback.add("sourceSeq", envelope.get("seq"));
                fallback.add("detectedAt", envelope.get("createdAt"));
                state.add("modelFallback", fallback);
                diagnosticRow(envelope, "warning", string(data, "message"));
            }
            case "provider/unhandled" -> {
                diagnosticRow(envelope, "unsupported", "Unsupported provider event: " + string(data, "rawType"));
            }
            case "system/manager/user_message" -> {
                JsonObject row = row("legacy:" + string(envelope, "id"), "userMessage", envelope);
                row.addProperty("role", "user");
                row.addProperty("text", string(data, "text"));
                row.addProperty("status", "accepted");
            }
            case "system/provider-turn-watchdog" -> diagnosticRow(envelope, "warning", "Provider turn idle watchdog fired");
            default -> throw new IllegalArgumentException("No projection for normalized event " + type);
        }
    }

    private void request(JsonObject envelope, JsonObject data) {
        state.remove("modelFallback");
        String id = string(data, "requestId");
        JsonObject row = row("request:" + id, "userMessage", envelope);
        row.addProperty("role", "user");
        row.addProperty("clientRequestId", id);
        row.addProperty("status", "pending");
        row.add("request", data.deepCopy());
        row.add("content", data.get("input").deepCopy());
        row.addProperty("text", contentText(data.getAsJsonArray("input")));
        row.add("media", media(data.getAsJsonArray("input")));
        requests.put(id, row);
    }

    private void item(JsonObject envelope, JsonObject item, boolean completed) {
        String id = string(item, "id");
        String kind = string(item, "type");
        if (finalizedItems.contains(id) && Set.of("agentMessage", "plan", "reasoning").contains(kind)) return;
        JsonObject row = items.get(id);
        if (row == null && kind.equals("userMessage") && item.has("clientRequestId")) {
            row = requests.get(string(item, "clientRequestId"));
        }
        if (row == null) row = row("item:" + id, kind, envelope);
        items.put(id, row);
        if (kind.equals("backgroundTask")) backgroundItems.put(id,row);
        row.addProperty("kind", kind);
        row.addProperty("itemId", id);
        row.add("item", item.deepCopy());
        touch(row, envelope);
        copy(item, row, "presentation", "parentToolCallId", "approvalStatus", "truncation");
        if (!row.has("parentToolCallId") && turnParents.containsKey(turn(envelope))) {
            row.addProperty("parentToolCallId", turnParents.get(turn(envelope)));
        }
        row.addProperty("status", item.has("status") ? string(item, "status") : completed ? "completed" : "pending");
        if (completed) finalizedItems.add(id);
        switch (kind) {
            case "userMessage" -> {
                row.addProperty("role", "user");
                row.addProperty("status", "accepted");
                // The requested input contains visibility/mention metadata omitted by provider echoes.
                if (!row.has("request")) {
                    row.add("content", item.get("content").deepCopy());
                    row.addProperty("text", contentText(item.getAsJsonArray("content")));
                    row.add("media", media(item.getAsJsonArray("content")));
                }
            }
            case "agentMessage", "plan" -> {
                row.addProperty("role", "assistant");
                String text = string(item, "text");
                if (!text.isEmpty() || !row.has("text")) row.addProperty("text", text);
            }
            case "reasoning" -> {
                String text = join(item.getAsJsonArray("summary")) + join(item.getAsJsonArray("content"));
                if (!text.isEmpty() || !row.has("text")) row.addProperty("text", text);
                row.addProperty("title", "Reasoning");
            }
            case "commandExecution" -> {
                row.addProperty("title", string(item, "command"));
                if (item.has("aggregatedOutput")) row.addProperty("text", string(item, "aggregatedOutput"));
                copy(item, row, "cwd", "exitCode", "durationMs");
                if (item.has("exitCode") && !item.get("exitCode").isJsonNull() && item.get("exitCode").getAsInt()!=0) row.addProperty("status", "failed");
            }
            case "fileChange" -> {
                row.addProperty("title", "File changes");
                StringBuilder text = new StringBuilder();
                for (JsonElement entry : item.getAsJsonArray("changes")) {
                    JsonObject change = entry.getAsJsonObject();
                    text.append(string(change, "kind")).append(' ').append(string(change, "path"));
                    if (change.has("movePath")) text.append(" → ").append(string(change, "movePath"));
                    text.append('\n');
                    if (change.has("diff")) text.append(string(change, "diff")).append('\n');
                }
                row.addProperty("text", text.toString().stripTrailing());
            }
            case "toolCall" -> {
                row.addProperty("title", string(item, "tool"));
                if (item.has("result")) row.addProperty("text", display(item.get("result")));
                else if (item.has("error")) row.addProperty("text", string(item, "error"));
            }
            case "webSearch" -> {
                row.addProperty("title", "Search: " + join(item.getAsJsonArray("queries"), ", "));
                row.addProperty("text", string(item, "resultText"));
            }
            case "webFetch" -> {
                row.addProperty("title", string(item, "url"));
                row.addProperty("text", string(item, "resultText"));
            }
            case "imageView", "imageGeneration" -> {
                row.addProperty("title", kind.equals("imageView") ? "View image" : "Generate image");
                row.addProperty("text", first(item, "error", "prompt", "path"));
                JsonArray attachments = new JsonArray();
                if (!string(item, "path").isBlank()) attachments.add(reference("localImage", string(item, "path")));
                if (!string(item,"result").isBlank()) {
                    JsonObject output=reference("image", string(item, "result"));
                    if (!string(item,"result").startsWith("http://") && !string(item,"result").startsWith("https://") && !string(item,"result").startsWith("data:")) {
                        output.addProperty("availability", "retained-output");
                        output.addProperty("detail", "Original image output retained; decoding requires its content format");
                    }
                    attachments.add(output);
                }
                row.add("media", attachments);
            }
            case "fileRead" -> { row.addProperty("title", "Read file"); row.addProperty("text", string(item, "path")); }
            case "search" -> {
                row.addProperty("title", "Search " + string(item, "mode"));
                row.addProperty("text", string(item, "query") + (item.has("path") ? " — " + string(item, "path") : ""));
            }
            case "planSteps" -> {
                row.addProperty("title", "Plan");
                row.addProperty("text", planText(item.getAsJsonArray("steps")));
            }
            case "contextCompaction" -> { row.addProperty("title", "Compact context"); row.addProperty("text", "Context compaction"); }
            case "backgroundTask" -> {
                row.addProperty("title", string(item, "description"));
                row.addProperty("text", first(item, "error", "summary", "description"));
                copy(item, row, "taskStatus", "workflow", "usage", "familyId", "skipTranscript", "outputFile");
                if (!string(item,"outputFile").isBlank()) {
                    JsonArray outputs=new JsonArray();
                    outputs.add(reference("localFile",string(item,"outputFile")));
                    row.add("media",outputs);
                }
                row.addProperty("background", true);
            }
            case "delegation" -> {
                row.addProperty("title", string(item, "label"));
                row.addProperty("text", string(item, "summary"));
                copy(item, row, "childRef", "background");
            }
            case "extension" -> {
                row.addProperty("title", string(item, "kind"));
                row.addProperty("text", display(item.get("payload")));
                row.add("extensionPayload", item.get("payload").deepCopy());
            }
            default -> throw new IllegalArgumentException("No projection for item " + kind);
        }
        present(row);
    }

    private void delta(JsonObject envelope, JsonObject data, String type) {
        String id = string(data, "itemId");
        if (finalizedItems.contains(id)) return;
        JsonObject row = items.get(id);
        if (row == null) {
            String kind = type.split("/")[1];
            row = row("item:" + id, kind, envelope);
            row.addProperty("itemId", id);
            row.addProperty("status", "pending");
            if (kind.equals("agentMessage") || kind.equals("plan")) row.addProperty("role", "assistant");
            items.put(id, row);
        }
        String field = type.equals("item/fileChange/outputDelta") ? "output" : "text";
        String previous = bool(data, "reset") ? "" : string(row, field);
        row.addProperty(field, previous + string(data, "delta"));
        copy(data, row, "parentToolCallId");
        touch(row, envelope);
    }

    private void interaction(JsonObject envelope, JsonObject lifecycle) {
        String id = string(lifecycle, "id");
        JsonObject row = row("interaction:" + id, "interaction", envelope);
        JsonArray history = row.has("history") ? row.getAsJsonArray("history") : new JsonArray();
        JsonObject step = lifecycle.deepCopy();
        step.add("createdAt", envelope.get("createdAt"));
        history.add(step);
        row.add("history", history);
        row.add("interaction", lifecycle.deepCopy());
        row.addProperty("status", string(lifecycle, "status"));
        row.addProperty("historicalStatus", string(lifecycle, "status"));
        row.addProperty("actionable", false);
        JsonObject payload = lifecycle.getAsJsonObject("payload");
        row.addProperty("title", switch (string(payload, "kind")) {
            case "approval" -> "Approval";
            case "user_question" -> "Question";
            default -> first(payload, "title", "kind");
        });
        row.addProperty("text", display(payload));
        if (payload.has("subject")) {
            JsonObject subject = payload.getAsJsonObject("subject");
            row.addProperty("itemId", string(subject, "itemId"));
            copy(subject, row, "presentation");
        }
        if (payload.has("presentation")) copy(payload, row, "presentation");
        present(row);
        interactions.put(id, row);
    }

    private void finishTurn(String turn, String status) {
        for (JsonObject row : rows.values()) {
            if (!turn.equals(string(row, "turnId")) || bool(row, "background") || !pending(row)) continue;
            String kind = string(row, "kind");
            if (kind.equals("delegation") && openTurns.stream().anyMatch(child -> string(row, "itemId").equals(turnParents.get(child)))) continue;
            boolean text = kind.equals("agentMessage") || kind.equals("reasoning") || kind.equals("plan") || kind.equals("planSteps");
            row.addProperty("status", status.equals("completed") && text ? "completed" : "interrupted");
            row.addProperty("statusReason", text ? "Turn ended" : "Turn ended without a terminal item event");
            describeInterruptedTool(row);
            if (row.has("itemId") && !kind.equals("interaction")) finalizedItems.add(string(row, "itemId"));
            present(row);
        }
    }

    /** Restore is a read operation: historical pending work never becomes a live approval or running task. */
    public void finishReplay() {
        interruptAll("No live provider owns this saved work");
        state.addProperty("restored", true);
    }

    private void interruptAll(String reason) {
        for (JsonObject row : rows.values()) {
            if (pending(row)) {
                row.addProperty("historicalStatus", string(row, "status"));
                row.addProperty("status", "interrupted");
                row.addProperty("statusReason", reason);
                describeInterruptedTool(row);
                present(row);
            }
        }
        openTurns.clear();
        state.addProperty("execution", "idle");
        state.remove("activeTurnId");
    }

    public void diagnostic(String message) { diagnostics.add(message); }

    /** Live status excludes large diffs, inputs, and provider extension payloads. */
    public JsonObject stateSnapshot() {
        JsonObject latest = new JsonObject();
        copy(state,latest,"execution","activeTurnId","lastTurnStatus","started","name","contextWindowUsage");
        int background=0, attention=0;
        for (var row : backgroundItems.values()) if (pending(row)) background++;
        for (var row : interactions.values()) if (pending(row)) attention++;
        latest.addProperty("backgroundRunning",background);
        latest.addProperty("historicalPendingInteractions",attention);
        latest.addProperty("attention",attention>0);
        return latest;
    }

    public JsonObject transcript(JsonObject query) { return ConversationTranscript.page(rows, openTurns, query); }

    public String lastReply(String turn) {
        String result="";
        for(var row:rows.values()) if (string(row,"role").equals("assistant") && !row.has("parentToolCallId")
                && (turn.isBlank() || string(row,"turnId").equals(turn)) && !string(row,"text").isBlank()) result=string(row,"text");
        return result;
    }

    public JsonObject snapshot() {
        JsonObject result = new JsonObject();
        JsonArray all = new JsonArray(), messages = new JsonArray(), activity = new JsonArray(), asks = new JsonArray();
        int background = 0, attention = 0;
        for (JsonObject source : rows.values()) {
            JsonObject row = source.deepCopy();
            if (emptyTextRow(row)) continue;
            JsonArray children = childrenOf(string(row, "itemId"), new LinkedHashSet<>());
            if (!children.isEmpty()) row.add("childRows", children);
            JsonArray linked = new JsonArray();
            if (!string(row, "kind").equals("interaction") && row.has("itemId")) {
                for (JsonObject interaction : interactions.values()) {
                    if (string(row, "itemId").equals(string(interaction, "itemId"))) linked.add(interaction.deepCopy());
                }
            }
            if (!linked.isEmpty()) row.add("interactions", linked);
            if (bool(row, "background") && pending(row)) background++;
            if ("interaction".equals(string(row, "kind")) && pending(row)) attention++;
            all.add(row);
            if (row.has("role") && !row.has("parentToolCallId")) messages.add(row.deepCopy());
            else if (!row.has("parentToolCallId") && !bool(row, "suppressed") && !bool(row, "skipTranscript")) activity.add(row.deepCopy());
            if ("interaction".equals(string(row, "kind"))) asks.add(row.deepCopy());
        }
        JsonObject latest = state.deepCopy();
        latest.addProperty("backgroundRunning", background);
        latest.addProperty("historicalPendingInteractions", attention);
        // Live handles, unread and Minecraft world waits belong to their respective owners.
        latest.addProperty("attention", attention > 0);
        result.add("rows", all);
        result.add("messages", messages);
        result.add("activity", activity);
        result.add("interactions", asks);
        result.add("state", latest);
        result.add("diagnostics", diagnostics.deepCopy());
        return result;
    }

    private JsonArray childrenOf(String parent, Set<String> ancestors) {
        JsonArray children = new JsonArray();
        if (parent.isBlank() || !ancestors.add(parent)) return children;
        for (JsonObject source : rows.values()) {
            if (!parent.equals(string(source, "parentToolCallId"))) continue;
            if (emptyTextRow(source)) continue;
            JsonObject child = source.deepCopy();
            JsonArray nested = childrenOf(string(child, "itemId"), new LinkedHashSet<>(ancestors));
            if (!nested.isEmpty()) child.add("childRows", nested);
            children.add(child);
        }
        return children;
    }

    private static boolean emptyTextRow(JsonObject row) {
        return Set.of("reasoning", "agentMessage", "plan").contains(string(row, "kind")) && string(row, "text").isEmpty();
    }

    private static void describeInterruptedTool(JsonObject row) {
        if ("interrupted".equals(string(row, "status")) && string(row, "text").isEmpty()
                && Set.of("commandExecution", "toolCall", "webSearch", "webFetch", "imageView").contains(string(row, "kind"))) {
            row.addProperty("text", "Tool execution interrupted");
        }
    }

    private void contextUsage(JsonObject incoming) {
        JsonObject value=incoming.deepCopy();
        JsonElement limit=value.get("modelContextWindow");
        if (limit==null || limit.isJsonNull() || limit.getAsDouble()<=0) {
            JsonObject previous=state.has("contextWindowUsage") ? state.getAsJsonObject("contextWindowUsage") : new JsonObject();
            if (previous.has("modelContextWindow")) value.add("modelContextWindow",previous.get("modelContextWindow").deepCopy());
        }
        JsonElement used=value.get("usedTokens");
        if (used!=null && !used.isJsonNull() && used.getAsDouble()<0) value.add("usedTokens",JsonNull.INSTANCE);
        if (value.has("snapshot") && value.get("snapshot").isJsonObject()) {
            JsonObject snapshot=value.getAsJsonObject("snapshot");
            if (!Objects.equals(snapshot.get("usedTokens"),value.get("usedTokens")) || !Objects.equals(snapshot.get("contextWindowTokens"),value.get("modelContextWindow"))) value.remove("snapshot");
        }
        state.add("contextWindowUsage",value);
    }

    private JsonObject row(String id, String kind, JsonObject envelope) {
        JsonObject row = rows.computeIfAbsent(id, ignored -> {
            JsonObject created = new JsonObject();
            created.addProperty("id", id);
            created.addProperty("kind", kind);
            created.add("threadId", envelope.get("threadId"));
            created.add("scope", envelope.get("scope").deepCopy());
            created.addProperty("turnId", turn(envelope));
            created.add("sourceSeqStart", envelope.get("seq"));
            created.add("createdAt", envelope.get("createdAt"));
            JsonObject eventData = envelope.getAsJsonObject("data");
            if (eventData.has("parentToolCallId")) created.add("parentToolCallId", eventData.get("parentToolCallId"));
            else if (turnParents.containsKey(turn(envelope))) created.addProperty("parentToolCallId", turnParents.get(turn(envelope)));
            return created;
        });
        touch(row, envelope);
        return row;
    }

    private static void touch(JsonObject row, JsonObject envelope) {
        row.add("sourceSeqEnd", envelope.get("seq"));
        row.add("updatedAt", envelope.get("createdAt"));
    }

    private JsonObject diagnosticRow(JsonObject envelope, String kind, String text) {
        JsonObject row = row("event:" + string(envelope, "id"), kind, envelope);
        row.addProperty("text", text.strip());
        row.addProperty("title", kind);
        JsonObject data=envelope.getAsJsonObject("data");
        row.add("event", data.deepCopy());
        row.addProperty("status", "completed");
        copy(data,row,"detail","errorInfo","willRetry","reconnectAttempt","reconnectTotal");
        if (kind.equals("error")) {
            row.addProperty("title", text.isBlank() ? "Error event" : text);
            if (!string(data,"detail").isBlank() && !string(data,"detail").equals(text)) row.addProperty("text", text + "\n" + string(data,"detail"));
        }
        return row;
    }

    private static void present(JsonObject row) {
        if (!row.has("presentation")) return;
        JsonObject presentation = row.getAsJsonObject("presentation");
        copy(presentation, row, "title", "detail", "icon", "tint", "badge");
        row.addProperty("suppressed", bool(presentation, "suppress"));
        if (presentation.has("label")) {
            row.addProperty("label", string(presentation.getAsJsonObject("label"), pending(row) ? "pending" : "completed"));
        }
    }

    private static boolean pending(JsonObject row) {
        return Set.of("pending", "streaming", "running", "active", "resolving", "started", "waiting_for_approval").contains(string(row, "status"));
    }

    private static String contentText(JsonArray content) {
        StringBuilder result = new StringBuilder();
        for (JsonElement value : content) {
            JsonObject part = value.getAsJsonObject();
            if (!"agent-only".equals(string(part, "visibility")) && "text".equals(string(part, "type"))) result.append(string(part, "text"));
        }
        return result.toString();
    }

    private static JsonArray media(JsonArray content) {
        JsonArray result = new JsonArray();
        for (JsonElement value : content) {
            JsonObject part = value.getAsJsonObject();
            String type = string(part, "type");
            if (!"agent-only".equals(string(part, "visibility")) && !type.equals("text")) {
                result.add(reference(type, type.equals("image") ? string(part, "url") : string(part, "path")));
            }
        }
        return result;
    }

    private static JsonObject reference(String type, String location) {
        JsonObject media = new JsonObject();
        media.addProperty("type", type);
        media.addProperty("reference", location);
        boolean inline = location.startsWith("data:");
        media.addProperty("availability", inline ? "retained-inline" : "unavailable-offline");
        media.addProperty("detail", inline ? "Inline content retained in event" : "Reference retained; content was not copied into conversation history");
        return media;
    }

    private static String planText(JsonArray steps) {
        StringBuilder text = new StringBuilder();
        for (JsonElement value : steps) {
            JsonObject step = value.getAsJsonObject();
            if (text.length() > 0) text.append('\n');
            text.append('[').append(step.has("status") ? string(step, "status") : "pending").append("] ").append(string(step, "step"));
        }
        return text.toString();
    }

    private static void copy(JsonObject from, JsonObject to, String... fields) {
        for (String field : fields) if (from.has(field)) to.add(field, from.get(field).deepCopy());
    }
    private static String first(JsonObject object, String... fields) {
        for (String field : fields) if (!string(object, field).isEmpty()) return string(object, field);
        return "";
    }
    private static String join(JsonArray values) { return join(values, ""); }
    private static String join(JsonArray values, String separator) {
        if (values == null) return "";
        StringBuilder result = new StringBuilder();
        for (JsonElement value : values) { if (result.length() > 0) result.append(separator); result.append(value.getAsString()); }
        return result.toString();
    }
    private static String display(JsonElement value) { return value == null || value instanceof JsonNull ? "" : value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : value.toString(); }
    private static String string(JsonObject value, String key) { return value == null || !value.has(key) || value.get(key).isJsonNull() ? "" : value.get(key).getAsString(); }
    private static boolean bool(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean(); }
    private static String turn(JsonObject envelope) { return string(envelope.getAsJsonObject("scope"), "turnId"); }
}
