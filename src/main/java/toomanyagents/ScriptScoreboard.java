package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

/** Public scoreboard state, serialized only when native scoreboard data changes. */
final class ScriptScoreboard {
    final ServerScoreboard source;
    private JsonObject cached;
    private long revision;

    ScriptScoreboard(ServerScoreboard source) {
        this.source = source;
        source.addDirtyListener(() -> { cached = null; revision++; });
    }

    JsonObject snapshot(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("scoreboard_requires_server_thread");
        if (cached != null) return cached;
        var result = new JsonObject();
        result.addProperty("revision", Long.toString(revision));
        var positions = new JsonObject();
        var visible = new LinkedHashSet<Objective>();
        for (var slot : DisplaySlot.values()) {
            var objective = source.getDisplayObjective(slot);
            if (objective == null) continue;
            visible.add(objective);
            positions.addProperty(Integer.toString(slot.id()), objective.getName());
        }
        result.add("positions", positions);
        int entries = 0;
        var boards = new JsonArray();
        for (var objective : visible) {
            var board = new JsonObject();
            board.addProperty("name", objective.getName());
            board.add("title", component(level, objective.getDisplayName()));
            var scores = new JsonArray();
            for (var score : source.listPlayerScores(objective)) {
                if (++entries > 4096) throw new IllegalStateException("scoreboard_record_limit");
                scores.add(JsonState.object("name", score.owner(), "value", score.value()));
            }
            board.add("scores", scores); boards.add(board);
        }
        result.add("boards", boards);
        var teams = new JsonArray();
        for (var team : source.getPlayerTeams()) {
            if (++entries > 4096) throw new IllegalStateException("scoreboard_record_limit");
            var row = new JsonObject();
            row.addProperty("team", team.getName());
            row.add("name", component(level, team.getDisplayName()));
            row.add("prefix", component(level, team.getPlayerPrefix()));
            row.add("suffix", component(level, team.getPlayerSuffix()));
            row.addProperty("friendlyFire", team.packOptions());
            row.addProperty("nameTagVisibility", team.getNameTagVisibility().name);
            row.addProperty("collisionRule", team.getCollisionRule().name);
            row.addProperty("formatting", team.getColor().getId());
            var members = new JsonArray();
            for (var name : team.getPlayers()) {
                if (++entries > 4096) throw new IllegalStateException("scoreboard_record_limit");
                members.add(name);
            }
            row.add("members", members); teams.add(row);
        }
        result.add("teams", teams);
        if (result.toString().length() > 1024 * 1024) throw new IllegalStateException("scoreboard_byte_limit");
        cached = result; // Serialized JSON is never mutated after publication.
        return cached;
    }

    private static JsonObject component(ServerLevel level, Component value) {
        return ScriptNbt.typed(ComponentSerialization.CODEC.encodeStart(
            level.registryAccess().createSerializationContext(NbtOps.INSTANCE), value).getOrThrow());
    }
}
