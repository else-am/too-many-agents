package toomanyagents;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

/** A body's ambient behavior while it has no physical action. Server thread only. */
final class AmbientBehavior {
    private final Mob mob;
    private String running = "";
    private int lastTick = -2, wait, walking, swing, returning;
    private Vec3 home;
    boolean failed;

    AmbientBehavior(Mob mob) { this.mob = mob; }

    /** Run one tick of the behavior; it restarts whenever it was interrupted or changed. */
    void tick(BodyBox box, JsonElement behavior, ServerPlayer player) {
        var settings = behavior != null && behavior.isJsonObject() ? behavior.getAsJsonObject() : new JsonObject();
        String type = settings.has("type") ? settings.get("type").getAsString() : "stand";
        int now = mob.getServer().getTickCount();
        if (now != lastTick + 1 || !settings.toString().equals(running)) {
            GameAccess.stopFollowingMotion(mob);
            running = settings.toString();
            wait = 20 + mob.getRandom().nextInt(40);
            if (type.equals("jump")) wait = Math.round(wait / 1.5f);
            walking = swing = 0;
            home = mob.position();
        }
        lastTick = now;
        if (type.equals("follow")) {
            GameAccess.follow(mob, box, player);
            return;
        }
        // A one-block box has nowhere to wander, so the body stands.
        if (type.equals("wander") && (box == null || box.min().getX() != box.max().getX() || box.min().getZ() != box.max().getZ())) wander(box);
        else {
            GameAccess.stopFollowingMotion(mob);
            var target = type.equals("look") || type.equals("swing") ? target(settings, player) : null;
            if (target != null) mob.getLookControl().setLookAt(target.x, target.y, target.z, 10, 40);
            mob.getLookControl().tick();
            if (type.equals("jump") && mob.onGround() && --wait <= 0) {
                mob.jumpFromGround();
                wait = Math.round((40 + mob.getRandom().nextInt(60)) / 1.5f);
            }
            if (type.equals("spin")) {
                float next = mob.getYRot() + 9;
                float yaw = net.minecraft.util.Mth.wrapDegrees(next);
                // Keep the previous angles in the same revolution for render interpolation.
                float wrap = yaw - next;
                mob.yRotO += wrap;
                mob.yHeadRotO += wrap;
                mob.yBodyRotO += wrap;
                mob.setYRot(yaw);
                mob.setYHeadRot(yaw);
                mob.setYBodyRot(yaw);
            }
            // Animation only: the block is never hit, damaged or used.
            if (type.equals("swing") && target != null && --swing <= 0) {
                mob.swing(InteractionHand.MAIN_HAND);
                swing = 10 + mob.getRandom().nextInt(10);
            }
        }
        GameAccess.travelFollowingBody(mob, box);
    }

    /** Walk a displaced body back inside its box, or teleport it if it cannot walk. True while outside. */
    boolean returnInside(BodyBox box) {
        if (box == null || box.holds(mob.position())) {
            if (returning != 0) GameAccess.stopFollowingMotion(mob);
            returning = 0;
            return false;
        }
        lastTick = -2;
        var navigation = mob.getNavigation();
        if (returning < 0) { returning++; GameAccess.travelFollowingBody(mob, box); } // waiting after a failed teleport
        else if (!mob.onGround()) GameAccess.travelFollowingBody(mob, box); // land first
        else if (returning++ == 0) {
            var path = navigation.createPath(box.clamp(mob.blockPosition()), 1);
            if (path == null || !path.canReach() || !GameAccess.staysInside(path, box) || !navigation.moveTo(path, 1.0)) teleportInside(box);
        } else if (navigation.isDone() || returning > 20 * 30) teleportInside(box); // Allow 30 seconds to walk back.
        else {
            navigation.tick(); mob.getMoveControl().tick(); mob.getLookControl().tick(); mob.getJumpControl().tick();
            GameAccess.travelFollowingBody(mob, box);
        }
        return true;
    }

    private void teleportInside(BodyBox box) {
        GameAccess.stopFollowingMotion(mob);
        var from = mob.position();
        float yaw = mob.getYRot(), pitch = mob.getXRot();
        try {
            GameAccess.placeNear((ServerLevel) mob.level(), mob, mob, box);
            mob.setDeltaMovement(Vec3.ZERO);
            mob.fallDistance = 0;
            returning = 0;
        } catch (IllegalStateException noSpace) {
            mob.moveTo(from.x, from.y, from.z, yaw, pitch);
            returning = -200;
        }
    }

    private void wander(BodyBox box) {
        var navigation = mob.getNavigation();
        if (walking > 0) {
            if (navigation.isDone() || --walking == 0) {
                GameAccess.stopFollowingMotion(mob);
                walking = 0;
                wait = 60 + mob.getRandom().nextInt(140);
            } else {
                var path = navigation.getPath();
                if (path != null && !path.isDone() && !mob.level().hasChunkAt(path.getNextNodePos())) { walking = 1; return; }
                navigation.tick(); mob.getMoveControl().tick(); mob.getLookControl().tick(); mob.getJumpControl().tick();
            }
            return;
        }
        mob.getLookControl().tick();
        if (--wait > 0) return;
        wait = 20;
        var spot = randomSpot(box);
        if (spot == null) return;
        var path = navigation.createPath(spot, 0);
        if (path != null && path.canReach() && GameAccess.staysInside(path, box) && navigation.moveTo(path, 0.6)) walking = 200;
    }

    /** A random standing spot near the body, inside its box, or near where wandering began. */
    private BlockPos randomSpot(BodyBox box) {
        var random = mob.getRandom();
        var center = box == null ? BlockPos.containing(home) : mob.blockPosition();
        int range = box == null ? 5 : 6;
        int minX = center.getX() - range, maxX = center.getX() + range, minZ = center.getZ() - range, maxZ = center.getZ() + range;
        int top = center.getY() + 3, bottom = center.getY() - 3;
        if (box != null) {
            minX = Math.max(minX, box.min().getX()); maxX = Math.min(maxX, box.max().getX());
            minZ = Math.max(minZ, box.min().getZ()); maxZ = Math.min(maxZ, box.max().getZ());
            top = Math.min(top, box.max().getY() + 1); bottom = Math.max(bottom, box.min().getY());
            if (minX > maxX || minZ > maxZ) return null;
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = random.nextIntBetweenInclusive(minX, maxX), z = random.nextIntBetweenInclusive(minZ, maxZ);
            for (int y = top; y >= bottom; y--) {
                var pos = new BlockPos(x, y, z);
                if (!mob.level().hasChunkAt(pos)) break;
                if (pos.equals(mob.blockPosition())) continue;
                if (WalkNodeEvaluator.getPathTypeStatic(mob, pos) == PathType.WALKABLE) return pos;
            }
        }
        return null;
    }

    private Vec3 target(JsonObject settings, ServerPlayer player) {
        var target = settings.get("target");
        if (target == null) return null;
        if (target.isJsonPrimitive()) return player != null && player.level() == mob.level() ? player.getEyePosition() : null;
        var xyz = target.getAsJsonObject();
        return Vec3.atCenterOf(new BlockPos(xyz.get("x").getAsInt(), xyz.get("y").getAsInt(), xyz.get("z").getAsInt()));
    }
}
