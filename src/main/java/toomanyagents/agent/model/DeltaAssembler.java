package toomanyagents.agent.model;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;

/** Grammar-v3 semantic delta assembler. */
public final class DeltaAssembler {
    private final String conversationId, providerId, prefix;
    private final Consumer<JsonObject> sink;
    private final LongSupplier now;
    private final long textFlushMs, progressThrottleMs;
    private final ThreadEventGrammar grammar = new ThreadEventGrammar();
    private final Map<String, String> turns = new LinkedHashMap<>(), nativeTurns = new LinkedHashMap<>(), items = new LinkedHashMap<>(), snapshots = new HashMap<>();
    private final Map<String, Open> open = new LinkedHashMap<>();
    private final Set<String> settled = new LinkedHashSet<>();
    private final List<String> accepted = new ArrayList<>();
    private final Map<String, JsonObject> pendingText = new LinkedHashMap<>(), pendingProgress = new LinkedHashMap<>();
    private final Map<String, Long> textTimes = new LinkedHashMap<>(), progressTimes = new LinkedHashMap<>();
    private String current, last, extensionOwner;
    private JsonObject extensionKinds = new JsonObject();
    private long turnCounter, itemCounter;

    // Provider-keyed turns are separate from the implicit current turn.
    // Connectors must explicitly close keyed turns when their session dies.

    private static final class Open {
        String id, text = "", summary = "";
        JsonObject key, item;
        boolean attached;
        Open(String id, JsonObject key, JsonObject item, boolean attached) {
            this.id = id;
            this.key = key;
            this.item = item;
            this.attached = attached;
        }
    }

    public DeltaAssembler(String conversationId, Consumer<JsonObject> sink) {
        this(conversationId, "", "da" + UUID.randomUUID().toString().substring(0, 8), 100, 500, System::currentTimeMillis, sink);
    }

    public DeltaAssembler(String conversationId, String providerId, String entropyPrefix, long textFlushMs, long progressThrottleMs, LongSupplier now, Consumer<JsonObject> sink) {
        this.conversationId = conversationId;
        this.providerId = providerId;
        this.prefix = entropyPrefix;
        this.textFlushMs = textFlushMs;
        this.progressThrottleMs = progressThrottleMs;
        this.now = now;
        this.sink = sink;
    }

    public synchronized void configureExtensions(String pluginId, JsonObject declaredKinds) {
        extensionOwner = Objects.requireNonNull(pluginId);
        extensionKinds = declaredKinds.deepCopy();
    }

    public synchronized String activeTurnId() {
        return current;
    }

    public synchronized String nativeTurnId(String id) {
        return nativeTurns.get(id);
    }

    public synchronized String canonicalTurnId(String id) {
        return turns.get(id);
    }

    /** Joins an implicit turn to its connector's command/tool handle without changing emitted events. */
    public synchronized void bindNativeTurn(String nativeId, String sharedId) {
        if (nativeId == null || nativeId.isBlank() || sharedId == null || sharedId.isBlank())
            throw new IllegalArgumentException("Turn identities must be nonempty");
        String existing = turns.get(nativeId), reverse = nativeTurns.get(sharedId);
        if (existing != null && !existing.equals(sharedId) || reverse != null && !reverse.equals(nativeId))
            throw new IllegalArgumentException("Turn identity is already bound");
        if (!sharedId.equals(current) && reverse == null) throw new IllegalArgumentException("Unknown shared turn");
        turns.put(nativeId,sharedId);
        nativeTurns.put(sharedId,nativeId);
        trim(turns,1024);
        trim(nativeTurns,1024);
    }

    public synchronized String canonicalItemId(String id) {
        return items.get(id);
    }

    public synchronized void accept(JsonObject delta) {
        JsonArray a = new JsonArray();
        a.add(delta);
        acceptAll(a);
    }
    // No timer thread: the caller owns serialization and can submit an empty
    // batch to flush elapsed buffers without reversing service lock order.

    public synchronized void acceptAll(JsonArray batch) {
        if (batch.isEmpty() || !s(batch.get(0).getAsJsonObject(), "kind").equals("session.reset")) {
            if (pendingText.keySet().stream().anyMatch(k -> now.getAsLong()-textTimes.getOrDefault(k, 0L)>=textFlushMs)) flushText();
            Set<String> skip = new HashSet<>();
            for (JsonElement e : batch) if (s(e.getAsJsonObject(), "kind").equals("item.progress")) skip.add(key(e.getAsJsonObject().getAsJsonObject("key")));
            for (String k : new ArrayList<>(pendingProgress.keySet())) if (!skip.contains(k) && now.getAsLong()-progressTimes.getOrDefault(k, 0L)>=progressThrottleMs) {
                JsonObject e = pendingProgress.remove(k);
                progressTimes.put(k, now.getAsLong());
                emit(e);
            }
        }
        for (JsonElement e : batch) {
            JsonObject d = e.getAsJsonObject();
            SharedModel.validateDelta(d);
            String kind = s(d, "kind");
            if (Set.of("item.close", "item.textClose", "session.ended", "session.reset").contains(kind)) flushText();
            handle(d);
        }
    }
    /** Drain buffered output at an application-owned checkpoint. */

    public synchronized void flush() {
        flushText();
        for (JsonObject e : new ArrayList<>(pendingProgress.values())) emit(e);
        pendingProgress.clear();
    }

    private static String s(JsonObject o, String k) {
        return SharedModel.str(o, k);
    }

    private static boolean b(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }

    private static JsonObject obj(String...pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i<pairs.length; i+=2) o.addProperty(pairs[i], pairs[i + 1]);
        return o;
    }

    private static void copy(JsonObject from, JsonObject to, String...fields) {
        for (String f : fields) if (from.has(f)) to.add(f, from.get(f).deepCopy());
    }

    private static <T> void trim(Map<String, T> m, int max) {
        while (m.size()>max) m.remove(m.keySet().iterator().next());
    }

    private String newItem() {
        return prefix + "-i" + (++itemCounter);
    }

    private String newTurn() {
        return prefix + "-t" + (++turnCounter);
    }

    private String resolveTurn(String nativeId) {
        String existing = turns.get(nativeId);
        if (existing != null) return existing;
        String id = newTurn();
        turns.put(nativeId, id);
        nativeTurns.put(id, nativeId);
        trim(turns, 1024);
        trim(nativeTurns, 1024);
        return id;
    }

    private void register(String nativeId, String id) {
        if (nativeId.isEmpty()) return;
        items.put(nativeId, id);
        trim(items, 1024);
    }

    private String itemId(JsonObject k) {
        String nativeId = s(k, "providerItemId"), id = items.get(nativeId);
        if (id == null) {
            id = newItem();
            register(nativeId, id);
        }
        return id;
    }

    private String parent(JsonObject k) {
        String nativeId = s(k, "parentRef");
        if (nativeId.isEmpty()) return null;
        String id = items.get(nativeId);
        if (id == null) {
            id = newItem();
            register(nativeId, id);
        }
        return id;
    }

    private static String key(JsonObject k) {
        return s(k, "providerItemId") + '\u001f' + s(k, "channel") + '\u001f' + s(k, "parentRef");
    }

    private String turn(JsonObject delta, boolean useLast) {
        if (delta.has("providerTurnId")) return resolveTurn(s(delta, "providerTurnId"));
        if (current != null) return current;
        return useLast ? last : null;
    }

    private JsonObject event(String type, String turn) {
        JsonObject e = obj("type", type, "threadId", conversationId, "providerThreadId", "");
        e.add("scope", turn == null ? obj("kind", "thread") : obj("kind", "turn", "turnId", turn));
        return e;
    }

    private void publish(JsonObject e) {
        SharedModel.validateEvent(e);
        e = checkExtension(e);
        grammar.observe(e);
        sink.accept(e.deepCopy());
    }

    private JsonObject checkExtension(JsonObject event) {
        if (extensionOwner == null) return event;
        JsonObject site = event.has("item") ? event.getAsJsonObject("item") : event;
        boolean state = s(event, "type").equals("thread/extensionState/updated");
        if (!state && !s(site, "type").equals("extension")) return event;
        String kind = s(site, "kind"), surface = state ? "state" : "item", problem = null;
        JsonObject declaration = extensionKinds.has(kind) && extensionKinds.get(kind).isJsonObject() ? extensionKinds.getAsJsonObject(kind) : null;
        if (!kind.startsWith(extensionOwner + "/")) problem = "Extension is owned by another provider";
        else if (declaration == null || !declaration.has(surface) || declaration.get(surface).equals(new JsonPrimitive(false))) problem = "Extension surface is undeclared";
        else if (site.get("payload").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536) problem = "Extension payload exceeds 64 KiB";
        else if (declaration.get(surface).isJsonObject()) {
            try {
                SharedModel.validateJsonSchema(declaration.getAsJsonObject(surface), site.get("payload"));
            }
            catch (IllegalArgumentException invalid) {
                problem = invalid.getMessage();
            }
        }
        if (problem == null) return event;
        JsonObject diagnostic = event.deepCopy();
        diagnostic.remove("item");
        diagnostic.remove("kind");
        diagnostic.remove("payload");
        diagnostic.addProperty("type", "provider/unhandled");
        diagnostic.addProperty("providerId", providerId);
        diagnostic.addProperty("rawType", "extension/" + surface + ":" + kind);
        copy(site, diagnostic, "parentToolCallId");
        JsonObject raw = obj("jsonrpc", "2.0", "method", s(event, "type")), params = obj("kind", kind, "reason", problem);
        params.add("payload", site.get("payload"));
        raw.add("params", params);
        diagnostic.add("rawEvent", raw);
        SharedModel.validateEvent(diagnostic);
        return diagnostic;
    }

    private void flushText() {
        for (var entry : pendingText.entrySet()) {
            publish(entry.getValue());
            textTimes.put(entry.getKey(), now.getAsLong());
        }
        pendingText.clear();
        trim(textTimes, 1024);
    }

    private void emit(JsonObject e) {
        String type = s(e, "type");
        if (textFlushMs>0 && e.has("delta") && ThreadEventGrammar.STREAMING.contains(type)) {
            String k = type + '\u001f' + s(e, "itemId");
            Long time = textTimes.get(k);
            if (b(e, "reset") || time == null) {
                flushText();
                publish(e);
                textTimes.put(k, now.getAsLong());
                trim(textTimes, 1024);
                return;
            }
            JsonObject previous = pendingText.get(k);
            if (previous == null) pendingText.put(k, e);
            else previous.addProperty("delta", s(previous, "delta") + s(e, "delta"));
            if (now.getAsLong()-time>=textFlushMs) flushText();
            return;
        }
        flushText();
        publish(e);
    }

    private String ensureTurn() {
        if (current != null) return current;
        current = newTurn();
        emit(event("turn/started", current));
        for (String id : accepted) {
            JsonObject e = event("turn/input/accepted", current);
            e.addProperty("clientRequestId", id);
            emit(e);
        }
        accepted.clear();
        return current;
    }

    private void finish() {
        last = current != null ? current : last;
        current = null;
        open.entrySet().removeIf(e -> !e.getValue().attached);
        pendingProgress.entrySet().removeIf(e -> s(e.getValue().getAsJsonObject("scope"), "kind").equals("turn"));
        snapshots.clear();
    }

    private void rememberSettled(String k) {
        settled.add(k);
        while (settled.size()>512) settled.remove(settled.iterator().next());
    }

    private void fallback(JsonObject d, JsonObject k) {
        if (!d.has("noTurnFallback")) return;
        JsonObject f = d.getAsJsonObject("noTurnFallback"), e = event("provider/unhandled", null);
        e.addProperty("providerId", providerId);
        copy(f, e, "rawType");
        e.add("rawEvent", f.get("raw"));
        String p = parent(k);
        if (p != null) e.addProperty("parentToolCallId", p);
        emit(e);
    }

    private static boolean attached(JsonObject shape) {
        return s(shape, "type").equals("backgroundTask") || (s(shape, "type").equals("delegation") && b(shape, "background"));
    }

    private static String itemType(String type) {
        return switch (type) {
            case "command" -> "commandExecution";
            case "tool" -> "toolCall";
            case "compaction" -> "contextCompaction";
            default -> type;
        };
    }

    private JsonObject build(String id, JsonObject shape, String status, String p, JsonElement presentation, JsonObject close) {
        JsonObject item = shape.deepCopy();
        String type = s(shape, "type");
        item.addProperty("type", itemType(type));
        item.addProperty("id", id);
        if (p != null) item.addProperty("parentToolCallId", p);
        if (presentation != null) item.add("presentation", presentation.deepCopy());
        if (Set.of("command", "fileChange", "tool", "imageGeneration", "fileRead", "search", "delegation", "planSteps", "extension").contains(type)) item.addProperty("status", status);
        if (type.equals("command") || type.equals("fileChange")) {
            item.add("approvalStatus", JsonNull.INSTANCE);
            if (close != null) copy(close, item, "approvalStatus");
        }
        if (type.equals("tool")) {
            item.remove("args");
            if (shape.has("args") && shape.get("args").isJsonObject()) item.add("arguments", shape.get("args").deepCopy());
            if (!shape.has("result") && close != null && close.has("resultText")) item.add("result", close.get("resultText"));
        }
        if (type.equals("webSearch") || type.equals("webFetch")) {
            item.add("resultText", close != null && close.has("resultText") ? close.get("resultText") : JsonNull.INSTANCE);
            if (type.equals("webFetch") && !item.has("prompt")) item.add("prompt", JsonNull.INSTANCE);
        }
        if (type.equals("command") && close != null) copy(close, item, "exitCode", "aggregatedOutput");
        if (type.equals("fileChange")) for (JsonElement c : item.getAsJsonArray("changes")) {
            JsonObject change = c.getAsJsonObject();
            if (!change.has("diff") && change.has("newText")) {
                String diff = editDiff(s(change, "path"), change.has("oldText") ? s(change, "oldText") : null, s(change, "newText"));
                if (diff != null) change.addProperty("diff", diff);
            };
            change.remove("oldText");
            change.remove("newText");
        }
        return item;
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(Arrays.asList(text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)));
        if (lines.getLast().isEmpty()) lines.removeLast();
        return lines;
    }

    private static String editDiff(String path, String oldText, String newText) {
        path = path.replace('\\', '/').replaceFirst("^/+", "");
        List<String> oldLines = lines(oldText), newLines = lines(newText);
        int prefix = 0, suffix = 0;
        while (prefix < Math.min(oldLines.size(), newLines.size()) && oldLines.get(prefix).equals(newLines.get(prefix))) prefix++;
        while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix && oldLines.get(oldLines.size()-1-suffix).equals(newLines.get(newLines.size()-1-suffix))) suffix++;
        oldLines = oldLines.subList(prefix, oldLines.size()-suffix);
        newLines = newLines.subList(prefix, newLines.size()-suffix);
        StringBuilder body = new StringBuilder();
        int columns = newLines.size() + 1;
        if ((long)(oldLines.size() + 1)*columns > 1_000_000) {
            for (String line : oldLines) body.append('-').append(line).append('\n');
            for (String line : newLines) body.append('+').append(line).append('\n');
        }
        else {
            int[] lcs = new int[(oldLines.size() + 1)*columns];
            for (int i = oldLines.size()-1; i>=0; i--) for (int j = newLines.size()-1; j>=0; j--) lcs[i*columns + j] = oldLines.get(i).equals(newLines.get(j)) ? lcs[(i + 1)*columns + j + 1] + 1 : Math.max(lcs[(i + 1)*columns + j], lcs[i*columns + j + 1]);
            int i = 0, j = 0;
            while (i<oldLines.size() && j<newLines.size()) {
                if (oldLines.get(i).equals(newLines.get(j))) {
                    i++;
                    j++;
                }
                else if (lcs[(i + 1)*columns + j]>=lcs[i*columns + j + 1]) body.append('-').append(oldLines.get(i++)).append('\n');
                else body.append('+').append(newLines.get(j++)).append('\n');
            }
            while (i<oldLines.size()) body.append('-').append(oldLines.get(i++)).append('\n');
            while (j<newLines.size()) body.append('+').append(newLines.get(j++)).append('\n');
        }
        if (body.isEmpty() && oldText != null && newText != null) return null;
        return (oldText == null ? "--- /dev/null" : "--- a/" + path) + "\n" + (newText == null ? "+++ /dev/null" : "+++ b/" + path) + "\n" + body;
    }

    private void itemEvent(String type, String turn, JsonObject item) {
        JsonObject e = event(type, turn);
        e.add("item", item);
        emit(e);
    }

    private JsonObject textItem(String id, String channel, String text, String p) {
        JsonObject item = obj("type", channel.startsWith("reasoning") ? "reasoning" : channel, "id", id);
        if (channel.startsWith("reasoning")) {
            JsonArray values = new JsonArray();
            values.add(text);
            item.add("summary", channel.equals("reasoningSummary") ? values : new JsonArray());
            item.add("content", channel.equals("reasoningText") ? values : new JsonArray());
        }
        else item.addProperty("text", text);
        if (p != null) item.addProperty("parentToolCallId", p);
        return item;
    }

    private JsonObject settleText(Open o, String finalText, String channel) {
        String type = s(o.item, "type");
        if (!Set.of("agentMessage", "plan", "reasoning").contains(type)) return null;
        JsonObject item = o.item.deepCopy();
        String text = "reasoningSummary".equals(channel) ? o.text : finalText == null ? o.text : finalText, summary = "reasoningSummary".equals(channel) && finalText != null ? finalText : o.summary;
        if (type.equals("reasoning")) {
            JsonArray content = new JsonArray(), summaries = new JsonArray();
            if (!text.isEmpty()) content.add(text);
            if (!summary.isEmpty()) summaries.add(summary);
            item.add("content", content);
            item.add("summary", summaries);
        }
        else item.addProperty("text", text);
        return item;
    }

    private void handle(JsonObject d) {
        String kind = s(d, "kind");
        JsonObject k = d.has("key") ? d.getAsJsonObject("key") : new JsonObject();
        String ks = key(k), t;
        switch (kind) {
            case "session.reset" -> {
                turns.clear();
                nativeTurns.clear();
                items.clear();
                snapshots.clear();
                open.clear();
                settled.clear();
                accepted.clear();
                pendingProgress.clear();
                progressTimes.clear();
                textTimes.clear();
                current = null;
                last = null;
                grammar.clear();
            }
            case "input.accepted" -> {
                t = turn(d, false);
                if (t == null) accepted.add(s(d, "clientRequestId"));
                else {
                    JsonObject e = event("turn/input/accepted", t);
                    copy(d, e, "clientRequestId");
                    emit(e);
                }
            }
            case "input.provider" -> {
                if (current == null) return;
                JsonObject item = obj("type", "userMessage", "id", newItem());
                JsonArray content = new JsonArray();
                content.add(obj("type", "text", "text", s(d, "text")));
                item.add("content", content);
                String p = parent(d);
                if (p != null) item.addProperty("parentToolCallId", p);
                itemEvent("item/completed", current, item);
            }
            case "turn.open" -> {
                if (!d.has("providerTurnId")) {
                    ensureTurn();
                    return;
                }
                JsonObject e = event("turn/started", resolveTurn(s(d, "providerTurnId")));
                String p = parent(d);
                if (p != null) e.addProperty("parentToolCallId", p);
                emit(e);
            }
            case "turn.boundary" -> {
                t = turn(d, false);
                if (t == null && b(d, "claimIfIdle") && !accepted.isEmpty()) t = ensureTurn();
                if (t == null) return;
                JsonObject e = event("turn/completed", t);
                copy(d, e, "status", "error", "providerCheckpointId");
                emit(e);
                if (!d.has("providerTurnId")) finish();
            }
            case "item.open" -> {
                t = turn(d, s(d, "attach").equals("currentOrLast"));
                if (t == null) {
                    fallback(d, k);
                    return;
                }
                JsonObject shape = d.getAsJsonObject("item");
                settled.remove(ks);
                if (!s(shape, "type").equals("compaction")) open.entrySet().removeIf(entry -> s(entry.getValue().key, "providerItemId").isEmpty() && s(entry.getValue().item, "type").equals("agentMessage") && s(entry.getValue().key, "parentRef").equals(s(k, "parentRef")));
                String id = itemId(k);
                JsonObject item = build(id, shape, "pending", parent(k), d.get("presentation"), null);
                open.put(ks, new Open(id, k.deepCopy(), item, attached(shape)));
                progressTimes.put(ks, now.getAsLong());
                itemEvent("item/started", t, item);
            }
            case "item.close" -> close(d, k, ks);
            case "item.textDelta" -> textDelta(d, k, ks);
            case "item.textClose" -> textClose(d, k, ks);
            case "item.outputDelta" -> {
                t = turn(d, false);
                if (t == null) {
                    fallback(d, k);
                    return;
                }
                Open o = open.get(ks);
                JsonObject e = event(s(d, "channel").equals("command") ? "item/commandExecution/outputDelta" : "item/fileChange/outputDelta", t);
                e.addProperty("itemId", o == null ? itemId(k) : o.id);
                e.addProperty("delta", s(d, "text"));
                String p = parent(k);
                if (p != null) e.addProperty("parentToolCallId", p);
                emit(e);
            }
            case "command.outputSnapshot" -> {
                if (current == null) {
                    fallback(d, k);
                    return;
                }
                Open o = open.get(ks);
                if (o == null) return;
                String previous = snapshots.getOrDefault(ks, ""), next = s(d, "text");
                if (next.isEmpty() || next.equals(previous)) return;
                snapshots.put(ks, next);
                JsonObject e = event("item/commandExecution/outputDelta", current);
                e.addProperty("itemId", o.id);
                boolean reset = !next.startsWith(previous);
                e.addProperty("delta", reset ? next : next.substring(previous.length()));
                if (reset) e.addProperty("reset", true);
                String p = parent(k);
                if (p != null) e.addProperty("parentToolCallId", p);
                emit(e);
            }
            case "item.progress" -> progress(d, k, ks);
            case "usage" -> {
                t = turn(d, true);
                if (t == null) return;
                JsonObject e = event("thread/tokenUsage/updated", t), usage = new JsonObject();
                copy(d, usage, "total", "last", "modelContextWindow");
                e.add("tokenUsage", usage);
                emit(e);
            }
            case "contextWindow" -> {
                t = turn(d, !s(d, "attach").equals("open"));
                JsonObject e = event("thread/contextWindowUsage/updated", t), usage = new JsonObject(), snapshot = d.has("snapshot") ? d.getAsJsonObject("snapshot") : new JsonObject();
                usage.add("usedTokens", snapshot.has("usedTokens") && !snapshot.get("usedTokens").isJsonNull() ? snapshot.get("usedTokens") : d.get("used"));
                usage.add("modelContextWindow", snapshot.has("contextWindowTokens") && !snapshot.get("contextWindowTokens").isJsonNull() ? snapshot.get("contextWindowTokens") : d.has("size") ? d.get("size") : JsonNull.INSTANCE);
                usage.add("estimated", snapshot.has("estimated") ? snapshot.get("estimated") : d.get("estimated"));
                copy(d, usage, "snapshot");
                e.add("contextWindowUsage", usage);
                emit(e);
            }
            case "context.compacted", "context.cleared" -> {
                t = kind.equals("context.cleared") ? (current != null ? current : last) : turn(d, true);
                if (t == null) {
                    if (kind.equals("context.compacted")) fallback(d, k);
                    return;
                }
                emit(event(kind.equals("context.compacted") ? "thread/compacted" : "thread/context/cleared", t));
            }
            case "provider.error" -> {
                t = d.has("providerTurnId") ? resolveTurn(s(d, "providerTurnId")) : b(d, "threadScoped") ? null : current != null ? current : !accepted.isEmpty() ? ensureTurn() : null;
                JsonObject e = event("provider/error", t);
                copy(d, e, "message", "detail", "willRetry", "errorInfo");
                emit(e);
                if (b(d, "settlesTurn") && t != null) {
                    JsonObject end = event("turn/completed", t);
                    end.addProperty("status", "failed");
                    emit(end);
                    finish();
                }
            }
            case "provider.modelFallback" -> {
                JsonObject e = event("provider/modelFallback", current != null ? current : last);
                copy(d, e, "originalModel", "fallbackModel", "reason", "message");
                emit(e);
            }
            case "provider.warning" -> {
                JsonObject e = event("provider/warning", b(d, "vouchedTurn") ? current : null);
                e.addProperty("category", d.has("category") ? s(d, "category") : "general");
                copy(d, e, "summary", "details");
                emit(e);
            }
            case "unhandled" -> {
                if (b(d, "onlyIfNoTurn") && current != null) return;
                t = d.has("providerTurnId") ? resolveTurn(s(d, "providerTurnId")) : b(d, "vouchedTurn") ? current : null;
                JsonObject e = event("provider/unhandled", t);
                e.addProperty("providerId", providerId);
                copy(d, e, "rawType");
                e.add("rawEvent", d.get("raw"));
                String p = parent(d);
                if (p != null) e.addProperty("parentToolCallId", p);
                emit(e);
            }
            case "turn.diff" -> {
                t = turn(d, false);
                if (t != null) {
                    JsonObject e = event("turn/diff/updated", t);
                    copy(d, e, "diff");
                    emit(e);
                }
            }
            case "thread.started" -> {
                JsonObject e = event("thread/started", null);
                e.remove("providerThreadId");
                emit(e);
            }
            case "thread.identity" -> {
                JsonObject e = event("thread/identity", null);
                copy(d, e, "providerThreadId");
                emit(e);
            }
            case "thread.name" -> {
                JsonObject e = event("thread/name/updated", null);
                e.addProperty("threadName", s(d, "name"));
                emit(e);
            }
            case "provider.rateLimits" -> {
                JsonObject e = event("provider/rateLimits/updated", null);
                copy(d, e, "rateLimits");
                emit(e);
            }
            case "extension.state" -> {
                JsonObject e = event("thread/extensionState/updated", null);
                e.addProperty("kind", s(d, "extensionKind"));
                copy(d, e, "payload");
                emit(e);
            }
            case "session.ended" -> {
                t = current != null ? current : !accepted.isEmpty() ? ensureTurn() : null;
                if (t == null) return;
                for (Open o : new ArrayList<>(open.values())) if (!o.attached) {
                    JsonObject item = (!o.text.isEmpty() || !o.summary.isEmpty()) ? settleText(o, null, null) : null;
                    if (item == null) {
                        item = o.item.deepCopy();
                        if (item.has("status")) item.addProperty("status", "interrupted");
                    }
                    itemEvent("item/completed", t, item);
                }
                JsonObject e = event("turn/completed", t);
                e.addProperty("status", "interrupted");
                emit(e);
                finish();
            }
            default -> throw new IllegalArgumentException("Unsupported shared delta " + kind);
        }
    }

    private void close(JsonObject d, JsonObject k, String ks) {
        JsonObject shape = d.getAsJsonObject("item");
        boolean threadScoped = attached(shape);
        String t = threadScoped ? null : turn(d, false);
        if (!threadScoped && t == null) {
            fallback(d, k);
            return;
        }
        if (k.has("providerItemId") && settled.contains(ks)) return;
        Open o = open.get(ks);
        String id = o == null ? itemId(k) : o.id, p = parent(k);
        if (p == null && o != null) p = o.item.has("parentToolCallId") ? s(o.item, "parentToolCallId") : null;
        JsonElement presentation = d.has("presentation") ? d.get("presentation") : o == null ? null : o.item.get("presentation");
        if (o != null && !itemType(s(shape, "type")).equals(s(o.item, "type")) && t != null) {
            JsonObject previous = o.item.deepCopy();
            if (previous.has("status")) previous.addProperty("status", s(d, "status"));
            itemEvent("item/completed", t, previous);
        }
        JsonObject item = build(id, shape, s(d, "status"), p, presentation, d);
        if (s(shape, "type").equals("delegation") && !item.has("summary") && o != null) copy(o.item, item, "summary");
        register(s(k, "providerItemId"), id);
        open.remove(ks);
        snapshots.remove(ks);
        pendingProgress.remove(ks);
        progressTimes.remove(ks);
        if (k.has("providerItemId")) rememberSettled(ks);
        String type = s(item, "type");
        itemEvent(type.equals("backgroundTask") ? "item/backgroundTask/completed" : type.equals("delegation") && b(item, "background") ? "item/delegation/completed" : "item/completed", t, item);
    }

    private void textDelta(JsonObject d, JsonObject k, String ks) {
        String t = turn(d, false);
        if (t == null) {
            fallback(d, k);
            return;
        }
        Open o = open.get(ks);
        String id = o == null ? items.get(s(k, "providerItemId")) : o.id, p = parent(k), channel = s(d, "channel");
        if (id == null) {
            id = itemId(k);
            JsonObject item = textItem(id, channel, "", p);
            if (channel.startsWith("reasoning")) {
                item.add("summary", new JsonArray());
                item.add("content", new JsonArray());
            }
            o = new Open(id, k.deepCopy(), item, false);
            open.put(ks, o);
            itemEvent("item/started", t, item);
        }
        if (o != null) {
            if (channel.equals("reasoningSummary")) o.summary+=s(d, "text");
            else o.text+=s(d, "text");
        }
        String type = switch (channel) {
            case "agentMessage" -> "item/agentMessage/delta";
            case "plan" -> "item/plan/delta";
            case "reasoningSummary" -> "item/reasoning/summaryTextDelta";
            default -> "item/reasoning/textDelta";
        };
        JsonObject e = event(type, t);
        e.addProperty("itemId", id);
        e.addProperty("delta", s(d, "text"));
        if (p != null) e.addProperty("parentToolCallId", p);
        emit(e);
    }

    private void textClose(JsonObject d, JsonObject k, String ks) {
        String t = turn(d, false);
        if (t == null) {
            fallback(d, k);
            return;
        }
        if (k.has("providerItemId") && settled.contains(ks)) return;
        Open o = open.remove(ks);
        String channel = s(d, "channel"), text = d.has("text") ? s(d, "text") : o == null ? null : channel.equals("reasoningSummary") ? o.summary : o.text;
        if (text == null || text.isEmpty() || (!d.has("text") && text.isBlank())) return;
        JsonObject item = o == null ? null : settleText(o, d.has("text") ? text : null, channel);
        String id = o == null ? itemId(k) : o.id;
        if (item == null) item = textItem(id, channel, text, parent(k));
        if (k.has("providerItemId")) {
            register(s(k, "providerItemId"), id);
            rememberSettled(ks);
        }
        itemEvent("item/completed", t, item);
    }

    private void progress(JsonObject d, JsonObject k, String ks) {
        Open o = open.get(ks);
        String id = o == null ? items.get(s(k, "providerItemId")) : o.id;
        if (id == null) id = k.has("providerItemId") ? s(k, "providerItemId") : newItem();
        String p = parent(k);
        JsonObject e;
        if (d.has("snapshot")) {
            JsonObject shape = d.getAsJsonObject("snapshot"), item = build(id, shape, "pending", p, o == null || !s(shape, "type").equals("delegation") ? null : o.item.get("presentation"), null);
            if (s(shape, "type").equals("delegation") && !item.has("summary") && o != null) copy(o.item, item, "summary");
            e = event(s(shape, "type").equals("delegation") ? "item/delegation/progress" : "item/backgroundTask/progress", null);
            e.add("item", item);
        }
        else {
            String t = turn(d, false);
            if (t == null) {
                fallback(d, k);
                return;
            }
            e = event("item/toolCall/progress", t);
            e.addProperty("itemId", id);
            copy(d, e, "message");
            if (p != null) e.addProperty("parentToolCallId", p);
        }
        Long time = progressTimes.get(ks);
        if (!b(d, "flush") && time != null && now.getAsLong()-time<progressThrottleMs) {
            pendingProgress.put(ks, e);
            return;
        }
        pendingProgress.remove(ks);
        progressTimes.put(ks, now.getAsLong());
        trim(progressTimes, 1024);
        emit(e);
    }
}
