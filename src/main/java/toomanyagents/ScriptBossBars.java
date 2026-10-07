package toomanyagents;

import com.google.gson.JsonArray;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.wither.WitherBoss;

/** Read native bars relevant to the body; never subscribe the human player. */
final class ScriptBossBars {
    static JsonArray snapshot(ServerLevel level, Mob body, AgentHands hands, JsonArray entities) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("boss_bars_require_server_thread");
        var bars = new LinkedHashSet<ServerBossEvent>();
        if (body instanceof WitherBoss wither) bars.add(wither.bossEvent);
        for (var row : entities) {
            var entity = level.getEntity(row.getAsJsonObject().get("id").getAsInt());
            if (entity instanceof WitherBoss wither) bars.add(wither.bossEvent);
        }
        var fight = level.getDragonFight();
        if (fight != null && fight.validPlayer.test(body)) bars.add(fight.dragonEvent);
        int candidates = 0;
        for (var bar : level.getServer().getCustomBossEvents().getEvents()) {
            if (++candidates > 4096) throw new IllegalStateException("boss_bar_candidate_limit");
            if (bar.getPlayers().contains(hands)) bars.add(bar);
        }
        var result = new JsonArray();
        int bytes = 0;
        for (var bar : bars) {
            if (!bar.isVisible()) continue;
            if (result.size() >= 256) throw new IllegalStateException("boss_bar_record_limit");
            var row = JsonState.object("uuid", bar.getId().toString(), "health", bar.getProgress(),
                "color", bar.getColor().ordinal(), "dividers", bar.getOverlay().ordinal(),
                "flags", (bar.shouldDarkenScreen() ? 1 : 0) | (bar.shouldPlayBossMusic() ? 2 : 0)
                    | (bar.shouldCreateWorldFog() ? 4 : 0));
            var tag = ComponentSerialization.CODEC.encodeStart(
                level.registryAccess().createSerializationContext(NbtOps.INSTANCE), bar.getName()).getOrThrow();
            row.add("title", ScriptNbt.typed(tag));
            bytes += row.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 1024 * 1024) throw new IllegalStateException("boss_bar_snapshot_limit");
            result.add(row);
        }
        return result;
    }
}
