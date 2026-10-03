package toomanyagents.agent;

import com.google.gson.*;
import java.util.*;

/** Codex native items to shared semantic deltas. */
final class CodexProtocol {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final Map<String, JsonObject> rateLimits = new LinkedHashMap<>();
    private final Map<String, JsonObject> retryErrors = new HashMap<>();
    private final Map<String, Map<String, JsonObject>> deferredCommands = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> capturedOutput = new HashMap<>();
    private final Map<String, Set<String>> shellCalls = new HashMap<>();
    private final Map<String, Set<String>> openTurns = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<String>> completedTurns = new HashMap<>();
    private final Map<String, String> childOwners = new HashMap<>();
    private final Map<String, String> threadParents = new HashMap<>();
    private final Map<String, String> turnParents = new HashMap<>();
    private final Map<String, List<PendingLink>> pendingLinks = new LinkedHashMap<>();
    private final Map<String, TrackedAgent> agents = new LinkedHashMap<>();
    private final Map<String, String> agentCalls = new HashMap<>();
    private final Map<String, String> interactionKinds = new HashMap<>();
    private final Map<String, JsonObject> unclassifiedInteractions = new LinkedHashMap<>();
    private final Set<String> processedInteractions = new HashSet<>();
    private final Set<String> userTurnStarts = new HashSet<>();

    private record PendingLink(String call, String parentTurn) {}
    private static final class TrackedAgent {
        String call, parentThread, parentTurn, childThread, path, parentRef;
        boolean terminal;
        int followups;
    }

    synchronized List<JsonObject> translate(String method, JsonObject params) {
        try { return translateStateful(method, params); }
        catch (IllegalArgumentException | IllegalStateException malformed) {
            return List.of(unhandled(method, params));
        }
    }

    /** Marks a user turn dispatch so it cannot accidentally consume a queued child-turn link. */
    synchronized void expectUserTurn(String nativeThreadId) { userTurnStarts.add(nativeThreadId); }
    synchronized void cancelExpectedUserTurn(String nativeThreadId) { userTurnStarts.remove(nativeThreadId); }

    /** Known child streams belong to the same Too Many Agents conversation as their native root. */
    synchronized String rootThread(String nativeThreadId) {
        Set<String> seen = new HashSet<>();
        while (seen.add(nativeThreadId) && childOwners.containsKey(nativeThreadId)) nativeThreadId = childOwners.get(nativeThreadId);
        return nativeThreadId;
    }

    synchronized boolean hasWork(String nativeThreadId) {
        for (var entry:openTurns.entrySet()) if (!entry.getValue().isEmpty() && (entry.getKey().equals(nativeThreadId) || rootThread(entry.getKey()).equals(nativeThreadId))) return true;
        for (var entry:deferredCommands.entrySet()) if (!entry.getValue().isEmpty() && (entry.getKey().equals(nativeThreadId) || rootThread(entry.getKey()).equals(nativeThreadId))) return true;
        for (TrackedAgent tracked:agents.values()) if ((!tracked.terminal || tracked.followups>0)
                && (tracked.parentThread.equals(nativeThreadId) || tracked.childThread.equals(nativeThreadId) || rootThread(tracked.parentThread).equals(nativeThreadId))) return true;
        return false;
    }

    private List<JsonObject> translateStateful(String method, JsonObject params) {
        String thread = text(params,"threadId");
        if (method.equals("thread/closed")) return settle(thread,"failed");
        if (method.equals("rawResponseItem/completed")) return rawResponse(params);
        JsonObject nativeItem = obj(params,"item");
        if (text(nativeItem,"type").equals("subAgentActivity")) {
            return method.equals("item/completed") ? subAgent(params) : List.of();
        }
        List<JsonObject> result = new ArrayList<>();
        for (JsonObject delta : translateValid(method,params)) {
            String kind=text(delta,"kind"), turn=text(delta,"providerTurnId");
            if (kind.equals("turn.open")) {
                if (completedTurns.getOrDefault(thread,new LinkedHashSet<>()).contains(turn)) continue;
                boolean userStart=userTurnStarts.remove(thread);
                if (!userStart && !hasPendingLink(thread,turn)) {
                    JsonObject unknown=null; String unknownKey=null;
                    for (var entry:unclassifiedInteractions.entrySet()) {
                        JsonObject activity=entry.getValue();
                        if (!turn.equals(text(activity,"turnId")) && (thread.equals(text(activity,"threadId")) || thread.equals(text(obj(activity,"item"),"agentThreadId")))) { unknown=activity; unknownKey=entry.getKey(); }
                    }
                    if (unknown != null) { unclassifiedInteractions.remove(unknownKey); result.addAll(followup(unknown)); }
                }
                linkParent(thread,delta,!userStart);
                if (!openTurns.computeIfAbsent(thread,ignored->new LinkedHashSet<>()).add(turn)) continue;
            } else linkParent(thread,delta,false);
            observeDelegation(thread,delta);
            if (kind.equals("item.close") && text(obj(delta,"item"),"type").equals("command")) {
                String call=text(obj(delta,"key"),"providerItemId");
                Map<String,String> captured=capturedOutput.get(thread);
                if (captured != null && captured.containsKey(call)) {
                    repairOutput(delta,captured.remove(call));
                    Set<String> knownShells=shellCalls.get(thread);
                    if (knownShells != null) knownShells.remove(call);
                } else if (shellCalls.getOrDefault(thread,Set.of()).contains(call)) {
                    deferredCommands.computeIfAbsent(thread,ignored->new LinkedHashMap<>()).put(call,delta);
                    continue;
                }
            }
            if (kind.equals("item.outputDelta") && text(delta,"channel").equals("command")) {
                JsonObject pending=deferredCommands.getOrDefault(thread,Map.of()).get(text(obj(delta,"key"),"providerItemId"));
                if (pending != null) {
                    // A delayed native snapshot must not discard stdout that arrived after it.
                    JsonObject shape=obj(pending,"item");
                    shape.addProperty("aggregatedOutput",text(shape,"aggregatedOutput")+text(delta,"text"));
                }
            }
            if (kind.equals("turn.boundary")) {
                LinkedHashSet<String> completed=completedTurns.computeIfAbsent(thread,ignored->new LinkedHashSet<>());
                if (!completed.add(turn)) continue;
                if (completed.size()>256) completed.remove(completed.iterator().next());
                result.addAll(drain(thread,turn));
                openTurns.getOrDefault(thread,new LinkedHashSet<>()).remove(turn);
                result.add(delta);
                String call=turnParents.get(thread+"\0"+turn);
                TrackedAgent tracked=agents.get(call);
                if (tracked != null) { JsonObject close=completeAgent(tracked,text(delta,"status")); if (close != null) result.add(close); }
                unclassifiedInteractions.entrySet().removeIf(entry->thread.equals(text(entry.getValue(),"threadId")) && turn.equals(text(entry.getValue(),"turnId")));
            } else result.add(delta);
        }
        return result;
    }

    /** Drain native terminal command snapshots before the corresponding turn boundary. */
    synchronized List<JsonObject> drain(String nativeThreadId, String nativeTurnId) {
        List<JsonObject> result=new ArrayList<>();
        Map<String,JsonObject> pending=deferredCommands.get(nativeThreadId);
        if (pending != null) {
            var iterator=pending.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry=iterator.next();
                if (nativeTurnId == null || nativeTurnId.equals(text(entry.getValue(),"providerTurnId"))) { result.add(entry.getValue()); iterator.remove(); }
            }
            if (pending.isEmpty()) deferredCommands.remove(nativeThreadId);
        }
        if (!deferredCommands.containsKey(nativeThreadId)) { capturedOutput.remove(nativeThreadId); shellCalls.remove(nativeThreadId); }
        return result;
    }

    /** Terminal transport cleanup: emit keyed boundaries, never rely on session.ended to close them. */
    synchronized List<JsonObject> settle(String nativeThreadId, String terminalStatus) {
        if (!Set.of("interrupted","failed").contains(terminalStatus)) throw new IllegalArgumentException("Cleanup must interrupt or fail work");
        Set<String> affected=new LinkedHashSet<>(); affected.add(nativeThreadId);
        for (String child:childOwners.keySet()) {
            String ancestor=child; Set<String> seen=new HashSet<>();
            while (seen.add(ancestor) && childOwners.containsKey(ancestor)) {
                ancestor=childOwners.get(ancestor);
                if (ancestor.equals(nativeThreadId)) { affected.add(child); break; }
            }
        }
        List<JsonObject> result=new ArrayList<>();
        for (String thread:affected) {
            result.addAll(drain(thread,null));
            Set<String> turns=openTurns.remove(thread);
            if (turns != null) for (String turn:turns) result.add(object("kind","turn.boundary","providerTurnId",turn,"status",terminalStatus));
        }
        for (TrackedAgent tracked:new ArrayList<>(agents.values())) if (affected.contains(tracked.parentThread) || affected.contains(tracked.childThread)) {
            if (!tracked.terminal || tracked.followups>0) { tracked.followups=0; tracked.terminal=true; result.add(agentDelta(tracked,true,terminalStatus)); }
            clearLinks(tracked); agents.remove(tracked.call); agentCalls.remove(tracked.childThread,tracked.call);
        }
        for (String thread:affected) {
            retryErrors.keySet().removeIf(key->key.startsWith(thread+"\0"));
            pendingLinks.remove(thread); threadParents.remove(thread); userTurnStarts.remove(thread);
            completedTurns.remove(thread);
            turnParents.keySet().removeIf(key->key.startsWith(thread+"\0"));
            interactionKinds.keySet().removeIf(key->key.startsWith(thread+"\0"));
            unclassifiedInteractions.entrySet().removeIf(entry->thread.equals(text(entry.getValue(),"threadId")));
            processedInteractions.removeIf(key->key.startsWith(thread+"\0"));
        }
        childOwners.entrySet().removeIf(entry->affected.contains(entry.getKey()) || affected.contains(entry.getValue()));
        return result;
    }

    private List<JsonObject> rawResponse(JsonObject params) {
        String thread=required(params,"threadId"); JsonObject item=obj(params,"item");
        String type=text(item,"type"), call=text(item,"call_id"), key=thread+"\0"+call;
        if (type.equals("function_call")) {
            String name=text(item,"name");
            if (name.equals("followup_task") || name.equals("send_message")) {
                JsonObject pending=unclassifiedInteractions.remove(key);
                if (pending != null) return name.equals("followup_task") ? followup(pending) : List.of();
                if (!processedInteractions.contains(key)) interactionKinds.put(key,name);
            } else if (Set.of("exec_command","Bash","bash").contains(name)) shellCalls.computeIfAbsent(thread,ignored->new HashSet<>()).add(call);
        } else if (type.equals("function_call_output") && shellCalls.getOrDefault(thread,Set.of()).contains(call)) {
            String output=recoverOutput(item.get("output"));
            if (output != null) capturedOutput.computeIfAbsent(thread,ignored->new HashMap<>()).put(call,output);
            else shellCalls.get(thread).remove(call);
            Map<String,JsonObject> deferred=deferredCommands.get(thread);
            JsonObject close=deferred == null ? null : deferred.remove(call);
            if (close != null) {
                if (output != null) { repairOutput(close,output); capturedOutput.get(thread).remove(call); }
                shellCalls.getOrDefault(thread,new HashSet<>()).remove(call);
                return List.of(close);
            }
        }
        return List.of();
    }

    private static void repairOutput(JsonObject close,String output) {
        JsonObject shape=obj(close,"item");
        if (output.isEmpty()) shape.remove("aggregatedOutput"); else shape.addProperty("aggregatedOutput",output);
    }

    private static String recoverOutput(JsonElement raw) {
        String text=resultText(raw).replace("\r\n","\n").replace('\r','\n');
        if (text.isEmpty()) return "";
        int firstEnd=text.indexOf('\n');
        String first=firstEnd<0 ? text : text.substring(0,firstEnd);
        if (first.equals("Output:")) return firstEnd<0 ? "" : text.substring(firstEnd+1);
        if (!outputMetadata(first)) return text;
        int cursor=firstEnd<0 ? text.length() : firstEnd+1, metadata=1;
        while (cursor<=text.length()) {
            int end=text.indexOf('\n',cursor); String line=end<0 ? text.substring(cursor) : text.substring(cursor,end);
            if (line.equals("Output:")) return end<0 ? "" : text.substring(end+1);
            if (!outputMetadata(line)) {
                boolean laterMarker=Arrays.stream(text.substring(cursor).split("\n",-1)).anyMatch("Output:"::equals);
                return laterMarker ? null : text;
            }
            metadata++;
            if (end<0 || end+1>=text.length()) return metadata==1 ? text : null;
            cursor=end+1;
        }
        return null;
    }
    private static boolean outputMetadata(String line) {
        return List.of("Chunk ID:","Wall time:","Process exited with code ","Original token count:").stream().anyMatch(line::startsWith);
    }
    private static String resultText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsJsonPrimitive().isString() ? value.getAsString() : value.toString();
        if (value.isJsonArray()) {
            StringJoiner parts=new StringJoiner("\n");
            for (JsonElement part:value.getAsJsonArray()) { String text=resultText(part); if (!text.isBlank()) parts.add(text); }
            return parts.toString();
        }
        JsonObject object=value.getAsJsonObject();
        if (object.has("text")) return text(object,"text");
        if (object.has("content")) return resultText(object.get("content"));
        return object.toString();
    }

    private List<JsonObject> subAgent(JsonObject params) {
        JsonObject item=obj(params,"item");
        String call=required(item,"id"), thread=required(params,"threadId"), child=required(item,"agentThreadId"), kind=required(item,"kind");
        required(params,"turnId"); required(item,"agentPath");
        if (kind.equals("completed")) return List.of();
        if (kind.equals("started")) return agents.containsKey(call) ? List.of() : beginAgent(params);
        TrackedAgent tracked=agents.get(agentCalls.get(child));
        if (kind.equals("interrupted")) { JsonObject close=tracked==null ? null : completeAgent(tracked,"interrupted"); return close==null ? List.of() : List.of(close); }
        if (!kind.equals("interacted")) throw new IllegalArgumentException("Unknown subagent activity kind");
        String key=thread+"\0"+call;
        if (!processedInteractions.add(key)) return List.of();
        String interaction=interactionKinds.remove(key);
        if ("send_message".equals(interaction) || tracked!=null && !tracked.terminal) return List.of();
        if ("followup_task".equals(interaction)) return followup(params);
        unclassifiedInteractions.put(key,params.deepCopy());
        return List.of();
    }

    private List<JsonObject> beginAgent(JsonObject params) {
        JsonObject item=obj(params,"item"); TrackedAgent tracked=new TrackedAgent();
        tracked.call=text(item,"id"); tracked.parentThread=text(params,"threadId"); tracked.parentTurn=text(params,"turnId");
        tracked.childThread=text(item,"agentThreadId"); tracked.path=text(item,"agentPath");
        JsonObject delta=agentDelta(tracked,false,""); linkParent(tracked.parentThread,delta,false);
        tracked.parentRef=text(obj(delta,"key"),"parentRef");
        agents.put(tracked.call,tracked); agentCalls.put(tracked.childThread,tracked.call); rearmAgent(tracked);
        return List.of(delta);
    }
    private List<JsonObject> followup(JsonObject params) {
        TrackedAgent tracked=agents.get(agentCalls.get(text(obj(params,"item"),"agentThreadId")));
        if (tracked==null) return beginAgent(params);
        if (!tracked.terminal) return List.of();
        boolean open=tracked.followups>0;
        tracked.followups++; rearmAgent(tracked);
        return open ? List.of() : List.of(agentDelta(tracked,false,""));
    }
    private void rearmAgent(TrackedAgent tracked) {
        agentCalls.put(tracked.childThread,tracked.call);
        if (!tracked.childThread.equals(tracked.parentThread)) {
            threadParents.put(tracked.childThread,tracked.call); childOwners.put(tracked.childThread,tracked.parentThread);
        }
        enqueueLink(tracked.parentThread,tracked.call,tracked.parentTurn);
    }
    private JsonObject completeAgent(TrackedAgent tracked,String status) {
        boolean open=!tracked.terminal || tracked.followups>0, terminal=tracked.terminal;
        tracked.terminal=true; clearLinks(tracked);
        if (terminal && tracked.followups>0) tracked.followups--;
        if (tracked.followups>0) rearmAgent(tracked);
        return !open || tracked.followups>0 ? null : agentDelta(tracked,true,status);
    }
    private void clearLinks(TrackedAgent tracked) {
        for (List<PendingLink> links:pendingLinks.values()) links.removeIf(link->link.call.equals(tracked.call));
        threadParents.entrySet().removeIf(entry->entry.getValue().equals(tracked.call));
        turnParents.entrySet().removeIf(entry->entry.getValue().equals(tracked.call));
    }
    private JsonObject agentDelta(TrackedAgent tracked,boolean close,String status) {
        JsonObject key=object("providerItemId",tracked.call);
        if (tracked.parentRef!=null && !tracked.parentRef.isBlank()) key.addProperty("parentRef",tracked.parentRef);
        JsonObject delta=object("kind",close ? "item.close" : "item.open","key",key,
            "item",object("type","delegation","childRef",tracked.childThread,"label",tracked.path,"background",false),
            "presentation",present("Running agent","Agent finished","UserRound",tracked.path),"providerTurnId",tracked.parentTurn);
        if (close) delta.addProperty("status",status);
        return delta;
    }
    private boolean hasPendingLink(String thread,String turn) {
        return pendingLinks.getOrDefault(thread,List.of()).stream().anyMatch(link->!link.parentTurn.equals(turn));
    }
    private void enqueueLink(String thread,String call,String parentTurn) {
        if (thread.isBlank() || parentTurn.isBlank()) return;
        if (pendingLinks.values().stream().flatMap(Collection::stream).anyMatch(link->link.call.equals(call))) return;
        pendingLinks.computeIfAbsent(thread,ignored->new ArrayList<>()).add(new PendingLink(call,parentTurn));
    }
    private void linkParent(String thread,JsonObject delta,boolean consumePending) {
        String kind=text(delta,"kind"), turn=text(delta,"providerTurnId");
        JsonObject target=delta.has("key") ? obj(delta,"key") : delta;
        String parent=text(target,"parentRef");
        if (parent.isBlank()) parent=turnParents.getOrDefault(thread+"\0"+turn,"");
        if (parent.isBlank()) parent=threadParents.getOrDefault(thread,"");
        if (parent.isBlank() && kind.equals("turn.open") && consumePending) {
            List<PendingLink> pending=pendingLinks.get(thread);
            if (pending!=null) while (!pending.isEmpty()) { PendingLink link=pending.remove(0); if (!link.parentTurn.equals(turn)) { parent=link.call; break; } }
        }
        if (!parent.isBlank()) {
            if (kind.equals("turn.open")) {
                turnParents.put(thread+"\0"+turn,parent);
                final String selected=parent;
                for (List<PendingLink> links:pendingLinks.values()) links.removeIf(link->link.call.equals(selected));
            }
            if (delta.has("key") || kind.equals("turn.open") || kind.equals("unhandled")) target.addProperty("parentRef",parent);
        }
    }
    private void observeDelegation(String thread,JsonObject delta) {
        String kind=text(delta,"kind"), type=text(obj(delta,"item"),"type");
        if (!kind.equals("item.open") && !kind.equals("item.close")) return;
        JsonObject item=obj(delta,"item"); String call=text(obj(delta,"key"),"providerItemId");
        if (call.isBlank()) return;
        JsonArray children=new JsonArray(); String sender="";
        if (type.equals("delegation")) children.add(text(item,"childRef"));
        else if (type.equals("tool") && Set.of("spawnAgent","resumeAgent").contains(text(item,"tool"))) { children=array(obj(item,"args"),"receiverThreadIds"); sender=text(obj(item,"args"),"senderThreadId"); }
        else return;
        if (children.isEmpty()) enqueueLink(thread,call,text(delta,"providerTurnId"));
        for (JsonElement value:children) {
            String child=value.getAsString(); if (child.isBlank()) continue;
            if (child.equals(thread) || child.equals(sender)) enqueueLink(thread,call,text(delta,"providerTurnId"));
            else { threadParents.put(child,call); childOwners.put(child,thread); }
        }
    }

    private List<JsonObject> translateValid(String method, JsonObject params) {
        List<JsonObject> out = new ArrayList<>();
        String turn = text(params, "turnId");
        JsonObject delta;
        switch (method) {
            case "turn/started" -> delta = object("kind", "turn.open", "providerTurnId", required(obj(params, "turn"), "id"));
            case "thread/started" -> {
                JsonObject thread = obj(params, "thread");
                out.add(object("kind", "thread.started"));
                out.add(object("kind", "thread.identity", "providerThreadId", required(thread, "id")));
                if (!text(thread, "preview").isBlank()) out.add(object("kind", "thread.name", "name", text(thread, "preview")));
                return out;
            }
            case "turn/completed" -> {
                JsonObject value = obj(params, "turn");
                retryErrors.remove(text(params, "threadId") + "\0" + text(value, "id"));
                delta = object("kind", "turn.boundary", "status", text(value,"status").equals("inProgress") ? "completed" : status(required(value, "status")), "providerTurnId", required(value, "id"), "providerCheckpointId", text(value,"id"));
                if (value.has("error") && value.get("error").isJsonObject()) delta.add("error", object("message", text(obj(value, "error"), "message")));
            }
            case "item/started", "item/completed" -> {
                JsonObject nativeItem = obj(params, "item");
                required(nativeItem, "id");
                if (Set.of("userMessage", "subAgentActivity").contains(text(nativeItem, "type"))) return out;
                if (text(nativeItem,"type").equals("webSearch") && (!nativeItem.has("action") || nativeItem.get("action").isJsonNull() || text(obj(nativeItem,"action"),"type").equals("other"))) return out;
                JsonObject shape = shape(nativeItem);
                if (shape == null) { out.add(unhandled(method, params)); return out; }
                delta = object("kind", method.equals("item/started") ? "item.open" : "item.close",
                    "key", object("providerItemId", text(nativeItem, "id")), "item", shape,
                    "presentation", presentation(shape));
                if (text(nativeItem,"type").equals("collabAgentToolCall")) delta.add("presentation", collabPresentation(nativeItem));
                if (!text(params,"parentToolCallId").isBlank()) delta.getAsJsonObject("key").addProperty("parentRef",text(params,"parentToolCallId"));
                if (method.equals("item/completed")) {
                    delta.addProperty("status", status(text(nativeItem, "status")));
                    if (text(nativeItem, "status").equals("declined")) delta.addProperty("approvalStatus", "denied");
                }
            }
            case "item/agentMessage/delta", "item/reasoning/textDelta", "item/reasoning/summaryTextDelta", "item/plan/delta" -> {
                String channel = switch (method) {
                    case "item/reasoning/textDelta" -> "reasoningText";
                    case "item/reasoning/summaryTextDelta" -> "reasoningSummary";
                    case "item/plan/delta" -> "plan";
                    default -> "agentMessage";
                };
                delta = object("kind", "item.textDelta", "key", object("providerItemId", text(params, "itemId")), "channel", channel, "text", text(params, "delta"));
            }
            case "item/commandExecution/outputDelta", "item/fileChange/outputDelta" -> delta = object("kind", "item.outputDelta",
                "key", object("providerItemId", text(params, "itemId")), "channel", method.contains("commandExecution") ? "command" : "fileChange", "text", text(params, "delta"));
            case "item/mcpToolCall/progress" -> delta = object("kind", "item.progress", "key", object("providerItemId", text(params, "itemId")), "message", text(params, "message"));
            case "thread/tokenUsage/updated" -> {
                JsonObject usage = obj(params, "tokenUsage");
                delta = object("kind", "usage", "total", usage(obj(usage,"total")), "last", usage(obj(usage,"last")), "modelContextWindow", usage.get("modelContextWindow"));
                JsonObject context = object("kind", "contextWindow", "used", obj(usage,"last").get("totalTokens"), "size", usage.get("modelContextWindow"), "estimated", false, "attach", "currentOrLast");
                if (!turn.isBlank()) delta.addProperty("providerTurnId", turn);
                if (!turn.isBlank()) context.addProperty("providerTurnId", turn);
                out.add(delta); out.add(context); return out;
            }
            case "turn/plan/updated" -> {
                JsonArray steps = array(params,"plan").deepCopy();
                for (JsonElement step : steps) if (text(step.getAsJsonObject(),"status").equals("inProgress")) step.getAsJsonObject().addProperty("status","active");
                JsonObject shape = object("type","planSteps","steps",steps);
                if (!text(params,"explanation").isBlank()) shape.addProperty("explanation",text(params,"explanation"));
                delta = object("kind", "item.close", "key", object("channel", "planSteps"), "status", "completed", "item", shape, "presentation", presentation(shape));
            }
            case "turn/diff/updated" -> delta = object("kind", "turn.diff", "diff", text(params, "diff"));
            case "thread/compacted" -> delta = object("kind", "context.compacted");
            case "thread/name/updated" -> delta = object("kind", "thread.name", "name", text(params, "threadName"));
            case "thread/goal/updated", "thread/goal/cleared" -> delta = object("kind", "extension.state", "extensionKind", "provider-codex/goal", "payload", method.endsWith("cleared") ? null : params.get("goal"));
            case "account/rateLimits/updated" -> delta = object("kind", "provider.rateLimits", "rateLimits", rateLimitUpdate(obj(params,"rateLimits")));
            case "error" -> {
                JsonObject error = obj(params, "error");
                String detail = text(error,"message") + (text(error,"additionalDetails").isBlank() ? "" : "\n" + text(error,"additionalDetails"));
                String failureText = text(error,"additionalDetails").isBlank() ? text(error,"message") : text(error,"additionalDetails");
                String key = text(params,"threadId") + "\0" + turn;
                JsonElement info = error.get("codexErrorInfo");
                if (params.has("willRetry") && !params.get("willRetry").isJsonNull()) {
                    if (params.get("willRetry").getAsBoolean()) {
                        if (info != null && !info.isJsonNull() && !info.equals(new JsonPrimitive("other"))) retryErrors.put(key,object("info",info,"failureText",failureText));
                    } else {
                        JsonObject previous = retryErrors.remove(key);
                        if (previous != null && new JsonPrimitive("other").equals(info) && failureText.equals(text(previous,"failureText"))) info = previous.get("info");
                    }
                }
                delta = object("kind", "provider.error", "message", "Provider error", "detail", detail);
                copy(params,delta,"willRetry");
                if (info != null && !info.isJsonNull()) {
                    delta.add("errorInfo",errorInfo(info));
                    delta.addProperty("category",errorCategory(info));
                }
                if (turn.isBlank()) delta.addProperty("threadScoped",true);
            }
            case "guardianWarning" -> delta = object("kind", "provider.warning", "summary", "Approval review",
                "details", required(params, "message"), "category", "general");
            case "warning", "configWarning", "deprecationNotice" -> delta = object("kind", "provider.warning", "summary",
                text(params, "summary").isBlank() ? text(params, "message") : text(params, "summary"), "details", text(params, "details"), "category", method.equals("configWarning") ? "config" : method.equals("deprecationNotice") ? "deprecation" : "general");
            // Administrative notifications carry no conversation content.
            case "thread/status/changed", "thread/archived", "thread/unarchived", "item/autoApprovalReview/started", "item/autoApprovalReview/completed",
                    "mcpServer/startupStatus/updated", "rawResponse/completed", "rawResponseItem/completed", "remoteControl/status/changed",
                    "serverRequest/resolved", "skills/changed", "thread/settings/updated", "turn/moderationMetadata" -> { return out; }
            default -> delta = unhandled(method, params);
        }
        if (!turn.isBlank() && !delta.has("providerTurnId")) delta.addProperty("providerTurnId", turn);
        out.add(delta);
        return out;
    }

    synchronized void clearThread(String nativeThreadId) {
        retryErrors.keySet().removeIf(key -> key.startsWith(nativeThreadId + "\0"));
    }

    private JsonObject shape(JsonObject item) {
        String type = text(item, "type");
        JsonObject result;
        switch (type) {
            case "agentMessage", "plan" -> {
                if (!item.has("text") || !item.get("text").isJsonPrimitive() || !item.getAsJsonPrimitive("text").isString()) throw new IllegalArgumentException("Missing text");
                result = object("type", type, "text", text(item, "text"));
            }
            case "reasoning" -> result = object("type", type, "summary", array(item, "summary"), "content", array(item, "content"));
            case "commandExecution" -> {
                result = object("type", "command", "command", required(item, "command"), "cwd", required(item, "cwd"));
                copy(item, result, "aggregatedOutput", "exitCode", "durationMs");
            }
            case "fileChange" -> {
                JsonArray changes = new JsonArray();
                for (var entry : array(item, "changes")) {
                    JsonObject change = entry.getAsJsonObject(), kind = obj(change, "kind");
                    JsonObject mapped = object("path", text(change, "path"), "kind", text(kind, "type"));
                    copy(change, mapped, "diff");
                    if (!text(kind,"move_path").isBlank()) mapped.addProperty("movePath", text(kind,"move_path"));
                    changes.add(mapped);
                }
                result = object("type", "fileChange", "changes", changes);
            }
            case "mcpToolCall", "dynamicToolCall", "collabAgentToolCall" -> {
                result = object("type", "tool", "tool", required(item,"tool"));
                copy(item, result, "server", "durationMs", "result");
                if (item.has("arguments")) result.add("args", item.get("arguments").deepCopy());
                if (type.equals("dynamicToolCall")) {
                    StringJoiner parts = new StringJoiner("\n");
                    for (JsonElement content : array(item,"contentItems")) {
                        JsonObject value = content.getAsJsonObject();
                        String rendered = switch(text(value,"type")) {
                            case "inputText" -> text(value,"text");
                            case "inputImage" -> "[image: " + text(value,"imageUrl") + "]";
                            default -> throw new IllegalArgumentException("Unsupported dynamic tool content");
                        };
                        if (!rendered.isBlank()) parts.add(rendered);
                    }
                    if (parts.length() > 0) result.addProperty("result",parts.toString());
                    if (item.has("success") && !item.get("success").isJsonNull() && !item.get("success").getAsBoolean()) result.addProperty("error",parts.length() > 0 ? parts.toString() : "Dynamic tool call failed");
                }
                if (item.has("error") && item.get("error").isJsonObject()) result.addProperty("error", text(obj(item,"error"),"message"));
                if (type.equals("collabAgentToolCall")) {
                    if (!array(item,"receiverThreadIds").isEmpty()) {
                        String label = text(item,"prompt").isBlank() ? switch(text(item,"tool")) { case "spawnAgent" -> "Spawn agent"; case "wait" -> "Wait for agent"; case "resumeAgent" -> "Resume agent"; case "sendInput" -> "Send input to agent"; case "closeAgent" -> "Close agent"; default -> text(item,"tool"); } : text(item,"prompt").strip();
                        result = object("type", "delegation", "childRef", array(item,"receiverThreadIds").get(0), "label", label, "background", false);
                        if (!text(item,"status").equals("inProgress")) {
                            StringJoiner summary = new StringJoiner("\n");
                            for (var entry : obj(item,"agentsStates").entrySet()) summary.add(entry.getKey() + ": " + (entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString()));
                            if (summary.length() > 0) result.addProperty("summary", summary.toString());
                        }
                    } else {
                        JsonObject args = object("senderThreadId",item.get("senderThreadId"),"receiverThreadIds",array(item,"receiverThreadIds"));
                        copy(item,args,"prompt","model","reasoningEffort");
                        result.add("args",args);
                        result.add("result",obj(item,"agentsStates").deepCopy());
                    }
                }
            }
            case "webSearch" -> {
                JsonObject action = obj(item,"action");
                if (text(action,"type").equals("openPage") || text(action,"type").equals("findInPage"))
                    result = object("type", "webFetch", "url", required(action,"url"), "pattern", action.get("pattern"));
                else if (text(action,"type").equals("search")) {
                    Set<String> queries = new LinkedHashSet<>();
                    for (JsonElement query : array(action,"queries")) if (!query.getAsString().isBlank()) queries.add(query.getAsString());
                    if (!text(action,"query").isBlank()) queries.add(text(action,"query"));
                    if (!text(item,"query").isBlank()) queries.add(text(item,"query"));
                    if (queries.isEmpty()) throw new IllegalArgumentException("Search has no query");
                    result = object("type", "webSearch", "queries", queries);
                } else return null;
            }
            case "imageView" -> result = object("type", type, "path", text(item,"path"));
            case "imageGeneration" -> result = object("type", type, "prompt", item.get("revisedPrompt"), "path", item.get("savedPath"), "result", text(item,"result"),
                "error", item.has("failure") && !item.get("failure").isJsonNull() ? "Image generation usage limit exceeded" : null,
                "transparentBackground", item.has("transparentBackground") && !item.get("transparentBackground").isJsonNull() && item.get("transparentBackground").getAsBoolean());
            case "contextCompaction" -> result = object("type", "compaction");
            default -> { return null; }
        }
        return result;
    }

    private JsonObject presentation(JsonObject shape) {
        return switch (text(shape,"type")) {
            case "agentMessage" -> present("Responding","Responded","MessageSquare","");
            case "reasoning" -> present("Thinking","Thought","Brain","");
            case "plan" -> present("Writing plan","Wrote plan","ListTodo","");
            case "compaction" -> present("Compacting context","Compacted context","Archive","");
            case "command" -> {
                String command = text(shape,"command").strip();
                var match = java.util.regex.Pattern.compile("^(?:\\S*/)?(?:sh|bash|zsh)\\s+(?:-lc|-c)\\s+([\\s\\S]+)$").matcher(command);
                if (match.matches()) { command = match.group(1).strip(); if (command.length() > 1 && (command.charAt(0) == '\'' || command.charAt(0) == '"') && command.charAt(0) == command.charAt(command.length()-1)) command = command.substring(1,command.length()-1); }
                yield present("Running command","Ran command","Terminal",command);
            }
            case "fileChange" -> {
                Set<String> names = new LinkedHashSet<>();
                for (JsonElement change : array(shape,"changes")) names.add(fileName(text(change.getAsJsonObject(),"path")));
                yield present(names.size()>1 ? "Editing files" : "Editing file",names.size()>1 ? "Edited files" : "Edited file","EditFile",String.join(", ",names));
            }
            case "tool" -> {
                String tool = text(shape,"tool");
                if (text(shape,"server").equals("node_repl") && tool.equals("js")) yield present("Running JavaScript","Ran JavaScript","Code",text(obj(shape,"args"),"title"));
                if (text(shape,"server").equals("node_repl") && tool.equals("js_reset")) yield present("Resetting JavaScript session","Reset JavaScript session","Code","");
                yield present("Running " + tool,"Ran " + tool,"Toolbox",text(shape,"server"));
            }
            case "webSearch" -> present("Searching the web","Searched the web","Globe",array(shape,"queries").isEmpty() ? "" : array(shape,"queries").get(0).getAsString());
            case "webFetch" -> present("Fetching page","Fetched page","Browser",text(shape,"url"));
            case "imageView" -> present("Viewing image","Viewed image","Eye",fileName(text(shape,"path")));
            case "imageGeneration" -> present("Generating image","Generated image","Palette",text(shape,"path").isBlank() ? text(shape,"prompt") : fileName(text(shape,"path")));
            case "planSteps" -> {
                String headline = text(shape,"explanation");
                for (JsonElement step : array(shape,"steps")) if (text(step.getAsJsonObject(),"status").equals("active")) { headline=text(step.getAsJsonObject(),"step"); break; }
                yield present("Updating plan","Updated plan","ListTodo",headline);
            }
            case "delegation" -> present("Running agent","Agent finished","UserRound",text(shape,"label"));
            default -> throw new IllegalArgumentException("Unknown presentation kind");
        };
    }

    private JsonObject collabPresentation(JsonObject item) {
        String tool = text(item,"tool");
        String[] labels = switch(tool) { case "spawnAgent" -> new String[]{"Spawning agent","Spawned agent"}; case "wait" -> new String[]{"Waiting for agents","Waited for agents"}; case "resumeAgent" -> new String[]{"Resuming agent","Resumed agent"}; case "sendInput" -> new String[]{"Messaging agent","Messaged agent"}; case "closeAgent" -> new String[]{"Closing agent","Closed agent"}; default -> new String[]{"Running " + tool,"Ran " + tool}; };
        return present(labels[0],labels[1],"UserRound",text(item,"prompt"));
    }
    private static JsonObject present(String pending,String completed,String glyph,String title) {
        JsonObject result = object("label",object("pending",pending,"completed",completed),"icon",object("glyph",glyph));
        String line = title.strip().split("\n",2)[0].strip();
        if (!line.isEmpty()) result.addProperty("title",truncate(line,160));
        return result;
    }
    private static String truncate(String text,int limit) { if (text.length() <= limit) return text; int end=limit-1; if (Character.isHighSurrogate(text.charAt(end-1))) end--; return text.substring(0,end)+"…"; }
    private static String fileName(String path) { String[] parts=path.split("/"); return parts.length == 0 ? path : parts[parts.length-1]; }

    synchronized JsonObject interaction(String method, JsonObject params) {
        if (method.equals("item/tool/requestUserInput")) {
            JsonArray questions = new JsonArray();
            for (var q : array(params,"questions")) {
                JsonObject question = q.getAsJsonObject();
                JsonObject mapped = question(required(question,"id"), required(question,"question"), array(question,"options"));
                if (!text(question,"header").isBlank()) mapped.addProperty("shortLabel",text(question,"header"));
                questions.add(mapped);
            }
            if (questions.isEmpty() || questions.size() > 4) return null;
            return object("kind", "user_question", "questions", questions);
        }
        Map<String, JsonObject> choices = decisions(method, params);
        if (choices.isEmpty()) return null;
        JsonObject subject;
        if (method.equals("item/commandExecution/requestApproval")) subject = object("kind", "command", "itemId", required(params,"itemId"),
            "command", required(params,"command"), "cwd", params.get("cwd"), "actions", array(params,"commandActions"), "sessionGrant", sessionGrant(method,params));
        else if (method.equals("item/fileChange/requestApproval")) subject = object("kind", "file_change", "itemId", required(params,"itemId"), "writeScope", params.get("grantRoot"), "sessionGrant", sessionGrant(method,params));
        else if (method.equals("item/permissions/requestApproval")) subject = object("kind", "permission_grant", "itemId", required(params,"itemId"), "toolName", null, "permissions", permissions(obj(params,"permissions")));
        else return null;
        String reason = text(params,"reason");
        if (!obj(obj(params,"additionalPermissions"),"macos").isEmpty()) reason += (reason.isBlank() ? "" : "\n") + "Requested macOS capabilities: " + obj(obj(params,"additionalPermissions"),"macos") + ". Too Many Agents cannot grant macOS permissions; approval covers the command only.";
        return object("kind", "approval", "subject", subject, "reason", reason.isBlank() ? null : reason, "availableDecisions", choices.keySet());
    }
    Map<String, JsonObject> decisions(String method, JsonObject params) {
        Map<String,JsonObject> result = new LinkedHashMap<>();
        switch (method) {
            case "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                JsonArray decisions = method.contains("fileChange") || !params.has("availableDecisions") || params.get("availableDecisions").isJsonNull() ? JSON.toJsonTree(List.of("accept","acceptForSession","decline")).getAsJsonArray() : array(params,"availableDecisions");
                for (var choice : decisions) if (choice.isJsonPrimitive()) {
                    String value = choice.getAsString(), semantic = switch (value) { case "accept" -> "allow_once"; case "acceptForSession" -> "allow_for_session"; case "decline", "cancel" -> "deny"; default -> ""; };
                    if (semantic.equals("allow_for_session") && sessionGrant(method,params) == null) continue;
                    if (!semantic.isBlank()) result.putIfAbsent(semantic, object("decision", value));
                }
            }
            case "item/permissions/requestApproval" -> {
                if (params.has("permissions") && params.get("permissions").isJsonObject()) {
                    JsonObject grant = nativePermissions(permissions(obj(params,"permissions")));
                    result.put("allow_once", object("permissions", grant, "scope", "turn"));
                    result.put("allow_for_session", object("permissions", grant, "scope", "session"));
                }
                result.put("deny", object("permissions", new JsonObject(), "scope", "turn"));
            }
            default -> { }
        }
        return result;
    }

    JsonObject response(String method, JsonObject params, JsonObject answer) {
        if (method.equals("item/tool/requestUserInput")) {
            JsonObject requested = interaction(method,params);
            if (requested == null) throw new IllegalArgumentException("Unsupported questions");
            JsonObject supplied = obj(answer,"answers"), nativeAnswers = new JsonObject();
            Set<String> expected = new HashSet<>();
            for (JsonElement value : requested.getAsJsonArray("questions")) {
                JsonObject question = value.getAsJsonObject();
                String id = text(question,"id"); expected.add(id);
                if (!supplied.has(id) || !supplied.get(id).isJsonObject()) throw new IllegalArgumentException("Missing answer: " + id);
                JsonObject reply = supplied.getAsJsonObject(id);
                JsonArray selected = array(reply,"selected"), nativeValues = new JsonArray();
                if (selected.size() > 1) throw new IllegalArgumentException("This question permits one selection");
                Set<String> options = new HashSet<>();
                for (JsonElement option : array(question,"options")) options.add(text(option.getAsJsonObject(),"value"));
                for (JsonElement choice : selected) { if (!options.contains(choice.getAsString())) throw new IllegalArgumentException("Unknown question option"); nativeValues.add(choice.deepCopy()); }
                String freeText = text(reply,"freeText");
                if (freeText.length() > 4096) throw new IllegalArgumentException("Answer exceeds 4096 characters");
                if (!freeText.isBlank()) nativeValues.add(freeText);
                if (nativeValues.isEmpty()) throw new IllegalArgumentException("An answer is required");
                nativeAnswers.add(id,object("answers",nativeValues));
            }
            if (!expected.equals(supplied.keySet())) throw new IllegalArgumentException("Unknown question ID");
            return object("answers",nativeAnswers);
        }
        JsonObject result = decisions(method,params).get(text(answer,"decision"));
        if (result == null) throw new IllegalArgumentException("That decision was not offered");
        if (method.equals("item/permissions/requestApproval") && !text(answer,"decision").equals("deny")) {
            if (!answer.has("grantedPermissions") || !answer.get("grantedPermissions").isJsonObject()) throw new IllegalArgumentException("Permission approval requires the granted permission profile");
            JsonObject granted = permissions(answer.getAsJsonObject("grantedPermissions"));
            requireSubset(granted,permissions(obj(params,"permissions")));
            result = result.deepCopy(); result.add("permissions",nativePermissions(granted));
        }
        return result.deepCopy();
    }

    private static JsonObject permissions(JsonObject nativeProfile) {
        JsonElement network = nativeProfile.get("network"), files = nativeProfile.get("fileSystem");
        return object("network",network == null || network.isJsonNull() ? null : object("enabled",obj(nativeProfile,"network").get("enabled")),
            "fileSystem",files == null || files.isJsonNull() ? null : object("read",array(obj(nativeProfile,"fileSystem"),"read"),"write",array(obj(nativeProfile,"fileSystem"),"write")));
    }
    private static JsonObject nativePermissions(JsonObject profile) {
        JsonObject result = new JsonObject();
        if (!obj(profile,"network").isEmpty()) result.add("network",profile.get("network").deepCopy());
        if (!obj(profile,"fileSystem").isEmpty()) {
            JsonObject files=obj(profile,"fileSystem");
            result.add("fileSystem",object("read",array(files,"read").isEmpty() ? null : array(files,"read"),"write",array(files,"write").isEmpty() ? null : array(files,"write")));
        }
        return result;
    }
    private static JsonObject sessionGrant(String method,JsonObject params) {
        if (method.equals("item/fileChange/requestApproval")) return text(params,"grantRoot").isBlank() ? null : object("network",null,"fileSystem",object("read",List.of(),"write",List.of(text(params,"grantRoot"))));
        JsonObject grant=permissions(obj(params,"additionalPermissions"));
        return bool(obj(grant,"network"),"enabled") || !array(obj(grant,"fileSystem"),"read").isEmpty() || !array(obj(grant,"fileSystem"),"write").isEmpty() ? grant : null;
    }
    private static void requireSubset(JsonObject granted,JsonObject requested) {
        if (bool(obj(granted,"network"),"enabled") && !bool(obj(requested,"network"),"enabled")) throw new IllegalArgumentException("Network permission was not requested");
        for (String access : List.of("read","write")) for (JsonElement path : array(obj(granted,"fileSystem"),access)) {
            if (!array(obj(requested,"fileSystem"),access).contains(path)) throw new IllegalArgumentException("Permission path was not requested");
        }
    }
    List<JsonObject> asyncQuestions(JsonObject params) {
        JsonObject item = obj(params,"item");
        if (!text(item,"delivery").equals("async")) return List.of();
        List<JsonObject> result = new ArrayList<>();
        int index = 0;
        for (var q : array(item,"questions")) {
            JsonObject question = q.getAsJsonObject(); String id = text(item,"id") + ":" + index++;
            result.add(object("id", id, "delivery", "async", "payload", object("kind", "user_question", "questions",
                List.of(question(id, text(question,"title"), array(question,"options"))))));
        }
        return result;
    }
    private JsonObject question(String id, String prompt, JsonArray nativeOptions) {
        JsonArray options = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (var option : nativeOptions) {
            String label = option.isJsonPrimitive() ? option.getAsString() : text(option.getAsJsonObject(),"label");
            if (label.isBlank() || !seen.add(label)) throw new IllegalArgumentException("Question options must have unique labels");
            JsonObject mapped=object("value", label, "label", label);
            if (option.isJsonObject() && !text(option.getAsJsonObject(),"description").isBlank()) mapped.addProperty("description",text(option.getAsJsonObject(),"description"));
            options.add(mapped);
        }
        if (id.isBlank() || prompt.isBlank() || options.size() > 4) throw new IllegalArgumentException("Unsupported question form");
        return object("id", id, "prompt", prompt, "multiSelect", false, "allowFreeText", true, "options", options);
    }
    private JsonObject unhandled(String method, JsonObject params) { return object("kind", "unhandled", "rawType", method,
        "raw", object("jsonrpc", "2.0", "method", method, "params", params), "vouchedTurn", !text(params,"turnId").isBlank()); }
    private String errorCategory(JsonElement info) {
        String key = info == null || info.isJsonNull() ? "" : info.isJsonPrimitive() ? info.getAsString() : info.getAsJsonObject().keySet().stream().findFirst().orElse("");
        return switch (key) { case "unauthorized" -> "unauthorized"; case "usageLimitExceeded" -> "rate-limit"; case "contextWindowExceeded" -> "context-window-exceeded";
            case "sandboxError" -> "sandbox"; case "serverOverloaded" -> "overloaded"; case "badRequest" -> "bad-request";
            case "sessionBudgetExceeded" -> "budget-exceeded"; case "cyberPolicy", "misalignmentPolicyViolation" -> "policy";
            case "internalServerError" -> "internal"; case "threadRollbackFailed" -> "thread-rollback-failed";
            case "httpConnectionFailed", "responseStreamConnectionFailed" -> "connection-failed";
            case "responseStreamDisconnected" -> "stream-disconnected"; case "responseTooManyFailedAttempts" -> "too-many-failed-attempts";
            case "activeTurnNotSteerable" -> "active-turn-not-steerable"; default -> "unknown"; };
    }
    private JsonObject errorInfo(JsonElement info) {
        String code=info.isJsonPrimitive() ? info.getAsString() : info.getAsJsonObject().keySet().stream().findFirst().orElse("other");
        JsonElement http=info.isJsonObject() && info.getAsJsonObject().has(code) && info.getAsJsonObject().get(code).isJsonObject() ? obj(info.getAsJsonObject(),code).get("httpStatusCode") : null;
        return object("category",errorCategory(info),"providerCode",code,"httpStatusCode",http);
    }
    private String status(String nativeStatus) { return switch (nativeStatus) { case "failed" -> "failed"; case "declined", "interrupted" -> "interrupted"; case "inProgress" -> "pending"; case "", "completed" -> "completed"; default -> throw new IllegalArgumentException("Unknown native status: " + nativeStatus); }; }
    private static JsonObject usage(JsonObject input) {
        JsonObject output = input.deepCopy();
        for (String key : List.of("totalTokens","inputTokens","cachedInputTokens","outputTokens","reasoningOutputTokens")) if (!input.has(key) || !input.get(key).isJsonPrimitive() || !input.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("Incomplete token usage");
        output.add("cacheReadInputTokens",input.get("cachedInputTokens"));
        return output;
    }

    private JsonObject rateLimitUpdate(JsonObject update) {
        String id=text(update,"limitId").isBlank() ? "codex" : text(update,"limitId");
        JsonObject previous=rateLimits.getOrDefault(id,new JsonObject());
        JsonObject merged=previous.deepCopy();
        for (String field : List.of("limitName","primary","secondary","credits","individualLimit","planType")) {
            if (update.has(field) && !update.get(field).isJsonNull()) merged.add(field,update.get(field).deepCopy());
        }
        merged.add("spendControlReached",update.has("spendControlReached") ? update.get("spendControlReached") : JsonNull.INSTANCE);
        String reached=text(update,"rateLimitReachedType");
        if (reached.isBlank()) {
            String old=text(previous,"rateLimitReachedType");
            boolean active=old.equals("rate_limit_reached") && (number(obj(merged,"primary"),"usedPercent",0)>=100 || number(obj(merged,"secondary"),"usedPercent",0)>=100)
                || old.contains("credits_depleted") && !obj(merged,"credits").isEmpty() && !bool(obj(merged,"credits"),"unlimited") && !bool(obj(merged,"credits"),"hasCredits")
                || old.contains("usage_limit_reached") && !obj(merged,"individualLimit").isEmpty() && number(obj(merged,"individualLimit"),"remainingPercent",100)<=0;
            if (active) reached=old;
        }
        merged.add("rateLimitReachedType",reached.isBlank() ? JsonNull.INSTANCE : new JsonPrimitive(reached));
        rateLimits.put(id,merged);
        List<JsonObject> candidates=new ArrayList<>();
        Set<String> keys=new LinkedHashSet<>(List.of("codex",id));
        JsonObject selected=null;
        int selectedScore=-1;
        for (String key:keys) {
            JsonObject nativeValue=rateLimits.get(key);
            if (nativeValue==null) continue;
            JsonObject normalized=normalizeRateLimits(nativeValue);
            candidates.add(normalized);
            int rank=switch(text(normalized,"status")) { case "blocked" -> 3; case "warning" -> 2; case "allowed" -> 1; default -> 0; };
            int score=rank*8;
            if (rank==3 && !text(normalized,"kind").equals("subscription-window")) score+=4;
            if (!text(nativeValue,"rateLimitReachedType").isBlank() || bool(nativeValue,"spendControlReached")) score+=2;
            if (key.equals(id)) score++;
            if (score>selectedScore) { selected=normalized; selectedScore=score; }
        }
        if (selected==null) throw new IllegalArgumentException("Missing rate limit snapshot");
        JsonArray windows=array(selected,"windows").deepCopy();
        for (JsonObject candidate:candidates) if (candidate!=selected && text(candidate,"status").equals("blocked")) {
            for (JsonElement window:array(candidate,"windows")) if (text(window.getAsJsonObject(),"status").equals("blocked")) windows.add(window.deepCopy());
        }
        selected.add("windows",windows);
        return selected;
    }

    private static JsonObject normalizeRateLimits(JsonObject value) {
        JsonArray windows=new JsonArray();
        for (String key:List.of("primary","secondary")) {
            JsonObject window=obj(value,key);
            if (window.isEmpty()) continue;
            if (!window.has("usedPercent")) throw new IllegalArgumentException("Missing rate-limit percentage");
            double duration=number(window,"windowDurationMins",0);
            String label=duration==10080 ? "Weekly limit" : duration==300 ? "Current session" : key.equals("primary") ? "Current session" : "Weekly limit";
            windows.add(object("providerKey",key,"label",label,"status",limitStatus(number(window,"usedPercent",0)),"resetsAtMs",millis(window,"resetsAt")));
        }
        JsonObject individual=obj(value,"individualLimit");
        if (!individual.isEmpty()) windows.add(object("providerKey","individual-limit","label","Spend control","status",limitStatus(100-number(individual,"remainingPercent",100)),"resetsAtMs",millis(individual,"resetsAt")));
        String reason=text(value,"rateLimitReachedType");
        boolean spend=!individual.isEmpty() && number(individual,"remainingPercent",100)<=0;
        String kind=reason.equals("rate_limit_reached") ? "subscription-window" : reason.contains("credits_depleted") ? "credits" : reason.contains("usage_limit_reached") ? "spend-control" : !reason.isBlank() ? "unknown" : spend ? "spend-control" : !obj(value,"primary").isEmpty() || !obj(value,"secondary").isEmpty() ? "subscription-window" : !individual.isEmpty() ? "spend-control" : "unknown";
        String status=windows.isEmpty() && !bool(obj(value,"credits"),"hasCredits") ? "unknown" : "allowed";
        for (JsonElement window:windows) { String next=text(window.getAsJsonObject(),"status"); if (next.equals("blocked") || next.equals("warning") && !status.equals("blocked")) status=next; }
        if (!reason.isBlank()) status="blocked";
        if (bool(value,"spendControlReached")) { status="blocked"; kind="spend-control"; }
        return object("providerId","codex","status",status,"kind",kind,"windows",windows,"reachedReason",reason.isBlank() ? null : reason,"overageStatus",null,"overageReason",null);
    }
    private static String limitStatus(double percent) { return percent>=100 ? "blocked" : percent>=90 ? "warning" : "allowed"; }
    private static Double millis(JsonObject o,String k) { return !o.has(k) || o.get(k).isJsonNull() ? null : o.get(k).getAsDouble()*1000; }
    private static double number(JsonObject o,String k,double absent) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : absent; }
    private static boolean bool(JsonObject o,String k) { return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean(); }
    private static String required(JsonObject o,String k) { String value=text(o,k); if (value.isBlank()) throw new IllegalArgumentException("Missing " + k); return value; }
    private static void copy(JsonObject from, JsonObject to, String... keys) { for (String key : keys) if (from.has(key) && !from.get(key).isJsonNull()) to.add(key, from.get(key).deepCopy()); }
    private static String text(JsonObject o,String k) { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : ""; }
    private static JsonObject obj(JsonObject o,String k) { return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject(); }
    private static JsonArray array(JsonObject o,String k) { return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray(); }
    private static JsonObject object(Object... values) { JsonObject o = new JsonObject(); for (int i=0;i<values.length;i+=2) o.add((String)values[i],JSON.toJsonTree(values[i+1])); return o; }
}
