package toomanyagents.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.concurrent.CompletableFuture;

/** Client UI access to serialized agent state; implementations own all game-thread scheduling. */
public interface AgentUiAccess {
    CompletableFuture<JsonObject> catalog();
    CompletableFuture<JsonObject> catalog(String providerId);
    CompletableFuture<JsonObject> usage();
    String defaultPermissionMode();
    JsonObject projects();
    CompletableFuture<JsonObject> projectCommand(JsonObject request);
    CompletableFuture<String> spawn(JsonObject settings);
    JsonArray list();
    JsonArray worldAgents();
    CompletableFuture<JsonObject> inventory(String agentId);
    CompletableFuture<Void> openInventory(String agentId);
    void markErrorRead(String agentId, long errorVersion);
    void markRead(String agentId, long replyVersion);
    JsonArray profiles();
    CompletableFuture<Void> saveProfile(String name, JsonObject settings);
    CompletableFuture<Void> updateSettings(String agentId, JsonObject settings);
    CompletableFuture<Void> send(String agentId, String text, String model, String effort, JsonObject pointing);
    CompletableFuture<Void> send(String agentId, String text, String model, String effort, JsonObject pointing, String delivery);
    CompletableFuture<Void> send(String agentId, String text, String model, String effort, JsonObject pointing, String delivery, String serviceTier);
    CompletableFuture<Void> send(String agentId, String text, String model, String effort, JsonObject pointing, String delivery, String serviceTier, java.util.List<String> images);
    CompletableFuture<Void> steerQueued(String agentId, String messageId);
    CompletableFuture<Void> cancelQueued(String agentId, String messageId);
    JsonObject snapshot(String agentId);
    CompletableFuture<JsonObject> transcript(String agentId, JsonObject query);
    CompletableFuture<Void> checkBody(String agentId);
    CompletableFuture<Void> setFollowing(String agentId, boolean following);
    CompletableFuture<Void> send(String agentId, String text, String model, String effort);
    CompletableFuture<Void> interrupt(String agentId);
    CompletableFuture<Void> remove(String agentId, boolean archiveThread);
    CompletableFuture<String> retire(String agentId, boolean deleteHistory);
    CompletableFuture<Void> archiveConversation(String agentId, boolean archived);
    CompletableFuture<Void> respond(String agentId, String requestId, String answer);
}
