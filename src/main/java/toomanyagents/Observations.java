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
        result.addProperty("id", entity.getId());
        result.addProperty("uuid", entity.getUUID().toString());
        result.addProperty("type", entity instanceof BodyFishingHook ? "minecraft:fishing_bobber"
            : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        result.addProperty("name", entity.getName().getString());
        result.add("position", position(entity.position()));
        result.add("velocity", position(entity.getDeltaMovement()));
        result.addProperty("width", entity.getBbWidth());
        result.addProperty("height", entity.getBbHeight());
        result.addProperty("onGround", entity.onGround());
        result.addProperty("eyeHeight", entity.getEyeHeight());
        result.add("eyePosition", position(entity.getEyePosition()));
        result.add("direction", position(entity.getLookAngle()));
        result.addProperty("yaw", entity.getYRot());
        result.addProperty("pitch", entity.getXRot());
        result.addProperty("alive", entity.isAlive());
        result.addProperty("isInWater", entity.isInWater());
        result.addProperty("isInLava", entity.isInLava());
        result.addProperty("crouching", entity.isShiftKeyDown());
        if (entity instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity rocket) {
            var target = rocket.getEntityData().get(net.minecraft.world.entity.projectile.FireworkRocketEntity.DATA_ATTACHED_TO_TARGET);
            if (target.isPresent()) result.addProperty("fireworkAttachedTo", target.getAsInt());
            result.addProperty("fireworkTicksRemaining", Math.max(0, rocket.lifetime - rocket.life + 1));
        }
        if (entity instanceof net.minecraft.world.entity.ExperienceOrb orb)
            result.addProperty("experienceValue", orb.getValue());
        if (entity instanceof LivingEntity living) {
            result.addProperty("elytraFlying", living.isFallFlying());
            result.addProperty("health", living.getHealth());
            result.addProperty("mainHand", living.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT ? "left" : "right");
            result.addProperty("isSleeping", living.isSleeping());
            result.addProperty("effectTick", living.tickCount);
            result.addProperty("airSupply", living.getAirSupply());
            result.addProperty("maxAirSupply", living.getMaxAirSupply());
            var attributes = new JsonObject();
            for (var attribute : living.getAttributes().getSyncableAttributes()) {
                var key = BuiltInRegistries.ATTRIBUTE.getKey(attribute.getAttribute().value());
                if (key == null) continue;
                var modifiers = new JsonArray();
                attribute.getModifiers().stream().sorted(java.util.Comparator.comparing(modifier -> modifier.id().toString()))
                    .forEach(modifier -> modifiers.add(JsonState.object("uuid", modifier.id().toString(),
                        "amount", modifier.amount(), "operation", modifier.operation().id())));
                var value = JsonState.object("value", attribute.getBaseValue());
                value.add("modifiers", modifiers);
                attributes.add(key.getNamespace().equals("minecraft") ? key.getPath() : key.toString(), value);
            }
            result.add("attributes", attributes);
            var effects = new JsonObject();
            for (var effect : living.getActiveEffects()) {
                int id = BuiltInRegistries.MOB_EFFECT.getId(effect.getEffect().value());
                effects.add(Integer.toString(id), JsonState.object("id", id, "amplifier", effect.getAmplifier(),
                    "duration", effect.getDuration()));
            }
            result.add("effects", effects);
        }
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
