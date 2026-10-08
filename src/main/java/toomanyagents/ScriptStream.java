package toomanyagents;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Bounded immutable state delivery. Producing a frame never waits on HTTP. */
final class ScriptStream {
    private static final Gson JSON = new Gson();
    private static final int FRAME_BYTES = 8 * 1024 * 1024, RETAINED_BYTES = 16 * 1024 * 1024;
    private static final byte[] END = encode(JsonState.object("type", "end"), 4096);
    private static final byte[] OVERFLOW = encode(JsonState.object("type", "error", "message", "script_state_consumer_too_slow"), 4096);
    private final Object lock = new Object();
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private int retainedBytes;
    private byte[] terminal;
    private boolean writerStarted;
    final CompletableFuture<JsonObject> completion = new CompletableFuture<>();

    boolean open() { synchronized (lock) { return terminal == null; } }

    void offer(JsonObject snapshot) {
        if (!open()) return;
        byte[] frame;
        try {
            var envelope = new JsonObject();
            envelope.addProperty("type", "state");
            envelope.add("snapshot", snapshot);
            frame = encode(envelope, FRAME_BYTES);
        } catch (RuntimeException | StackOverflowError failure) {
            fail(failure);
            return;
        }
        synchronized (lock) {
            if (terminal != null) return;
            if (frames.size() < 64 && frame.length <= RETAINED_BYTES - retainedBytes) {
                retainedBytes += frame.length;
                frames.addLast(frame);
                lock.notifyAll();
                return;
            }
            terminal = OVERFLOW;
            lock.notifyAll();
        }
        completion.completeExceptionally(new IllegalStateException("script_state_consumer_too_slow"));
    }

    void finish() { close(END, null); }

    void fail(Throwable failure) {
        Objects.requireNonNull(failure);
        if (!open()) return;
        var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        String message = cause.getMessage();
        if (message != null && message.length() > 512) message = message.substring(0, 512) + "…";
        close(encode(JsonState.object("type", "error", "message", message), 4096), failure);
    }

    private void close(byte[] ending, Throwable failure) {
        synchronized (lock) {
            if (terminal != null) return;
            terminal = ending;
            lock.notifyAll();
        }
        // Completion callbacks may run inline and reenter stream/controller cleanup.
        if (failure == null) completion.complete(new JsonObject());
        else completion.completeExceptionally(failure);
    }

    void write(OutputStream output) throws IOException {
        synchronized (lock) {
            if (writerStarted) throw new IOException("Script stream already has a reader");
            writerStarted = true;
        }
        try {
            while (true) {
                byte[] frame, ending;
                synchronized (lock) {
                    if (frames.isEmpty() && terminal == null) lock.wait(1000);
                    frame = frames.pollFirst();
                    ending = terminal;
                }
                if (frame != null) {
                    int bytes = frame.length;
                    try {
                        output.write(frame);
                        output.flush();
                    } finally {
                        frame = null;
                        synchronized (lock) { retainedBytes -= bytes; }
                    }
                } else if (ending != null) {
                    output.write(ending);
                    output.flush();
                    return;
                } else {
                    // Keep the HTTP connection alive while the game is paused.
                    output.write('\n');
                    output.flush();
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Script stream interrupted", failure);
        } finally {
            fail(new IllegalStateException("script_state_connection_closed"));
            synchronized (lock) {
                // The in-flight reservation has already been released, even after a failed write.
                frames.clear();
                retainedBytes = 0;
            }
        }
    }

    private static byte[] encode(JsonObject value, int limit) {
        var bytes = new CappedBytes(limit);
        try (var writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8) {
            @Override public void write(String text, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, text.length());
                // StreamEncoder otherwise copies the entire requested string into a char array.
                while (length > 0) {
                    int count = Math.min(length, 4096);
                    super.write(text, offset, count);
                    offset += count;
                    length -= count;
                }
            }
        }) {
            JSON.toJson(value, writer);
            writer.flush();
            bytes.write('\n');
        } catch (IOException failure) {
            throw new IllegalStateException("script_state_encoding_failed", failure);
        }
        return Arrays.copyOf(bytes.data, bytes.size);
    }

    /** Bounds encoder storage as well as accepted length; no full JSON string is built. */
    private static final class CappedBytes extends OutputStream {
        private final int limit;
        private byte[] data;
        private int size;

        CappedBytes(int limit) { this.limit = limit; data = new byte[Math.min(8192, limit)]; }

        private void reserve(int count) {
            if (count > limit - size) throw new IllegalStateException("script_state_frame_too_large");
            int needed = size + count;
            if (needed > data.length) data = Arrays.copyOf(data, Math.min(limit, Math.max(needed, data.length * 2)));
        }

        @Override public void write(int value) {
            reserve(1);
            data[size++] = (byte) value;
        }

        @Override public void write(byte[] source, int offset, int count) {
            Objects.checkFromIndexSize(offset, count, source.length);
            reserve(count);
            System.arraycopy(source, offset, data, size, count);
            size += count;
        }
    }
}
