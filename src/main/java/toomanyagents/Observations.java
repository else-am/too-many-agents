package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

final class Observations {
    private Observations() {}

    static JsonObject position(Vec3 position) {
        var result = new JsonObject();
        result.addProperty("x", position.x);
        result.addProperty("y", position.y);
        result.addProperty("z", position.z);
        return result;
    }

    static JsonObject entity(Entity entity) {
        var result = new JsonObject();
        result.addProperty("uuid", entity.getUUID().toString());
        result.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        result.addProperty("name", entity.getName().getString());
        result.add("position", position(entity.position()));
        result.add("eyePosition", position(entity.getEyePosition()));
        result.add("direction", position(entity.getLookAngle()));
        result.addProperty("yaw", entity.getYRot());
        result.addProperty("pitch", entity.getXRot());
        result.addProperty("alive", entity.isAlive());
        if (entity instanceof LivingEntity living) result.addProperty("health", living.getHealth());
        var saved = entity.getPersistentData();
        if (saved.contains("too_many_agents_agent")) {
            var agent = new JsonObject();
            agent.addProperty("id", saved.getString("too_many_agents_agent"));
            if (entity instanceof Mob mob) {
                agent.addProperty("navigationDone", mob.getNavigation().isDone());
            }
            result.add("agent", agent);
        }
        return result;
    }

    static JsonArray coordinates(int x, int y, int z) {
        var array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }
}
