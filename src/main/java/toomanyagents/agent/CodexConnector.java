package toomanyagents.agent;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Each conversation owns its app-server process. Catalog discovery is separate. */
public final class CodexConnector implements AgentHarness {
    private final Path executable;
    private final ToolHandler tools;
    private final Consumer<Event> listener;
    private final Map<String,CodexAdapter> sessions = new HashMap<>();
    private final Map<String,Boolean> nativeSubagents = new HashMap<>();
    private static final class Reconfiguration {
        final CodexAdapter previous;
        CodexAdapter replacement;
        boolean cancelled, dispatched;
        Reconfiguration(CodexAdapter previous) { this.previous = previous; }
    }
    private final Map<String,Reconfiguration> reconfiguring = new HashMap<>();
    private final Map<String,Session> identities = new HashMap<>();
    private final Map<String,CodexAdapter> requests = new HashMap<>();
    private CodexAdapter catalog;
    private CompletableFuture<List<Model>> models;
    private long catalogTime;
    private boolean closed;
    private String availability = "unknown", availabilityMessage = "";

    public CodexConnector(Path executable, ToolHandler tools, Consumer<Event> listener) {
        this.executable = executable; this.tools = tools; this.listener = listener;
        catalog = new CodexAdapter(executable,tools,event -> {});
    }
    @Override public String providerId() { return "codex"; }
    @Override public synchronized JsonObject description() {
        JsonObject result = catalog.description();
        JsonObject status = new JsonObject(); status.addProperty("state",availability); status.addProperty("message",availabilityMessage);
        result.add("availability",status);
        return result;
    }
    @Override public synchronized JsonObject normalizePermissions(JsonObject settings) { return catalog.normalizePermissions(settings); }
    @Override public synchronized CompletableFuture<JsonObject> usage() {
        connect();
        return catalog.usage();
    }
    @Override public synchronized String permissionMode(Options options) { return catalog.permissionMode(options); }
    @Override public synchronized CompletableFuture<List<Model>> connect() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Codex connector closed"));
        if (models != null && (!models.isDone() || !models.isCompletedExceptionally() && System.nanoTime()-catalogTime < 60_000_000_000L)) return models;
        catalog.close();
        catalog = new CodexAdapter(executable,tools,event -> {});
        catalogTime = System.nanoTime();
        availability = "connecting";
        models = catalog.connect();
        models.whenComplete((result,error) -> {
            synchronized(this) {
                if(error == null) { availability = "ready"; availabilityMessage = ""; }
                else {
                    Throwable cause = error;
                    while(cause instanceof java.util.concurrent.CompletionException && cause.getCause()!=null) cause=cause.getCause();
                    availability = cause instanceof RequestFailure failure && failure.recovery.equals("authRequired") ? "authRequired" : "unavailable";
                    availabilityMessage = cause.getMessage();
                }
            }
        });
        return models;
    }
    private synchronized CodexAdapter create(String id) {
        if (closed) throw new IllegalStateException("Codex connector closed");
        if (sessions.containsKey(id)) throw new IllegalStateException("Conversation is already connected");
        CodexAdapter[] owner = new CodexAdapter[1];
        owner[0] = new CodexAdapter(executable, (thread,turn,tool,args) -> {
            synchronized(this) {
                if (sessions.get(id) != owner[0]) return CompletableFuture.failedFuture(new IllegalStateException("Session replaced"));
            }
            return tools.call(thread,turn,tool,args);
        }, event -> deliver(id,owner[0],event));
        sessions.put(id,owner[0]);
        return owner[0];
    }
    private void deliver(String id,CodexAdapter owner,Event event) {
        synchronized(this) {
            if (sessions.get(id) != owner) return;
            if (event.kind().equals("interaction/request")) requests.put(id + ":" + event.data().get("id").getAsString(),owner);
            if (event.kind().equals("interaction/resolved")) requests.remove(id + ":" + event.data().get("id").getAsString());
            if (event.kind().equals("connection/error")) {
                sessions.remove(id,owner);
                requests.values().removeIf(value -> value == owner);
            }
        }
        if (event.kind().equals("connection/error")) {
            listener.accept(new Event(id,"","session/disconnected",event.text(),event.data()));
        } else if (event.kind().startsWith("interaction/")) {
            JsonObject data = event.data().deepCopy();
            data.addProperty("id",id + ":" + data.get("id").getAsString());
            listener.accept(new Event(id,event.turnId(),event.kind(),event.text(),data));
        } else listener.accept(event);
    }
    private synchronized CodexAdapter require(Session session) {
        CodexAdapter adapter = sessions.get(session.threadId());
        if (adapter == null || !session.equals(identities.get(session.threadId()))) throw new IllegalStateException("Conversation connection has changed");
        return adapter;
    }
    @Override public CompletableFuture<Session> start(String id,Options options) {
        CodexAdapter adapter = create(id);
        return adapter.start(id,options).whenComplete((session,error) -> { if(error != null) discard(id,adapter); else synchronized(this) { identities.put(id,session); nativeSubagents.put(id,options.nativeSubagentsEnabled()); } });
    }
    @Override public CompletableFuture<Session> resume(String id,String nativeId,Options options) {
        CodexAdapter adapter = create(id);
        return adapter.resume(id,nativeId,options).whenComplete((session,error) -> { if(error != null) discard(id,adapter); else synchronized(this) { identities.put(id,session); nativeSubagents.put(id,options.nativeSubagentsEnabled()); } });
    }
    @Override public CompletableFuture<String> send(Session session,String requestId,Input input,Options options) {
        Reconfiguration change;
        synchronized(this) {
            if (reconfiguring.containsKey(session.threadId())) return CompletableFuture.failedFuture(new RequestFailure(
                "Codex is applying the native sub-agent setting; wait before sending another turn.","staleTurn",true,new JsonObject()));
            CodexAdapter previous = require(session);
            if (nativeSubagents.getOrDefault(session.threadId(),false) == options.nativeSubagentsEnabled())
                return previous.send(session,requestId,input,options);
            if (!previous.canReconfigure()) return CompletableFuture.failedFuture(new RequestFailure(
                "The native sub-agent setting cannot apply while Codex has an active turn, pending approval, or native children. Close native children first; they have not been stopped.",
                "staleTurn",true,new JsonObject()));
            change = new Reconfiguration(previous);
            reconfiguring.put(session.threadId(),change);
            sessions.remove(session.threadId(),previous);
        }
        // Reattach the same native conversation before submitting any new work.
        return change.previous.terminate().thenCompose(unused -> {
            synchronized(this) {
                checkReconfiguration(session,change);
                change.replacement = create(session.threadId());
                return change.replacement.resume(session.threadId(),session.providerSessionId(),options);
            }
        }).thenCompose(resumed -> {
            synchronized(this) {
                checkReconfiguration(session,change);
                if (!resumed.providerSessionId().equals(session.providerSessionId())) throw new RequestFailure(
                    "Codex resumed a different native conversation; no input was sent.","restartRecommended",true,new JsonObject());
                nativeSubagents.put(session.threadId(),options.nativeSubagentsEnabled());
                change.dispatched = true;
                return change.replacement.send(session,requestId,input,options);
            }
        }).exceptionallyCompose(error -> {
            Throwable cause = error;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
            boolean beforeInput;
            synchronized(this) { beforeInput = !change.dispatched; }
            if (!beforeInput && !(cause instanceof RequestFailure failure && failure.rejected)) return CompletableFuture.failedFuture(error);
            Throwable rejected = beforeInput ? new RequestFailure("Codex could not apply the setting; no turn input was sent. " + cause.getMessage(),
                "restartRecommended",true,new JsonObject()) : cause;
            return cancelReconfiguration(session,change,"Codex session setup ended; reconnect to resume context.")
                .handle((unused,cleanupError) -> null).thenCompose(unused -> CompletableFuture.failedFuture(rejected));
        }).whenComplete((turn,error) -> { synchronized(this) { reconfiguring.remove(session.threadId(),change); } });
    }
    private void checkReconfiguration(Session session,Reconfiguration change) {
        if (closed || change.cancelled || reconfiguring.get(session.threadId()) != change || !session.equals(identities.get(session.threadId())))
            throw new RequestFailure("Codex session reconfiguration was cancelled before input was sent.","staleTurn",true,new JsonObject());
    }
    private CompletableFuture<Void> cancelReconfiguration(Session session,Reconfiguration change,String reason) {
        synchronized(this) {
            if (!reconfiguring.remove(session.threadId(),change)) return CompletableFuture.completedFuture(null);
            change.cancelled = true;
            if (change.replacement != null) sessions.remove(session.threadId(),change.replacement);
            identities.remove(session.threadId(),session);
            nativeSubagents.remove(session.threadId());
            requests.values().removeIf(value -> value == change.previous || value == change.replacement);
        }
        CompletableFuture<Void> stopped = CompletableFuture.allOf(change.previous.terminate(),
            change.replacement == null ? CompletableFuture.completedFuture(null) : change.replacement.terminate());
        return CompletableFuture.allOf(stopped,CompletableFuture.runAsync(() ->
            listener.accept(new Event(session.threadId(),"","session/disconnected",reason,new JsonObject()))));
    }
    @Override public CompletableFuture<Void> steer(Session session,String requestId,String turn,Input input,Options options) { return require(session).steer(session,requestId,turn,input,options); }
    @Override public CompletableFuture<Void> interrupt(Session session) {
        CodexAdapter adapter;
        synchronized(this) {
            Reconfiguration change = reconfiguring.get(session.threadId());
            if (change != null && session.equals(identities.get(session.threadId())))
                return cancelReconfiguration(session,change,"Codex was stopped while applying settings; reconnect before sending more work.");
            adapter = require(session);
        }
        return adapter.interrupt(session).orTimeout(12,java.util.concurrent.TimeUnit.SECONDS).exceptionallyCompose(error ->
            adapter.abort("Codex did not settle after Stop. Its owned process was terminated; no action was retried."));
    }
    @Override public CompletableFuture<Void> release(Session session) {
        CodexAdapter adapter;
        synchronized(this) {
            Reconfiguration change = reconfiguring.get(session.threadId());
            if (change != null && session.equals(identities.get(session.threadId())))
                return cancelReconfiguration(session,change,"Codex was released while applying settings.");
            adapter = sessions.get(session.threadId()); if (adapter != null) adapter = require(session);
        }
        if (adapter == null) return CompletableFuture.completedFuture(null);
        CodexAdapter owner = adapter;
        return owner.release(session).thenCompose(unused -> discard(session.threadId(),owner));
    }
    @Override public CompletableFuture<Void> archive(Session session) {
        CodexAdapter adapter;
        synchronized(this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Codex connector closed"));
            adapter = sessions.get(session.threadId());
            if (adapter != null && identities.containsKey(session.threadId())) {
                CodexAdapter owner = require(session);
                return owner.archive(session).thenCompose(unused -> discard(session.threadId(),owner));
            }
        }
        // Archiving a released conversation needs its saved provider ID, not a live session.
        var cleanup = adapter == null ? CompletableFuture.<Void>completedFuture(null) : discard(session.threadId(),adapter);
        return cleanup.thenCompose(unused -> {
            var archive = new CodexAdapter(executable,tools,event -> {});
            return archive.archive(session).whenComplete((done,failure) -> archive.close());
        });
    }
    @Override public CompletableFuture<Void> respond(String id,JsonObject answer) {
        CodexAdapter adapter;
        synchronized(this) { adapter = requests.get(id); }
        if(adapter == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Request is no longer pending"));
        return adapter.respond(id.substring(id.indexOf(':')+1),answer);
    }
    private synchronized CompletableFuture<Void> discard(String id,CodexAdapter adapter) {
        if (sessions.remove(id,adapter)) { identities.remove(id); nativeSubagents.remove(id); }
        requests.values().removeIf(value -> value == adapter);
        return adapter.terminate();
    }
    @Override public synchronized void close() {
        if(closed) return; closed = true;
        catalog.close(); sessions.values().forEach(CodexAdapter::close); sessions.clear(); identities.clear(); nativeSubagents.clear(); reconfiguring.clear(); requests.clear();
    }
}
