package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import toomanyagents.ui.AgentUiAccess;

/** Automatic chat previews and optional sounds for replies and requests for attention. */
public final class AgentNotifications {
    private final Supplier<? extends AgentUiAccess> access;
    private final Predicate<String> viewingAgent;
    private final Map<String, Boolean> previousAttention = new HashMap<>();
    private final Map<String, Boolean> previousUnread = new HashMap<>();
    private Object previousLevel;
    private long lastCueMs;

    public AgentNotifications(Supplier<? extends AgentUiAccess> access, Predicate<String> viewingAgent) {
        this.access = access;
        this.viewingAgent = viewingAgent;
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    /** Chat remains available while the integrated server is paused. */
    static CompletableFuture<Void> chat(String agentId, String name, String message, BooleanSupplier current) {
        var result = new CompletableFuture<Void>();
        var client = Minecraft.getInstance();
        client.execute(() -> {
            if (result.isDone()) return;
            try {
                if (client.level != null && current.getAsBoolean()) {
                    var notification = Component.literal("[" + name + "] " + message).withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                            "/agents open " + agentId))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                            Component.literal("Open conversation"))));
                    client.getChatListener().handleSystemMessage(notification, false);
                }
                result.complete(null);
            } catch (Exception failure) { result.completeExceptionally(failure); }
        });
        return result.orTimeout(5, TimeUnit.SECONDS);
    }

    private void tick(ClientTickEvent.Post event) {
        var client = Minecraft.getInstance();
        if (previousLevel != client.level) {
            previousLevel = client.level;
            previousAttention.clear();
            previousUnread.clear();
        }
        if (client.level == null) return;
        var current = access.get();
        var agents = current == null ? new JsonArray() : current.worldAgents();
        var present = new HashSet<String>();
        boolean cue = false;
        for (var item : agents) {
            var agent = item.getAsJsonObject();
            String id = text(agent, "id");
            present.add(id);
            boolean attention = flag(agent, "hasPendingInteraction");
            boolean unread = agent.has("latestAttentionAt") && agent.get("latestAttentionAt").getAsLong()
                > (agent.has("lastReadAt") && !agent.get("lastReadAt").isJsonNull() ? agent.get("lastReadAt").getAsLong() : 0);
            Boolean before = previousAttention.put(id, attention);
            Boolean readBefore = previousUnread.put(id, unread);
            // First sightings and reconnects establish a quiet baseline.
            if (Boolean.FALSE.equals(before) && attention) cue = true;
            if (Boolean.FALSE.equals(readBefore) && unread && !viewingAgent.test(id)) cue = true;
        }
        previousAttention.keySet().retainAll(present);
        previousUnread.keySet().retainAll(present);
        long now = System.currentTimeMillis();
        if (cue && TooManyAgentsClientSettings.get().notificationSound() && now - lastCueMs > 1500) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.5F, 0.25F));
            lastCueMs = now;
        }
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static boolean flag(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }
}
