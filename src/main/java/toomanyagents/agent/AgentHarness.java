package toomanyagents.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import toomanyagents.agent.model.SharedModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A provider connection. Native protocols and permission encodings stay behind this boundary. */
public interface AgentHarness extends AutoCloseable {
    record ToolDefinition(String name, String description, JsonObject inputSchema) {
        public ToolDefinition { inputSchema = inputSchema.deepCopy(); }
        @Override public JsonObject inputSchema() { return inputSchema.deepCopy(); }
    }
    record ServiceTier(String id, String name, String description) {}
    record Model(String id, String label, List<String> efforts, String defaultEffort, List<ServiceTier> serviceTiers, JsonObject descriptor, Boolean supportsAutoMode) {
        public Model { efforts = List.copyOf(efforts); serviceTiers = List.copyOf(serviceTiers); descriptor = descriptor.deepCopy(); }
        @Override public JsonObject descriptor() { return descriptor.deepCopy(); }
    }
    record Options(Path directory, List<Path> additionalDirectories, String instructions, String model, String effort,
                   JsonObject permissions, List<ToolDefinition> tools, String serviceTier, boolean nativeSubagentsEnabled) {
        public Options {
            directory = directory.toAbsolutePath().normalize();
            var folders = new LinkedHashSet<Path>();
            for (Path path : additionalDirectories) folders.add(path.toAbsolutePath().normalize());
            folders.remove(directory);
            additionalDirectories = List.copyOf(folders);
            permissions = permissions.deepCopy(); tools = List.copyOf(tools);
        }
        @Override public JsonObject permissions() { return permissions.deepCopy(); }
        public List<Path> directories() {
            var result = new ArrayList<Path>(); result.add(directory); result.addAll(additionalDirectories);
            return List.copyOf(result);
        }
        /** Git worktrees write shared metadata, without needing access to the original checkout's files. */
        public List<Path> writableDirectories() {
            var result = new LinkedHashSet<>(directories());
            for (Path folder : directories()) {
                try {
                    for (Path parent = folder; parent != null; parent = parent.getParent()) {
                        Path marker = parent.resolve(".git");
                        if (Files.isDirectory(marker)) { result.add(marker.toRealPath()); break; }
                        if (!Files.isRegularFile(marker)) continue;
                        String pointer = gitPointer(marker);
                        if (!pointer.startsWith("gitdir: ")) throw new IOException("Invalid Git directory pointer: " + marker);
                        Path gitDirectory = parent.resolve(pointer.substring(8).strip()).toRealPath();
                        result.add(gitDirectory);
                        Path common = gitDirectory.resolve("commondir");
                        if (Files.isRegularFile(common)) result.add(gitDirectory.resolve(gitPointer(common)).toRealPath());
                        break;
                    }
                } catch (IOException failure) {
                    throw new UncheckedIOException("Cannot resolve Git metadata for " + folder, failure);
                }
            }
            return List.copyOf(result);
        }
        private static String gitPointer(Path path) throws IOException {
            if (Files.size(path) > 8192) throw new IOException("Git metadata pointer is too large: " + path);
            String value = Files.readString(path).strip();
            if (value.isEmpty() || value.contains("\n") || value.contains("\r")) throw new IOException("Invalid Git metadata pointer: " + path);
            return value;
        }
    }
    record Session(String threadId, String providerSessionId, String connectionId, Path cwd, String model, String effort, String serviceTier, boolean restorable) {}
    record Input(JsonArray content) {
        public Input {
            if (content == null || content.isEmpty()) throw new IllegalArgumentException("Input must not be empty");
            for (var part : content) SharedModel.validate("promptInputSchema",part);
            content = content.deepCopy();
        }
        @Override public JsonArray content() { return content.deepCopy(); }
        public static Input withImages(String text, List<String> paths) {
            JsonArray content = text.isBlank() && !paths.isEmpty() ? new JsonArray() : text(text).content();
            for (String path : paths) {
                JsonObject part = new JsonObject();
                part.addProperty("type", "localImage");
                part.addProperty("path", path);
                content.add(part);
            }
            return new Input(content);
        }
        public static Input text(String text) {
            JsonObject part = new JsonObject(); part.addProperty("type","text"); part.addProperty("text",text); part.add("mentions",new JsonArray());
            JsonArray content = new JsonArray(); content.add(part); return new Input(content);
        }
    }
    /** kind is delta, interaction/request, interaction/resolved, session/replaced, recovery, or connection/error. */
    record Event(String threadId, String turnId, String kind, String text, JsonObject data) {}
    @FunctionalInterface
    interface ToolHandler {
        /** Thread identity belongs to Too Many Agents; turnId is the native join key resolved by its assembler. */
        CompletableFuture<JsonObject> call(String threadId, String turnId, String tool, JsonObject arguments);
    }

    String providerId();
    /** Labels, capabilities, and provider-specific permission field choices; no live connection required. */
    JsonObject description();
    JsonObject normalizePermissions(JsonObject settings);
    CompletableFuture<List<Model>> connect();
    /** Account quota windows for display; no prompt or conversation turn is submitted. */
    CompletableFuture<JsonObject> usage();
    /** The effective common execution mode; detailed native settings remain providerOptions. */
    String permissionMode(Options options);
    /** Rejection is known before execution; timeouts and disconnections have unknown outcomes. */
    final class RequestFailure extends RuntimeException {
        public final String recovery;
        public final boolean rejected;
        public final JsonObject details;
        public RequestFailure(String message, String recovery, boolean rejected, JsonObject details) {
            super(message); this.recovery = recovery; this.rejected = rejected; this.details = details.deepCopy();
        }
    }
    CompletableFuture<Session> start(String conversationId, Options options);
    CompletableFuture<Session> resume(String conversationId, String providerSessionId, Options options);
    CompletableFuture<String> send(Session session, String requestId, Input input, Options options);
    CompletableFuture<Void> steer(Session session, String requestId, String expectedNativeTurnId, Input input, Options options);
    CompletableFuture<Void> interrupt(Session session);
    /** Applies a permission change to an open session, even mid-turn. False means it waits for the next turn. */
    default CompletableFuture<Boolean> updatePermissions(Session session, JsonObject from, JsonObject to) { return CompletableFuture.completedFuture(false); }
    CompletableFuture<Void> release(Session session);
    CompletableFuture<Void> archive(Session session);
    /** A semantic answer: {kind: approval|user_question|plan_review, decision?, answers?}. */
    CompletableFuture<Void> respond(String requestId, JsonObject answer);
    @Override void close();
}
