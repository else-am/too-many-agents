package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.NeoForgeMod;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Executes supplied edges; never asks the native navigator to find a route. Server thread only. */
final class ScriptNavigation {
    private static final int MAX_NODES = 128, MAX_EDITS = 128, MAX_TICKS = 2400, EDGE_TICKS = 240;
    private static final double EPS = 1e-6;
    // Method ownership is immutable for a loaded class; body state is not.
    private static final ClassValue<Boolean> ORDINARY_PHYSICS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("travel", Vec3.class).getDeclaringClass() == LivingEntity.class
                    && type.getMethod("jumpFromGround").getDeclaringClass() == LivingEntity.class
                    && inherits(type, "getJumpPower") && inherits(type, "getFlyingSpeed")
                    && inherits(type, "isAffectedByFluids");
            } catch (ReflectiveOperationException failure) { return false; }
        }
    };
    private final Mob mob;
    private final AgentHands hands;
    private final Supplier<BodyBox> box;
    private final ServerLevel level;
    private final List<JsonObject> nodes = new ArrayList<>();
    private final JsonArray edits = new JsonArray();
    private final List<Frame> trajectory = new ArrayList<>();
    private BlockPos cacheMin;
    private int sx, sy, sz;
    private int[] expected;
    private int index, breakIndex, placeIndex, ticks, edgeTicks, frameIndex, lastTick = -1;
    private int toolSlot, scaffoldSlot, predictionSteps;
    private long predictionDeadline;
    private final int[] currentSlots = new int[41];
    private Vec3 edgeStart, target, returnTo;
    private AABB relevant;
    private BlockPos mining;
    private int miningBefore;
    private boolean active, completed, stopRequested, stopped, sprintAllowed, edgeStarted, tower;
    private String phase = "idle";

    private record Frame(Vec3 before, Vec3 after, Vec3 beforeVelocity, boolean beforeGround, Vec3 target, boolean jump, boolean sprint) {}
    private record Motion(Vec3 delta, boolean ground, boolean wall) {}

    ScriptNavigation(Mob mob, AgentHands hands, Supplier<BodyBox> box) {
        this.mob = mob;
        this.hands = hands;
        this.box = box;
        this.level = (ServerLevel) mob.level();
    }

    void start(JsonObject request) {
        requireThread();
        try { parseStart(request); }
        catch (IllegalStateException failure) { throw failure; }
        catch (RuntimeException failure) { throw error("route_invalid_request"); }
    }

    private void parseStart(JsonObject request) {
        if (active) throw error("route_already_running");
        nodes.clear(); while (!edits.isEmpty()) edits.remove(edits.size() - 1); trajectory.clear();
        index = breakIndex = placeIndex = ticks = edgeTicks = frameIndex = 0;
        completed = stopRequested = stopped = edgeStarted = tower = false;
        mining = null; returnTo = null; lastTick = -1;
        if (!ordinaryPhysics(mob)) throw error("route_unsupported_physics");
        if (!inside(mob.position())) throw error("route_outside_body_box");
        Vec3 start = vector(request.getAsJsonObject("start"));
        if (mob.position().distanceTo(start) > 0.2) throw error("route_start_changed");
        JsonArray raw = request.getAsJsonArray("nodes");
        if (raw == null || raw.size() > MAX_NODES) throw error("route_node_limit");
        int changes = 0;
        BlockPos previous = logicalPosition(start);
        for (JsonElement element : raw) {
            JsonObject node = element.getAsJsonObject().deepCopy();
            BlockPos pos = integerPosition(node);
            int dx = Math.abs(pos.getX() - previous.getX()), dz = Math.abs(pos.getZ() - previous.getZ());
            int dy = pos.getY() - previous.getY();
            boolean parkour = flag(node, "parkour");
            if (dx > (parkour ? 4 : 1) || dz > (parkour ? 4 : 1) || dy > 1 || dy < -16
                || parkour && dx != 0 && dz != 0) throw error("route_discontinuous_edge");
            for (String key : List.of("toBreak", "toPlace")) {
                if (!node.has(key)) node.add(key, new JsonArray());
                for (JsonElement edit : node.getAsJsonArray(key)) {
                    JsonObject action = edit.getAsJsonObject();
                    integerPosition(action);
                    if (key.equals("toBreak") && action.has("toolSlot")) slot(action, "toolSlot", 0);
                    if (key.equals("toPlace") && !flag(action, "useOne")) face(action);
                    if (++changes > MAX_EDITS) throw error("route_edit_limit");
                }
            }
            nodes.add(node); previous = pos;
        }
        JsonObject cache = request.getAsJsonObject("blocks");
        if (cache == null) throw error("route_snapshot_required");
        cacheMin = arrayPosition(cache.getAsJsonArray("min"));
        BlockPos size = arrayPosition(cache.getAsJsonArray("size"));
        sx = size.getX(); sy = size.getY(); sz = size.getZ();
        long count = (long) sx * sy * sz;
        if (sx < 1 || sy < 1 || sz < 1 || sx > 128 || sy > 128 || sz > 128 || count > 65536)
            throw error("route_snapshot_limit");
        JsonArray states = cache.getAsJsonArray("states");
        if (states == null || states.size() != count) throw error("route_snapshot_invalid");
        expected = new int[(int) count];
        for (int i = 0; i < expected.length; i++) expected[i] = integer(states.get(i));
        sprintAllowed = flag(request, "allowSprinting");
        toolSlot = slot(request, "toolSlot", hands.getInventory().selected);
        scaffoldSlot = slot(request, "scaffoldingSlot", hands.getInventory().selected);
        for (int i = 0; i < currentSlots.length; i++) currentSlots[i] = i;
        clearControls();
        active = true;
        phase = "starting";
    }

    void requestStop() { requireThread(); stopRequested = true; }

    JsonObject tick() {
        requireThread();
        if (completed) return progress();
        if (!active) throw error("route_not_running");
        int now = level.getServer().getTickCount();
        if (lastTick == now) throw error("route_duplicate_tick");
        lastTick = now;
        if (++ticks > MAX_TICKS) throw error("route_timeout");
        if (mob.isRemoved() || !mob.isAlive() || mob.level() != level) throw error("route_body_changed");
        if (!ordinaryPhysics(mob)) throw error("route_unsupported_physics");
        if (!inside(mob.position())) throw error("route_outside_body_box");
        clearInputs();
        if (!trajectory.isEmpty() && frameIndex == trajectory.size()) { trajectory.clear(); frameIndex = 0; clearControls(); }
        Frame predicted = null;
        boolean checkArrival = false;
        try {
            if (index == nodes.size()) {
                if (settled()) complete();
                else { phase = "landing"; clearControls(); }
            } else if (stopRequested && !edgeStarted && settled()) {
                complete();
            } else {
                if (!edgeStarted) beginEdge();
                if (++edgeTicks > EDGE_TICKS && mining == null) throw error("route_edge_timeout");
                checkVolume(relevant);
                JsonObject node = nodes.get(index);
                if (mining != null) {
                    phase = "mining";
                    JsonObject result = hands.tickMine();
                    if ("completed".equals(result.get("status").getAsString())) finishMining();
                } else if (frameIndex < trajectory.size()) {
                    predicted = followFrame();
                } else if (returnTo != null) {
                    Vec3 destination = returnTo; returnTo = null;
                    plan(destination, false);
                    if (!trajectory.isEmpty()) predicted = followFrame();
                } else if (breakIndex < node.getAsJsonArray("toBreak").size()) {
                    beginMining(node.getAsJsonArray("toBreak").get(breakIndex).getAsJsonObject());
                } else if (placeIndex < node.getAsJsonArray("toPlace").size()) {
                    place(node.getAsJsonArray("toPlace").get(placeIndex).getAsJsonObject());
                } else {
                    target = destination(integerPosition(node));
                    if (arrivedAtNode(target)) { clearControls(); checkArrival = true; }
                    else if (mob.isInWater() || mob.onClimbable() || waterOrClimb(integerPosition(node))) {
                        specialTravel(target);
                        // A ladder descent can move .15 in one tick. Accept its
                        // first post-travel arrival, not two ticks in a .12 band.
                        checkArrival = true;
                    } else {
                        phase = flag(node, "parkour") ? "parkour" : "moving";
                        plan(target, flag(node, "parkour"));
                        if (!trajectory.isEmpty()) predicted = followFrame();
                    }
                }
            }
            // Exactly one native travel on a successful tick, including mining and building waits.
            travel();
            if (predicted != null && mob.position().distanceTo(predicted.after) > 0.075)
                throw error("route_trajectory_changed");
            if (predicted != null) frameIndex++;
            if (checkArrival && arrivedAtNode(target)) finishEdge();
            return progress();
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    void stop() {
        requireThread();
        try { hands.cancelMine(); }
        finally {
            mining = null; clearControls(); active = false; trajectory.clear();
        }
        // Outer AgentActions owns subsequent passive gravity after cancellation.
    }

    private void beginEdge() {
        edgeStarted = true; edgeTicks = breakIndex = placeIndex = frameIndex = 0;
        trajectory.clear(); tower = false; returnTo = null;
        edgeStart = mob.position();
        Vec3 raw = Vec3.atBottomCenterOf(integerPosition(nodes.get(index)));
        double rise = Math.min(4, jumpRise(mob));
        relevant = mob.getBoundingBox().minmax(mob.getBoundingBox().move(raw.subtract(edgeStart)))
            .inflate(0.1, 0, 0.1).expandTowards(0, rise + 0.1, 0).expandTowards(0, -1, 0);
        checkVolume(relevant);
        for (String key : List.of("toBreak", "toPlace")) for (JsonElement entry : nodes.get(index).getAsJsonArray(key)) {
            BlockPos pos = integerPosition(entry.getAsJsonObject());
            checkCell(pos);
            if (Vec3.atCenterOf(pos).distanceTo(edgeStart) > 6) throw error("route_edit_outside_edge");
        }
    }

    private void finishEdge() {
        index++; edgeStarted = false; trajectory.clear(); frameIndex = 0;
        clearControls();
        if (stopRequested || index == nodes.size()) complete();
    }

    private void complete() {
        stopped = stopRequested;
        completed = true; active = false;
        phase = stopped ? "stopped" : "arrived";
        clearControls();
    }

    JsonObject progress() {
        JsonObject result = new JsonObject();
        result.addProperty("status", completed ? "completed" : "running");
        result.addProperty("phase", phase);
        result.addProperty("node", index); result.addProperty("totalNodes", nodes.size()); result.addProperty("ticks", ticks);
        result.addProperty("stopped", stopped);
        result.addProperty("isMining", active && mining != null);
        result.addProperty("isBuilding", active && (phase.equals("building") || phase.equals("placement_stance")));
        result.add("position", xyz(mob.position())); result.add("edits", edits.deepCopy());
        return result;
    }

    private void beginMining(JsonObject edit) {
        BlockPos pos = integerPosition(edit);
        checkCell(pos); equip(slot(edit, "toolSlot", toolSlot), false);
        mining = pos; miningBefore = Block.getId(level.getBlockState(pos));
        phase = "mining";
        JsonObject result = hands.beginMine(pos);
        if ("completed".equals(result.get("status").getAsString())) finishMining();
    }

    private void finishMining() {
        BlockState after = level.getBlockState(mining);
        if (!after.getCollisionShape(level, mining, CollisionContext.of(mob)).isEmpty()) throw error("route_break_did_not_clear");
        recordEdit("break", mining, miningBefore);
        mining = null; breakIndex++; edgeTicks = 0;
    }

    private void place(JsonObject edit) {
        phase = "building";
        BlockPos reference = integerPosition(edit);
        checkCell(reference);
        if (flag(edit, "useOne")) { use(reference); placeIndex++; return; }
        equip(scaffoldSlot, true);
        if (!(hands.getMainHandItem().getItem() instanceof BlockItem item)) throw error("route_missing_scaffolding");
        // Context-changing BlockItem subclasses require their own exact target validation.
        if (item.getClass() != BlockItem.class) throw error("route_unsupported_placement_item");
        Direction direction = face(edit);
        BlockPos desired = reference.relative(direction);
        checkCell(desired);
        if (flag(edit, "jump")) {
            if (!tower) {
                if (!mob.onGround() || jumpRise(mob) < desired.getY() + 1 - mob.getY()) throw error("route_jump_unavailable");
                AABB clearance = mob.getBoundingBox().expandTowards(0, jumpRise(mob), 0);
                if (!level.noCollision(mob, clearance)) throw error("route_jump_obstructed");
                mob.setSprinting(false); mob.jumpFromGround(); tower = true;
                return;
            }
            if (mob.getBoundingBox().minY < desired.getY() + 1 - EPS) {
                if (mob.getDeltaMovement().y <= 0) throw error("route_tower_clearance_failed");
                return;
            }
        }
        if (!hands.blockReachable(reference, direction)) {
            if (!tower && direction.getAxis().isHorizontal() && mob.onGround()) {
                Vec3 stance = Vec3.atBottomCenterOf(reference.above()).add(direction.getStepX() * (0.5 + mob.getBbWidth() / 2 - 0.1), 0,
                    direction.getStepZ() * (0.5 + mob.getBbWidth() / 2 - 0.1));
                stance = new Vec3(stance.x, mob.getY(), stance.z);
                if (mob.position().distanceTo(stance) > 0.12 && stance.distanceTo(edgeStart) <= 1.5) {
                    phase = "placement_stance"; returnTo = stance; return;
                }
            }
            throw error("route_placement_out_of_reach");
        }
        BlockHitResult hit = hit(reference, direction);
        BlockPlaceContext context = new BlockPlaceContext(hands, InteractionHand.MAIN_HAND, hands.getMainHandItem(), hit);
        if (!context.getClickedPos().equals(desired)) throw error("route_placement_target_changed");
        BlockState proposed = item.getBlock().getStateForPlacement(context);
        if (proposed == null || !context.canPlace()) throw error("route_placement_rejected");
        for (AABB bounds : proposed.getCollisionShape(level, desired, CollisionContext.of(mob)).toAabbs())
            if (bounds.move(desired).intersects(mob.getBoundingBox())) throw error("route_placement_intersects_body");
        int before = Block.getId(level.getBlockState(desired));
        int count = hands.getMainHandItem().getCount();
        hands.useBlock(reference, direction, true);
        BlockState after = level.getBlockState(desired);
        if (!after.is(item.getBlock()) || Block.getId(after) == before || after.getCollisionShape(level, desired, CollisionContext.of(mob)).isEmpty())
            throw error("route_placement_outcome_mismatch");
        if (!hands.isCreative() && hands.getMainHandItem().getCount() != count - 1) throw error("route_placement_inventory_mismatch");
        recordEdit("place", desired, before);
        placeIndex++; tower = false;
        if (edit.has("returnPos")) returnTo = Vec3.atBottomCenterOf(integerPosition(edit.getAsJsonObject("returnPos")));
    }

    private void use(BlockPos pos) {
        BlockState before = level.getBlockState(pos);
        if (!before.hasProperty(BlockStateProperties.OPEN)) throw error("route_unsupported_block_use");
        if (before.getValue(BlockStateProperties.OPEN)) return;
        BlockPos other = null;
        int otherBefore = 0;
        if (before.getBlock() instanceof DoorBlock) {
            other = before.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            checkCell(other); otherBefore = Block.getId(level.getBlockState(other));
        }
        Direction visible = null;
        for (Direction candidate : Direction.values()) if (hands.blockReachable(pos, candidate)) { visible = candidate; break; }
        if (visible == null) throw error("route_use_out_of_reach");
        hands.useBlock(pos, visible, false);
        BlockState after = level.getBlockState(pos);
        if (!after.is(before.getBlock()) || !after.hasProperty(BlockStateProperties.OPEN) || !after.getValue(BlockStateProperties.OPEN))
            throw error("route_use_outcome_mismatch");
        recordEdit("use", pos, Block.getId(before));
        if (other != null) recordEdit("use", other, otherBefore);
    }

    private void equip(int originalSlot, boolean material) {
        int chosen = currentSlots[originalSlot];
        int selected = hands.getInventory().selected;
        if (material && hands.getInventory().getItem(chosen).isEmpty()) throw error("route_missing_scaffolding");
        if (chosen == selected) return;
        hands.equip(chosen, "mainhand");
        for (int i = 0; i < currentSlots.length; i++) currentSlots[i] = swapped(currentSlots[i], chosen, selected);
    }

    private static int swapped(int value, int a, int b) { return value == a ? b : value == b ? a : value; }

    private void recordEdit(String kind, BlockPos pos, int before) {
        int after = Block.getId(level.getBlockState(pos));
        expected[cacheIndex(pos)] = after;
        JsonObject result = new JsonObject(); result.addProperty("kind", kind); result.add("position", xyz(Vec3.atLowerCornerOf(pos)));
        result.addProperty("before", before); result.addProperty("after", after); edits.add(result);
    }

    private void specialTravel(Vec3 goal) {
        if (mob.isInWater()) {
            if (!mob.canSwimInFluidType(NeoForgeMod.WATER_TYPE.value())) throw error("route_cannot_swim");
            phase = "swimming";
            steer(goal, false);
            BlockPos node = integerPosition(nodes.get(index));
            // Aim inside a water cell, not at its lower goal plane. Keep the
            // collision-shape landing height when swimming out onto dry support.
            double swimY = level.getFluidState(node).is(FluidTags.WATER) ? node.getY() + 0.5 : goal.y;
            double velocityY = mob.getDeltaMovement().y;
            double nextY = mob.getY() + velocityY;
            // Mob.jumpInFluid adds 0.3 when canFloat is false. Do not stack
            // impulses while already rising, or sink impulses while descending.
            if (nextY < swimY && velocityY <= 0) mob.jumpInFluid(NeoForgeMod.WATER_TYPE.value());
            else if (nextY > swimY && velocityY >= 0) mob.sinkInFluid(NeoForgeMod.WATER_TYPE.value());
        } else if (mob.onClimbable()) {
            phase = "climbing";
            steer(goal, false);
            mob.setJumping(goal.y > mob.getY());
        } else {
            phase = "moving";
            steer(goal, false);
        }
    }

    private boolean waterOrClimb(BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER) || level.getBlockState(pos).is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    private void plan(Vec3 goal, boolean parkour) {
        trajectory.clear(); frameIndex = 0; predictionSteps = 0;
        predictionDeadline = System.nanoTime() + 25_000_000L;
        if (arrived(goal)) return;
        List<Frame> best = simulate(goal, -1, false);
        if (best == null && mob.onGround() && jumpRise(mob) > 0) {
            for (boolean sprint : new boolean[]{false, true}) {
                if (sprint && !sprintAllowed) continue;
                for (int takeoff = 0; takeoff <= (parkour ? 12 : 5); takeoff++) {
                    List<Frame> candidate = simulate(goal, takeoff, sprint);
                    if (candidate != null && (best == null || candidate.size() < best.size())) best = candidate;
                }
            }
        }
        if (best == null) throw error(parkour ? "route_parkour_unexecutable" : "route_edge_unexecutable");
        trajectory.addAll(best);
    }

    private Frame followFrame() {
        Frame frame = trajectory.get(frameIndex);
        if (mob.position().distanceTo(frame.before) > 0.075 || mob.getDeltaMovement().distanceTo(frame.beforeVelocity) > 0.035
            || mob.onGround() != frame.beforeGround) throw error("route_trajectory_changed");
        steer(frame.target, frame.sprint);
        if (frame.jump) {
            if (!mob.onGround()) throw error("route_takeoff_not_grounded");
            mob.jumpFromGround();
        }
        return frame;
    }

    private void steer(Vec3 goal, boolean sprint) {
        double dx = goal.x - mob.getX(), dz = goal.z - mob.getZ();
        mob.setSprinting(sprint);
        if (dx * dx + dz * dz < 0.0025) { mob.setSpeed(0); return; }
        mob.setYRot((float) (Math.atan2(dz, dx) * 180 / Math.PI - 90));
        mob.getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1);
        mob.getMoveControl().tick();
        // Gap takeoff belongs to our schedule, not MoveControl's obstacle heuristic.
        mob.getJumpControl().tick(); mob.setJumping(false);
    }

    private List<Frame> simulate(Vec3 goal, int takeoff, boolean sprint) {
        if (mob.isInWater() || mob.isInLava() || mob.onClimbable() || mob.isNoGravity() || mob.shouldDiscardFriction()
            || mob.hasEffect(MobEffects.LEVITATION) || mob.hasEffect(MobEffects.SLOW_FALLING)) return null;
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement();
        AABB bounds = mob.getBoundingBox();
        boolean ground = mob.onGround();
        BlockPos supporting = mob.mainSupportingBlockPos.orElse(null);
        boolean groundWithoutBlock = ground && supporting == null;
        double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1);
        double speed = baseSpeed * (sprint ? 1.3 : 1);
        List<Frame> frames = new ArrayList<>();
        for (int tick = 0; tick < 100; tick++) {
            if (++predictionSteps > 2700 || System.nanoTime() > predictionDeadline) throw error("route_prediction_budget");
            if (tick > 0) velocity = small(velocity.scale(0.98)); // normal NoAI aiStep between Post calls
            Vec3 beforeVelocity = velocity;
            boolean beforeGround = ground;
            Vec3 difference = goal.subtract(pos);
            double distance = difference.horizontalDistance();
            if (distance < 0.12 && Math.abs(difference.y) < 0.12 && ground) return frames;
            Vec3 direction = distance < 0.05 ? Vec3.ZERO : new Vec3(difference.x / distance, 0, difference.z / distance);
            BlockPos support = movementSupport(pos, supporting);
            if (!known(support) || !known(BlockPos.containing(pos))) return null;
            BlockState state = level.getBlockState(support);
            if (!level.getFluidState(BlockPos.containing(pos)).isEmpty()) return null;
            float friction = state.getFriction(level, support, mob);
            if (!(friction >= 0.6F && friction <= 1)) return null;
            double drag = ground ? friction * 0.91F : 0.91F;
            boolean jump = tick == takeoff;
            if (jump) {
                if (!ground) return null;
                double power = jumpPower(mob, pos, support);
                if (power <= 0) return null;
                velocity = new Vec3(velocity.x, power, velocity.z);
                if (sprint) velocity = velocity.add(direction.scale(0.2));
            }
            double acceleration = ground ? speed * (0.21600002F / (friction * friction * friction)) : 0.02F;
            velocity = velocity.add(direction.scale(acceleration * Math.min(1, speed)));
            Motion motion = collide(bounds, velocity, ground);
            Vec3 next = pos.add(motion.delta);
            if (!inside(next) || !withinEdge(next)) return null;
            if (next.y < Math.min(edgeStart.y, goal.y) - 1.1) return null;
            Vec3 remaining = new Vec3(Math.abs(velocity.x - motion.delta.x) > EPS ? 0 : velocity.x,
                Math.abs(velocity.y - motion.delta.y) > EPS ? 0 : velocity.y,
                Math.abs(velocity.z - motion.delta.z) > EPS ? 0 : velocity.z);
            AABB nextBounds = bounds.move(motion.delta);
            BlockPos nextSupporting = null;
            if (motion.ground) {
                nextSupporting = supportingBlock(nextBounds, next);
                if (nextSupporting == null && !groundWithoutBlock)
                    nextSupporting = supportingBlock(nextBounds.move(-motion.delta.x, 0, -motion.delta.z), next);
            }
            groundWithoutBlock = motion.ground && nextSupporting == null;
            supporting = nextSupporting;
            BlockState feet = level.getBlockState(BlockPos.containing(next));
            float factor = feet.getBlock().getSpeedFactor();
            if (factor == 1) factor = level.getBlockState(movementSupport(next, supporting)).getBlock().getSpeedFactor();
            remaining = remaining.multiply(factor * drag, 1, factor * drag);
            remaining = new Vec3(remaining.x, (remaining.y - mob.getGravity()) * 0.98F, remaining.z);
            frames.add(new Frame(pos, next, beforeVelocity, beforeGround, goal, jump, sprint));
            bounds = nextBounds; pos = next; velocity = remaining; ground = motion.ground;
            if (motion.wall && motion.delta.horizontalDistanceSqr() < 1e-7 && tick > 15) return null;
        }
        return null;
    }

    private static BlockPos movementSupport(Vec3 position, BlockPos supporting) {
        int y = (int) Math.floor(position.y - 0.500001F);
        return supporting == null ? BlockPos.containing(position.x, y, position.z) : supporting.atY(y);
    }

    private BlockPos supportingBlock(AABB bounds, Vec3 position) {
        AABB feet = new AABB(bounds.minX, bounds.minY - 1e-6, bounds.minZ, bounds.maxX, bounds.minY, bounds.maxZ);
        var blocks = new BlockCollisions<BlockPos>(level, mob, feet, false, (pos, shape) -> pos);
        BlockPos result = null;
        double closest = Double.MAX_VALUE;
        while (blocks.hasNext()) {
            BlockPos pos = blocks.next();
            double distance = pos.distToCenterSqr(position);
            if (distance < closest || distance == closest && (result == null || result.compareTo(pos) < 0)) {
                closest = distance; result = pos.immutable();
            }
        }
        return result;
    }

    /** Shape-step strategy from Minecraft 1.21.1 Entity.collide (NeoForge 21.1.251 generated sources).
     * Uses the public native collision kernel with hypothetical AABBs; does not mutate an entity. */
    private Motion collide(AABB bounds, Vec3 wanted, boolean ground) {
        checkVolume(bounds.expandTowards(wanted).expandTowards(0, mob.maxUpStep(), 0));
        var entities = level.getEntityCollisions(mob, bounds.expandTowards(wanted));
        Vec3 clipped = Entity.collideBoundingBox(mob, wanted, bounds, level, entities);
        boolean below = wanted.y != clipped.y && wanted.y < 0;
        boolean wall = wanted.x != clipped.x || wanted.z != clipped.z;
        if (mob.maxUpStep() > 0 && (below || ground) && wall) {
            AABB base = below ? bounds.move(0, clipped.y, 0) : bounds;
            AABB sweep = base.expandTowards(wanted.x, mob.maxUpStep(), wanted.z).expandTowards(0, -1e-5, 0);
            TreeSet<Double> heights = new TreeSet<>();
            List<VoxelShape> shapes = new ArrayList<>(level.getEntityCollisions(mob, sweep));
            level.getBlockCollisions(mob, sweep).forEach(shapes::add);
            for (VoxelShape shape : shapes) for (double y : shape.getCoords(Direction.Axis.Y)) {
                double h = (float) (y - base.minY);
                if (h >= 0 && h <= mob.maxUpStep() && h != clipped.y) heights.add(h);
            }
            for (double height : heights) {
                Vec3 step = Entity.collideBoundingBox(mob, new Vec3(wanted.x, height, wanted.z), base, level, shapes);
                if (step.horizontalDistanceSqr() > clipped.horizontalDistanceSqr()) {
                    clipped = step.add(0, -(bounds.minY - base.minY), 0); break;
                }
            }
        }
        return new Motion(clipped, wanted.y != clipped.y && wanted.y < 0,
            Math.abs(wanted.x - clipped.x) > EPS || Math.abs(wanted.z - clipped.z) > EPS);
    }

    private void travel() {
        if (!mob.isAlive() || mob.isRemoved()) return;
        AABB sweep = mob.getBoundingBox().expandTowards(mob.getDeltaMovement()).inflate(0.4, mob.maxUpStep() + 0.1, 0.4);
        checkVolume(sweep);
        if (box.get() != null) {
            BlockPos support = mob.getBlockPosBelowThatAffectsMyMovement();
            float friction = level.getBlockState(support).getFriction(level, support, mob);
            double acceleration = mob.onGround() ? mob.getSpeed() * (0.21600002F / (friction * friction * friction)) : 0.02F;
            if (mob.isInWater()) acceleration = Math.max(0.02, mob.getSpeed()) * Math.max(1, mob.getAttributeValue(NeoForgeMod.SWIM_SPEED));
            acceleration *= Math.min(1, Math.hypot(mob.xxa, mob.zza));
            if (!Double.isFinite(acceleration)) throw error("route_unsupported_physics");
            for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
                Vec3 possible = mob.getDeltaMovement().add(x * acceleration, 0, z * acceleration);
                Motion clipped = collide(mob.getBoundingBox(), possible, mob.onGround());
                if (!inside(mob.position().add(clipped.delta))) throw error("route_leaves_body_box");
            }
        }
        mob.setNoAi(false);
        try { mob.travel(new Vec3(mob.xxa, mob.yya, mob.zza)); }
        finally { mob.setNoAi(true); mob.setJumping(false); }
        if (!inside(mob.position())) throw error("route_leaves_body_box");
    }

    private void clearInputs() {
        mob.getNavigation().stop();
        mob.setXxa(0); mob.setYya(0); mob.setSpeed(0);
        mob.getJumpControl().tick(); mob.setJumping(false);
    }

    private void clearControls() {
        clearInputs(); mob.setSprinting(false);
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
    }

    private boolean settled() { return mob.onGround() || mob.isInWater() || mob.onClimbable(); }
    private boolean arrived(Vec3 goal) {
        return goal.subtract(mob.position()).horizontalDistance() < 0.12 && Math.abs(goal.y - mob.getY()) < 0.12 && settled();
    }

    private BlockPos logicalPosition(Vec3 position) {
        BlockPos cell = BlockPos.containing(position);
        // Fractional supports represent the cell above their occupied shape;
        // swimming/climbing feet use their actual integer cell.
        if (mob.onGround() && position.y - cell.getY() > 0.001
            && !level.getBlockState(cell).getCollisionShape(level, cell, CollisionContext.of(mob)).isEmpty())
            return cell.above();
        return cell;
    }

    private boolean arrivedAtNode(Vec3 goal) {
        BlockPos node = integerPosition(nodes.get(index));
        if (!settled() || !logicalPosition(mob.position()).equals(node)
            || goal.subtract(mob.position()).horizontalDistance() >= 0.12) return false;
        // Only water accepts the whole cell. A ladder may otherwise finish
        // almost a block above its target while still sliding down toward it.
        return level.getFluidState(node).is(FluidTags.WATER) || Math.abs(goal.y - mob.getY()) < 0.12;
    }

    private Vec3 destination(BlockPos node) {
        Vec3 center = Vec3.atBottomCenterOf(node);
        if (waterOrClimb(node)) return center;
        double y = node.getY() - 1;
        boolean supported = false;
        AABB footprint = mob.getBoundingBox().move(center.subtract(mob.position()));
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(footprint.minX, node.getY() - 1, footprint.minZ),
            BlockPos.containing(footprint.maxX - EPS, node.getY(), footprint.maxZ - EPS))) {
            checkCell(pos);
            for (AABB local : level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(mob)).toAabbs()) {
                AABB shape = local.move(pos);
                if (shape.maxX > footprint.minX + EPS && shape.minX < footprint.maxX - EPS
                    && shape.maxZ > footprint.minZ + EPS && shape.minZ < footprint.maxZ - EPS
                    && shape.maxY <= node.getY() + 0.5 + EPS && shape.maxY >= node.getY() - 1 - EPS) {
                    y = Math.max(y, shape.maxY); supported = true;
                }
            }
        }
        if (!supported) throw error("route_landing_has_no_support");
        return new Vec3(center.x, y, center.z);
    }

    private boolean inside(Vec3 position) {
        BodyBox confined = box.get();
        return confined == null || confined.dimension().equals(level.dimension().location().toString()) && confined.holds(position);
    }

    private boolean withinEdge(Vec3 position) {
        return relevant != null && relevant.inflate(0.4).contains(position);
    }

    private void checkVolume(AABB volume) {
        if ((volume.maxX - volume.minX + 2) * (volume.maxY - volume.minY + 2) * (volume.maxZ - volume.minZ + 2) > 8192)
            throw error("route_sweep_limit");
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(volume.minX, volume.minY, volume.minZ),
            BlockPos.containing(volume.maxX - EPS, volume.maxY - EPS, volume.maxZ - EPS))) checkCell(pos);
    }

    private void checkCell(BlockPos pos) {
        int offset = cacheIndex(pos);
        if (expected[offset] < 0 || !level.hasChunkAt(pos) || !level.isPositionEntityTicking(pos) || !level.getWorldBorder().isWithinBounds(pos))
            throw error("route_cell_unavailable");
        if (Block.getId(level.getBlockState(pos)) != expected[offset]) throw error("route_world_changed");
    }

    private boolean known(BlockPos pos) {
        try { checkCell(pos); return true; } catch (IllegalStateException failure) { return false; }
    }

    private int cacheIndex(BlockPos pos) {
        int x = pos.getX() - cacheMin.getX(), y = pos.getY() - cacheMin.getY(), z = pos.getZ() - cacheMin.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) throw error("route_outside_snapshot");
        return (y * sz + z) * sx + x;
    }

    private BlockHitResult hit(BlockPos pos, Direction face) {
        for (AABB shape : level.getBlockState(pos).getShape(level, pos, CollisionContext.of(mob)).toAabbs()) {
            Vec3 point = shape.getCenter();
            point = switch (face) {
                case WEST -> new Vec3(shape.minX + 0.0001, point.y, point.z);
                case EAST -> new Vec3(shape.maxX - 0.0001, point.y, point.z);
                case DOWN -> new Vec3(point.x, shape.minY + 0.0001, point.z);
                case UP -> new Vec3(point.x, shape.maxY - 0.0001, point.z);
                case NORTH -> new Vec3(point.x, point.y, shape.minZ + 0.0001);
                case SOUTH -> new Vec3(point.x, point.y, shape.maxZ - 0.0001);
            };
            BlockHitResult hit = level.clip(new ClipContext(mob.getEyePosition(), point.add(Vec3.atLowerCornerOf(pos)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mob));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && hit.getDirection() == face) return hit;
        }
        throw error("route_placement_out_of_reach");
    }

    static JsonObject capabilities(Mob mob) {
        boolean supported = ordinaryPhysics(mob);
        double gravity = supported ? mob.getGravity() : 0;
        double power = supported ? jumpPower(mob, mob.position()) : 0;
        double rise = supported && !mob.hasEffect(MobEffects.LEVITATION) ? jumpRise(power, gravity) : 0;
        double speed = supported ? mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1) : 0;
        double velocity = mob.getDeltaMovement().horizontalDistance();
        double width = mob.getBbWidth();
        JsonObject result = new JsonObject();
        result.addProperty("stepHeight", Math.max(0, mob.maxUpStep()));
        result.addProperty("jumpHeight", rise);
        result.addProperty("canJump", supported && rise > 0);
        result.addProperty("canSwim", supported && mob.canSwimInFluidType(NeoForgeMod.WATER_TYPE.value()));
        result.addProperty("maxJumpDistance", rise > 0 ? reachEnvelope(speed, velocity, power, gravity, width, false) : 0);
        result.addProperty("maxSprintJumpDistance", rise > 0 ? reachEnvelope(speed, velocity, power, gravity, width, true) : 0);
        result.addProperty("physics", supported ? "native-ground-post-tick" : "unsupported");
        return result;
    }

    private static boolean ordinaryPhysics(Mob mob) {
        return mob.getMoveControl().getClass() == MoveControl.class && !mob.isPassenger() && !mob.isNoGravity()
            && !mob.isFallFlying() && !mob.shouldDiscardFriction() && ORDINARY_PHYSICS.get(mob.getClass());
    }

    private static boolean inherits(Class<?> type, String method) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { current.getDeclaredMethod(method); return current == LivingEntity.class; }
            catch (NoSuchMethodException ignored) { }
        }
        return false;
    }

    private static double jumpPower(Mob mob, Vec3 position) {
        return jumpPower(mob, position, mob.getBlockPosBelowThatAffectsMyMovement());
    }

    private static double jumpPower(Mob mob, Vec3 position, BlockPos support) {
        BlockState feet = mob.level().getBlockState(BlockPos.containing(position));
        float factor = feet.getBlock().getJumpFactor();
        if (factor == 1) factor = mob.level().getBlockState(support).getBlock().getJumpFactor();
        return (float) mob.getAttributeValue(Attributes.JUMP_STRENGTH) * factor + mob.getJumpBoostPower();
    }

    private static double jumpRise(Mob mob) {
        return mob.hasEffect(MobEffects.LEVITATION) ? 0 : jumpRise(jumpPower(mob, mob.position()), mob.getGravity());
    }

    private static double jumpRise(double power, double gravity) {
        if (gravity <= 0) return 0;
        double velocity = power, height = 0;
        for (int i = 0; i < 100 && velocity > 0; i++) {
            height += velocity;
            velocity = (velocity - gravity) * 0.98F * 0.98;
        }
        return Double.isFinite(height) ? Math.min(16, height) : 0;
    }

    /** Candidate envelope, not a promise: includes a bounded takeoff approach and body overlap. */
    private static double reachEnvelope(double speed, double initialVelocity, double power, double gravity, double width, boolean sprint) {
        speed *= sprint ? 1.3 : 1;
        // Native forward input equals speed. For supported friction [.6, 1], use
        // the largest ground acceleration and drag as a conservative 12-tick run-up bound.
        double acceleration = speed * Math.min(1, speed) * 1.001;
        double horizontal = initialVelocity;
        for (int i = 0; i < 12; i++) horizontal = (horizontal * 0.98 + acceleration) * 0.91;
        horizontal = horizontal * 0.98 + acceleration + (sprint ? 0.2 : 0);
        double vertical = power, height = 0, distance = 0;
        for (int i = 0; i < 100; i++) {
            distance += horizontal; height += vertical;
            if (height < -1) break;
            horizontal = (horizontal * 0.91F * 0.98) + 0.02F * Math.min(1, speed);
            vertical = (vertical - gravity) * 0.98F * 0.98;
        }
        return Math.min(4, distance + 1 + width);
    }

    private static Vec3 small(Vec3 value) { return new Vec3(Math.abs(value.x) < 0.003 ? 0 : value.x, Math.abs(value.y) < 0.003 ? 0 : value.y, Math.abs(value.z) < 0.003 ? 0 : value.z); }
    private static boolean flag(JsonObject object, String key) { return object.has(key) && object.get(key).getAsBoolean(); }
    private static int slot(JsonObject request, String name, int fallback) {
        int value = request.has(name) ? integer(request.get(name)) : fallback;
        if (value < 0 || value > 35 && value != 40) throw error("route_invalid_inventory_slot");
        return value;
    }
    private static int integer(JsonElement value) {
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number) || Math.abs(number) > 30_000_000) throw error("route_invalid_integer");
        return (int) number;
    }
    private static BlockPos integerPosition(JsonObject value) { return new BlockPos(integer(value.get("x")), integer(value.get("y")), integer(value.get("z"))); }
    private static BlockPos arrayPosition(JsonArray value) {
        if (value == null || value.size() != 3) throw error("route_invalid_coordinates");
        return new BlockPos(integer(value.get(0)), integer(value.get(1)), integer(value.get(2)));
    }
    private static Vec3 vector(JsonObject value) {
        if (value == null) throw error("route_start_required");
        double x = value.get("x").getAsDouble(), y = value.get("y").getAsDouble(), z = value.get("z").getAsDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw error("route_invalid_coordinates");
        return new Vec3(x, y, z);
    }
    private static Direction face(JsonObject value) {
        int x = integer(value.get("dx")), y = integer(value.get("dy")), z = integer(value.get("dz"));
        if (Math.abs(x) + Math.abs(y) + Math.abs(z) != 1) throw error("route_invalid_placement_face");
        return Direction.fromDelta(x, y, z);
    }
    private static JsonObject xyz(Vec3 value) {
        JsonObject result = new JsonObject(); result.addProperty("x", value.x); result.addProperty("y", value.y); result.addProperty("z", value.z); return result;
    }
    private void requireThread() { if (!level.getServer().isSameThread()) throw error("route_requires_server_thread"); }
    private static IllegalStateException error(String name) { return new IllegalStateException(name); }
}
