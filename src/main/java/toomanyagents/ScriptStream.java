package toomanyagents;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Bounded serialized state delivery. Producing a frame never waits on HTTP. */
final class ScriptStream {
    private static final Gson JSON = new Gson();
    private final ArrayBlockingQueue<JsonObject> frames = new ArrayBlockingQueue<>(64);
    final CompletableFuture<JsonObject> completion = new CompletableFuture<>();

    boolean open() { return !completion.isDone(); }

    void offer(JsonObject snapshot) {
        if (!open()) return;
        if (!frames.offer(JsonState.object("type", "state", "snapshot", snapshot)))
            fail(new IllegalStateException("script_state_consumer_too_slow"));
    }

    void finish() { completion.complete(new JsonObject()); }

    void fail(Throwable failure) { completion.completeExceptionally(failure); }

    void write(OutputStream output) throws IOException {
        try {
            while (open() || !frames.isEmpty()) {
                var frame = frames.poll(1, TimeUnit.SECONDS);
                // Keep the HTTP connection alive while the game is paused.
                String line = frame == null ? "\n" : JSON.toJson(frame) + "\n";
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            JsonObject terminal;
            try { completion.join(); terminal = JsonState.object("type", "end"); }
            catch (RuntimeException failure) {
                var cause = failure.getCause() == null ? failure : failure.getCause();
                terminal = JsonState.object("type", "error", "message", cause.getMessage());
            }
            output.write((JSON.toJson(terminal) + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Script stream interrupted", failure);
        } finally {
            if (open()) fail(new IllegalStateException("script_state_connection_closed"));
        }
    }
}
