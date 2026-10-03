package toomanyagents.agent.history;

import toomanyagents.agent.model.SharedModel;
import com.google.gson.JsonObject;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local stored-event envelopes. All filesystem work runs on the owned writer. */
public final class ConversationHistory implements AutoCloseable {
    private final String conversationId;
    private final Path directory;
    private final Path file;
    private final ExecutorService writer;
    private final ConversationProjection projection = new ConversationProjection();
    private final Set<String> eventIds = new HashSet<>();
    private volatile JsonObject published = new JsonObject();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> loading;
    private CompletableFuture<Void> closing;
    private FileChannel channel;
    private FileLock lock;
    private long sequence;
    private long createdAt;
    private long durableSequence;
    private boolean loaded;
    private boolean closed;
    private Throwable failure;

    public ConversationHistory(Path directory, String conversationId) {
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("Invalid conversation ID");
        }
        this.directory = directory;
        this.conversationId = conversationId;
        file = directory.resolve(conversationId + ".events.jsonl");
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "too_many_agents-history-" + conversationId);
            thread.setDaemon(true);
            return thread;
        });
        publish();
    }

    public synchronized CompletableFuture<Void> load() {
        if (loading != null) return loading;
        loading = enqueue(() -> {
            Files.createDirectories(directory);
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) throw new IOException("Conversation history is already open: " + file);
            byte[] bytes = Files.readAllBytes(file);
            int lineStart = 0;
            for (int index = 0; index < bytes.length; index++) {
                if (bytes[index] != '\n') continue;
                readEnvelope(bytes, lineStart, index);
                lineStart = index + 1;
            }
            if (lineStart < bytes.length) {
                // A newline is the commit delimiter. Save the uncommitted suffix before truncating.
                Path recovery = directory.resolve(conversationId + ".torn-" + UUID.randomUUID() + ".bin");
                try (FileChannel backup = FileChannel.open(recovery, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    writeAll(backup, ByteBuffer.wrap(bytes, lineStart, bytes.length - lineStart));
                    backup.force(true);
                }
                channel.truncate(lineStart);
                channel.force(true);
                projection.diagnostic("Recovered an incomplete trailing write; preserved "
                        + (bytes.length - lineStart) + " bytes in " + recovery.getFileName());
            }
            channel.position(channel.size());
            synchronized (this) {
                projection.finishReplay();
                durableSequence = sequence;
                loaded = true;
                publish();
            }
        });
        return loading;
    }

    /** Publishes immediately; the returned future is the event's disk durability barrier. */
    public synchronized CompletableFuture<Void> append(JsonObject event) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("History is closed"));
        if (failure != null) return CompletableFuture.failedFuture(new IOException("History persistence failed", failure));
        if (!loaded || loading == null || !loading.isDone() || loading.isCompletedExceptionally()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Await successful history load before appending"));
        }
        JsonObject copy = event.deepCopy();
        SharedModel.validateEvent(copy);
        if (!conversationId.equals(copy.get("threadId").getAsString())) {
            throw new IllegalArgumentException("Event belongs to another conversation");
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("id", UUID.randomUUID().toString());
        envelope.addProperty("threadId", conversationId);
        long reserved = sequence + 1;
        envelope.addProperty("seq", reserved);
        envelope.addProperty("createdAt", System.currentTimeMillis());
        envelope.add("scope", copy.get("scope").deepCopy());
        envelope.addProperty("type", copy.get("type").getAsString());
        JsonObject data = copy.deepCopy();
        data.remove("threadId");
        data.remove("scope");
        data.remove("type");
        envelope.add("data", data);
        try {
            projection.accept(envelope);
            if (sequence == 0) createdAt = envelope.get("createdAt").getAsLong();
            sequence = reserved;
            eventIds.add(envelope.get("id").getAsString());
            publish();
        } catch (RuntimeException error) {
            recordFailure(error);
            return CompletableFuture.failedFuture(error);
        }
        byte[] bytes = (envelope + "\n").getBytes(StandardCharsets.UTF_8);
        return enqueue(() -> {
            writeAll(channel, ByteBuffer.wrap(bytes));
            channel.force(true);
            synchronized (this) { durableSequence = reserved; publish(); }
        });
    }

    public JsonObject stateSnapshot() { return published.deepCopy(); }

    /** Full export is explicit; live UI and event handling never build it. */
    public synchronized JsonObject snapshot() {
        JsonObject value = projection.snapshot();
        published.entrySet().forEach(entry -> { if (!entry.getKey().equals("state")) value.add(entry.getKey(),entry.getValue().deepCopy()); });
        return value;
    }

    public synchronized JsonObject transcript(JsonObject query) {
        JsonObject value=projection.transcript(query);
        value.addProperty("sequence",sequence);
        return value;
    }

    public synchronized String lastReply(String turn) { return projection.lastReply(turn); }

    public synchronized CompletableFuture<Void> flush() {
        load();
        return enqueue(() -> { if (channel != null) channel.force(true); });
    }

    /** Nonblocking; callers needing a durability barrier should await flush() off the game thread. */
    @Override public synchronized void close() {
        closeAsync();
    }

    /** Closes even a failed writer, preserving its failure on the returned future. */
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing;
        closed = true;
        closing = tail.handleAsync((ignored, error) -> {
            Throwable problem=error;
            try {
                if (channel != null) {
                    try { if (lock != null && lock.isValid()) channel.force(true); }
                    finally { channel.close(); }
                }
            } catch (IOException exception) { recordFailure(exception); if (problem == null) problem=exception; }
            synchronized (this) { publish(); }
            if (problem != null) throw new java.util.concurrent.CompletionException(problem);
            return null;
        }, writer);
        closing.whenComplete((ignored, error) -> writer.shutdown());
        return closing;
    }

    private void readEnvelope(byte[] bytes, int start, int end) throws IOException {
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, start, end - start)).toString();
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setLenient(false);
            JsonObject envelope = Streams.parse(reader).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON content");
            for (String field : new String[]{"id", "threadId", "type"}) {
                if (!envelope.has(field) || !envelope.get(field).isJsonPrimitive() || !envelope.getAsJsonPrimitive(field).isString()) throw new IllegalArgumentException("Invalid envelope " + field);
            }
            String id = envelope.get("id").getAsString();
            long next = integer(envelope, "seq");
            if (id.isBlank() || eventIds.contains(id) || next != sequence + 1
                    || !conversationId.equals(envelope.get("threadId").getAsString())
                    || integer(envelope, "createdAt") < 0) {
                throw new IllegalArgumentException("Invalid envelope ID, sequence, timestamp or conversation");
            }
            JsonObject event = envelope.getAsJsonObject("data").deepCopy();
            if (event.has("threadId") || event.has("scope") || event.has("type")) throw new IllegalArgumentException("Envelope fields duplicated in stored data");
            event.add("threadId", envelope.get("threadId"));
            event.add("scope", envelope.get("scope"));
            event.add("type", envelope.get("type"));
            SharedModel.validateEvent(event);
            projection.accept(envelope);
            if (sequence == 0) createdAt = envelope.get("createdAt").getAsLong();
            sequence = next;
            durableSequence = next;
            eventIds.add(id);
        } catch (Exception error) {
            throw new IOException("Corrupt history at byte " + start + "; original file retained: " + file, error);
        }
    }

    private synchronized CompletableFuture<Void> enqueue(IoAction action) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("History is closed"));
        tail = tail.thenRunAsync(() -> {
            try { action.run(); }
            catch (Exception error) {
                recordFailure(error);
                throw new java.util.concurrent.CompletionException(error);
            }
        }, writer);
        return tail;
    }

    private synchronized void recordFailure(Throwable error) {
        failure = error;
        projection.diagnostic(error.getMessage() == null ? error.toString() : error.getMessage());
        projection.finishReplay();
        publish();
    }

    private void publish() {
        JsonObject value = new JsonObject();
        value.add("state",projection.stateSnapshot());
        value.addProperty("conversationId", conversationId);
        value.addProperty("createdAt", createdAt);
        value.addProperty("sequence", sequence);
        value.addProperty("durableSequence", durableSequence);
        value.addProperty("storageStatus", failure != null ? "failed" : closed ? "closed" : !loaded ? "loading" : durableSequence < sequence ? "pending" : "ready");
        if (failure != null) value.addProperty("storageError", failure.getMessage() == null ? failure.toString() : failure.getMessage());
        published = value;
    }

    private static void writeAll(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static long integer(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isNumber()) throw new IllegalArgumentException("Invalid envelope " + field);
        return object.get(field).getAsBigDecimal().longValueExact();
    }

    @FunctionalInterface private interface IoAction { void run() throws Exception; }
}
