package toomanyagents.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.concurrent.CompletableFuture;

/** Client UI access to serialized agent state; implementations own all game-thread scheduling. */
public interface AgentUiAccess {
    CompletableFuture<JsonObject> catalog();
    CompletableFuture<JsonObject> catalog(String providerId);
    CompletableFuture<JsonObject> catalog(String providerId, String environmentId);
    CompletableFuture<JsonObject> usage();
    CompletableFuture<JsonObject> backendStatus();
    CompletableFuture<JsonObject> backendConfig();
    CompletableFuture<JsonObject> setDefaultProvider(String providerId);
    CompletableFuture<JsonObject> projectExecutionOptions(String projectId);
    JsonObject projects();
    CompletableFuture<JsonObject> projectCreationOptions(String projectId);
    CompletableFuture<JsonObject> projectCommand(JsonObject request);
    CompletableFuture<String> spawn(JsonObject settings);
    JsonArray list();
    JsonArray worldAgents();
    CompletableFuture<JsonObject> inventory(String agentId);
    CompletableFuture<Void> openInventory(String agentId);
    void markRead(String agentId, long replyVersion);
    JsonArray profiles();
    CompletableFuture<Void> saveProfile(String name, JsonObject settings);
    CompletableFuture<Void> updateSettings(String agentId, JsonObject settings);
    /** {@code text}, and optionally {@code model}, {@code reasoningLevel}, {@code serviceTier}, {@code permissionMode}, {@code delivery}, {@code pointing}, {@code images}. */
    CompletableFuture<Void> send(String agentId, JsonObject message);
    CompletableFuture<Void> steerQueued(String agentId, String messageId);
    CompletableFuture<Void> cancelQueued(String agentId, String messageId);
    JsonObject snapshot(String agentId);
    CompletableFuture<JsonObject> transcript(String agentId, JsonObject query);
    CompletableFuture<JsonObject> chatAsset(String agentId, String kind, String source);
    CompletableFuture<Void> openChatLink(String agentId, String target);
    CompletableFuture<JsonObject> timelineTurnSummaryDetails(String agentId, JsonObject query);
    CompletableFuture<Void> checkBody(String agentId);
    CompletableFuture<Void> interrupt(String agentId);
    CompletableFuture<Void> remove(String agentId, boolean archiveThread);
    CompletableFuture<Void> archiveConversation(String agentId, boolean archived);
    CompletableFuture<Void> respond(String agentId, String requestId, JsonObject resolution);
}
