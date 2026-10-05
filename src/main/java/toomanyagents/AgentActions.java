package toomanyagents;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Supplier;

/** One body's server-thread action state. Models choose goals; native controls advance each tick. */
final class AgentActions {
    static final List<String> TYPES = List.of("walk", "look", "mine", "place", "equip", "creative_item", "use", "release", "pickup", "give", "interact", "menu", "menu_click", "menu_close");
    final Mob mob;
    final AgentHands hands;
    final AmbientBehavior ambient;
    private final String session;
    private final Supplier<BodyBox> box;
    private final Supplier<ServerPlayer> player;
    private final LinkedHashMap<String, JsonObject> history = new LinkedHashMap<>();
    private JsonObject action;
    private JsonObject args;
    private String kind;
    private int ticks, stillTicks;
    private Vec3 lastPosition;
    private BlockState original;
    private boolean mining;

    AgentActions(Mob mob, String session, Supplier<BodyBox> box, Supplier<ServerPlayer> player) {
        this.mob = mob;
        this.session = session;
        this.box = box;
        this.player = player;
        hands = new AgentHands(mob);
        ambient = new AmbientBehavior(mob);
    }

    boolean busy() { return action != null && "running".equals(action.get("status").getAsString()); }
    JsonObject status(String id) {
        if (id == null || id.isBlank()) return action == null ? object("status", "idle") : action.deepCopy();
        var found = history.get(id);
        if (found == null) throw error("unknown_action");
        return found.deepCopy();
    }

    JsonObject start(JsonObject request) {
        if (busy()) throw error("action_already_running_cancel_or_wait");
        String type = text(request, "type");
        if (!TYPES.contains(type)) throw error("unknown_action_type");
        if (request.has("position") && request.has("entity")) throw error("choose_position_or_entity");
        if (List.of("walk", "look", "interact").contains(type) && !request.has("position") && !request.has("entity")) throw error("position_or_entity_required");
        if (List.of("mine", "place").contains(type) && !request.has("position")) throw error("position_required");
        if (type.equals("give") && !request.has("entity")) throw error("entity_required");
        args = request.deepCopy();
        if (args.has("position")) {
            var pos = BlockPos.containing(position(args));
            if (List.of("mine", "place", "interact").contains(type)) {
                var level = (ServerLevel) mob.level();
                if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) throw error("outside_build_height");
                if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) throw error("target_unloaded_or_outside_world");
            }
        }
        if (List.of("place", "interact").contains(type) && args.has("position")) blockFace();
        if (type.equals("pickup") && args.has("entity") && !(entity() instanceof ItemEntity)) throw error("pickup_target_must_be_item");
        kind = type;
        action = object("id", UUID.randomUUID().toString(), "type", kind, "status", "running", "phase", "starting", "terminal", false, "session", session);
        history.put(text(action, "id"), action);
        while (history.size() > 32) history.remove(history.keySet().iterator().next());
        ticks = stillTicks = 0;
        lastPosition = mob.position();
        original = args.has("position") && List.of("mine", "place", "interact").contains(kind)
            ? mob.level().getBlockState(BlockPos.containing(position(args))) : null;
        mining = false;
        GameAccess.stopFollowingMotion(mob);
        return action.deepCopy();
    }

    JsonObject cancel(String id) {
        if (id != null && !id.isBlank() && (action == null || !id.equals(text(action, "id")))) return status(id);
        if (busy()) finish("interrupted", "cancelled", null);
        // A completed 'use' action can leave a bow or other held item in use.
        hands.stopUsingItem();
        hands.cancelMine();
        GameAccess.stopFollowingMotion(mob);
        return status("");
    }

    void close(String reason) {
        if (busy()) finish("interrupted", reason, null);
        if (mob.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) hands.closeAfterTransfer();
        else hands.closeHands();
    }

    void tick() {
        try {
            hands.tickHands();
            // Explicit pickup must collect and report its own target before it disappears.
            if (!busy() || !kind.equals("pickup")) hands.pickupNearby();
            if (!busy()) return;
            if (++ticks > 1200) { finish("timeout", "Action exceeded 60 seconds of game time.", null); return; }
            switch (kind) {
                case "walk" -> {
                    var wanted = target();
                    var confined = box.get();
                    var target = GameAccess.inside(mob, confined, wanted);
                    double distance = args.has("entity") ? 2.0 : 0.9;
                    if (mob.position().distanceTo(wanted) <= distance) finish("completed", "arrived", null);
                    else if (target != wanted && mob.position().distanceTo(target) <= 0.9) {
                        // The body stops at its box's edge; say so rather than claiming arrival.
                        var result = new JsonObject();
                        result.add("requested", Observations.position(wanted));
                        result.add("box", confined.json());
                        finish("completed", "confined_to_box", result);
                    }
                    else navigate(target, false);
                }
                case "look" -> {
                    face(target());
                    finish("completed", "looking", null);
                }
                case "mine" -> {
                    var pos = checkedBlockTarget();
                    if (!mining && !mob.level().getBlockState(pos).equals(original)) throw error("target_changed");
                    if (!hands.blockReachable(pos)) {
                        if (mining) throw error("target_out_of_reach");
                        navigate(Vec3.atCenterOf(pos), true); return;
                    }
                    GameAccess.stopFollowingMotion(mob);
                    face(Vec3.atCenterOf(pos));
                    JsonObject result = mining ? hands.tickMine() : hands.beginMine(pos);
                    mining = true;
                    action.addProperty("phase", "mining");
                    action.add("progress", result.deepCopy());
                    if ("completed".equals(text(result, "status"))) finish("completed", "mined", result);
                }
                case "place", "interact" -> {
                    if (args.has("entity")) {
                        Entity entity = entity();
                        if (!hands.canReach(entity)) { navigate(entity.getBoundingBox().getCenter(), true); return; }
                        face(entity.getEyePosition());
                        finish("completed", "interacted", hands.interact(entity));
                    } else {
                        var pos = checkedBlockTarget();
                        if (!mob.level().getBlockState(pos).equals(original)) throw error("target_changed");
                        Direction face = blockFace();
                        if (!hands.blockReachable(pos, face)) { navigate(Vec3.atCenterOf(pos), true); return; }
                        face(Vec3.atCenterOf(pos));
                        var result = hands.useBlock(pos, face, args.has("secondaryUse") && args.get("secondaryUse").getAsBoolean());
                        finish("completed", "interaction_finished_check_result", result);
                    }
                }
                case "give" -> {
                    Entity target = entity();
                    if (!(target instanceof ServerPlayer player)) throw error("give_target_must_be_player");
                    if (!hands.canReach(player)) { navigate(player.getBoundingBox().getCenter(), true); return; }
                    face(player.getEyePosition());
                    finish("completed", "handoff_finished", hands.give(player, text(args, "item"), integer(args, "count", 1)));
                }
                case "pickup" -> {
                    if (args.has("entity")) {
                        // Resolve each tick: the requested drop may move, despawn, or be taken.
                        if (!(entity() instanceof ItemEntity item)) throw error("pickup_target_must_be_item");
                        if (!pickupReachable(item)) { navigate(item.position(), false); return; }
                        int before = item.getItem().getCount();
                        var result = hands.pickup(2);
                        int remaining = item.isRemoved() ? 0 : item.getItem().getCount();
                        int collected = Math.max(0, before - remaining);
                        result.addProperty("targetEntity", item.getStringUUID());
                        result.addProperty("targetPickedUp", collected);
                        result.addProperty("targetRemaining", remaining);
                        finish(collected > 0 ? "completed" : "failed",
                            collected == 0 ? "target_not_collected" : remaining > 0 ? "target_partially_picked_up" : "target_picked_up", result);
                    } else {
                        if (args.has("position") && mob.position().distanceTo(position(args)) > 1) { navigate(position(args), false); return; }
                        finish("completed", "pickup_finished", hands.pickup(2));
                    }
                }
                case "equip" -> finish("completed", "equipped", hands.equip(integer(args, "slot", 0), args.has("equipment") ? text(args,"equipment") : "mainhand"));
                case "creative_item" -> finish("completed", "item_selected", hands.creativeItem(text(args,"item"), integer(args,"count",1)));
                case "use" -> finish("completed", "use_started", hands.useHeld());
                case "release" -> finish("completed", "released", hands.releaseHeld());
                case "menu" -> finish("completed", "menu", hands.menuSnapshot());
                case "menu_click" -> finish("completed", "menu_clicked", hands.clickMenu(integer(args,"menuId",-1), integer(args,"slot",-1), integer(args,"button",0), ClickType.valueOf(text(args,"clickType").toUpperCase(Locale.ROOT))));
                case "menu_close" -> finish("completed", "menu_closed", hands.closeMenu());
            }
        } catch (RuntimeException failure) {
            if (busy()) finish("failed", failure.getMessage(), null);
            else throw failure;
        }
    }

    private boolean pickupReachable(ItemEntity item) {
        // Match AgentHands.pickup: distance from the visible body box and a clear
        // eye-to-item line, rather than ordinary entity interaction reach.
        if (mob.getBoundingBox().distanceToSqr(item.position()) > 4) return false;
        var eye = hands.getEyePosition();
        var level = (ServerLevel) mob.level();
        if (!level.hasChunksAt(BlockPos.containing(eye), item.blockPosition())) return false;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, item.position(),
            net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mob));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private BlockPos checkedBlockTarget() {
        var pos = BlockPos.containing(position(args));
        if (!((ServerLevel) mob.level()).hasChunkAt(pos)) throw error("target_chunk_unloaded");
        return pos;
    }

    private Direction blockFace() {
        Direction face = Direction.byName(args.has("face") ? text(args, "face") : "up");
        if (face == null) throw error("invalid_face");
        return face;
    }

    private Entity entity() {
        String id = text(args, "entity");
        if (id.equals("player")) {
            var owner = player.get();
            if (owner != null && owner.level() == mob.level()) return owner;
            throw error("player_unavailable_in_dimension");
        }
        var entity = ((ServerLevel) mob.level()).getEntity(UUID.fromString(id));
        if (entity == null || !entity.isAlive()) throw error("entity_missing_or_unloaded");
        return entity;
    }
    private Vec3 target() { return args.has("entity") ? (kind.equals("look") ? entity().getEyePosition() : entity().position()) : position(args); }

    private void face(Vec3 target) {
        var delta = target.subtract(mob.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
        mob.setYRot(yaw); mob.setYHeadRot(yaw); mob.setYBodyRot(yaw); mob.setXRot(pitch);
        hands.syncBody();
    }

    private void navigate(Vec3 target, boolean workingPosition) {
        var level = (ServerLevel) mob.level();
        var confined = box.get();
        if (mob.position().distanceToSqr(target) > 64 * 64) throw error("target_outside_navigation_radius_64");
        var goal = workingPosition ? target : GameAccess.inside(mob, confined, target);
        if (goal != target && mob.position().distanceToSqr(goal) < 0.81) throw error("target_out_of_reach_from_box");
        var pos = BlockPos.containing(goal);
        if (mob.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation && !mob.onGround()) {
            GameAccess.travelFollowingBody(mob, confined);
            action.addProperty("phase","landing");
            return;
        }
        if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) throw error("target_unloaded_or_outside_world");
        if (!level.isPositionEntityTicking(pos)) throw error("target_outside_simulated_chunks");
        if (ticks == 1 || ticks % 10 == 0 || mob.getNavigation().isDone()) {
            boolean found = false, outsideOnly = false;
            if (workingPosition) {
                // Search physical standing positions; never dig or teleport to manufacture a path.
                var candidates = new ArrayList<BlockPos>();
                for (int dy = -2; dy <= 1; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) candidates.add(pos.offset(dx,dy,dz));
                candidates.sort(Comparator.comparingDouble(p -> mob.position().distanceToSqr(Vec3.atBottomCenterOf(p))));
                for (var candidate : candidates) {
                    if (!level.hasChunkAt(candidate) || !level.getBlockState(candidate).getCollisionShape(level,candidate).isEmpty()
                        || !level.getBlockState(candidate.below()).isCollisionShapeFullBlock(level,candidate.below())) continue;
                    var standing = Vec3.atBottomCenterOf(candidate);
                    // A navigator may finish within a block of its waypoint. Choose a new
                    // working tile if that completion point still cannot reach the target.
                    if (mob.position().distanceToSqr(standing) < 0.81) continue;
                    var box = mob.getBoundingBox().move(standing.subtract(mob.position()));
                    if (level.getBlockCollisions(mob,box).iterator().hasNext()) continue;
                    var eye = standing.add(0,mob.getEyeHeight(),0);
                    double reach = args.has("entity") ? hands.entityInteractionRange() : hands.blockInteractionRange();
                    if (eye.distanceToSqr(target) > (reach - 0.1) * (reach - 0.1)) continue;
                    Direction requiredFace = args.has("position") && (kind.equals("place") || kind.equals("interact")) ? blockFace() : null;
                    if (!visibleWorkTarget(eye, target, pos, requiredFace)) continue;
                    // Interactions may reach out of the box, but the body works from inside it.
                    if (confined != null && !confined.holdsFeet(candidate)) { outsideOnly = true; continue; }
                    outsideOnly = false;
                    var path = mob.getNavigation().createPath(candidate, 0, 64);
                    if (path != null && path.canReach() && GameAccess.staysInside(path, confined)) { found = mob.getNavigation().moveTo(path, 1.0); if (found) break; }
                }
            } else {
                // The convenience moveTo overload accepts a neighboring tile (accuracy 1).
                // Coordinate goals need the actual target tile before our arrival check.
                // Use the physical tool's range rather than this mob species' follow range.
                var path = mob.getNavigation().createPath(pos, 0, 64);
                found = path != null && path.canReach() && GameAccess.staysInside(path, confined) && mob.getNavigation().moveTo(path, 1.0);
            }
            if (!found) throw error(outsideOnly ? "target_out_of_reach_from_box" : confined != null ? "unreachable_inside_box" : "unreachable");
        }
        var path = mob.getNavigation().getPath();
        if (path != null && !path.isDone() && !level.hasChunkAt(path.getNextNodePos())) throw error("path_enters_unloaded_chunk");
        if (path != null && !path.isDone() && !level.isPositionEntityTicking(path.getNextNodePos())) throw error("path_leaves_simulated_chunks");
        mob.getLookControl().setLookAt(target.x,target.y,target.z,30,30);
        mob.getNavigation().tick(); mob.getMoveControl().tick(); mob.getLookControl().tick(); mob.getJumpControl().tick();
        GameAccess.travelFollowingBody(mob, confined);
        action.addProperty("phase", "approaching");
        action.add("position", Observations.position(mob.position()));
        if (mob.position().distanceToSqr(lastPosition) < 0.0001) stillTicks++; else stillTicks = 0;
        lastPosition = mob.position();
        if (stillTicks > 80) throw error("stuck");
    }

    private boolean visibleWorkTarget(Vec3 eye, Vec3 target, BlockPos pos, Direction requiredFace) {
        var level = (ServerLevel) mob.level();
        if (!level.hasChunksAt(BlockPos.containing(eye), pos)) return false;
        if (requiredFace == null) {
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye,target,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,mob));
            return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(pos);
        }
        var shape = level.getBlockState(pos).getShape(level, pos, net.minecraft.world.phys.shapes.CollisionContext.of(mob));
        for (var box : shape.toAabbs()) {
            var center = box.getCenter();
            var point = switch (requiredFace) {
                case WEST -> new Vec3(box.minX + 0.0001, center.y, center.z);
                case EAST -> new Vec3(box.maxX - 0.0001, center.y, center.z);
                case DOWN -> new Vec3(center.x, box.minY + 0.0001, center.z);
                case UP -> new Vec3(center.x, box.maxY - 0.0001, center.z);
                case NORTH -> new Vec3(center.x, center.y, box.minZ + 0.0001);
                case SOUTH -> new Vec3(center.x, center.y, box.maxZ - 0.0001);
            };
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye,Vec3.atLowerCornerOf(pos).add(point),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,mob));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                && hit.getDirection() == requiredFace) return true;
        }
        return false;
    }

    private void finish(String status, String detail, JsonObject result) {
        hands.cancelMine();
        GameAccess.stopFollowingMotion(mob);
        action.addProperty("terminal",true); action.addProperty("status",status); action.addProperty("detail",detail == null ? "action_failed" : detail);
        action.addProperty("ticks",ticks);
        action.add("position", Observations.position(mob.position()));
        if (result != null) action.add("result",result.deepCopy());
    }
    static Vec3 position(JsonObject args) {
        if (!args.has("position") || !args.get("position").isJsonObject()) throw error("position_must_be_xyz_object");
        var p = args.getAsJsonObject("position");
        double x = number(p,"x"), y = number(p,"y"), z = number(p,"z");
        return new Vec3(x,y,z);
    }
    private static double number(JsonObject args,String key) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isNumber()) throw error("invalid_"+key);
        double value = args.get(key).getAsDouble();
        if (!Double.isFinite(value) || Math.abs(value)>30_000_000) throw error("invalid_"+key);
        return value;
    }
    private static int integer(JsonObject args,String key,int fallback) { if (!args.has(key)) return fallback; double n=number(args,key); if(n!=Math.rint(n)) throw error("invalid_"+key); return (int)n; }
    private static String text(JsonObject args,String key) { return args.has(key) ? args.get(key).getAsString() : ""; }
    private static JsonObject object(Object... values) { var result=new JsonObject(); var gson=new Gson(); for(int i=0;i<values.length;i+=2) result.add((String)values[i],gson.toJsonTree(values[i+1])); return result; }
    private static IllegalStateException error(String message) { return new IllegalStateException(message); }
}
