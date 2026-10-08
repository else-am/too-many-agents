package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Salmon;
import net.minecraft.world.entity.animal.TropicalFish;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Executes supplied edges; never asks the native navigator to find a route. Server thread only. */
public final class ScriptNavigation {
    private static final Map<Entity, ScriptNavigation> OWNED = new IdentityHashMap<>();
    private enum Physics { UNSUPPORTED, ORDINARY, FOX, DROWNED, FISH, SLIME, MAGMA }
    private static final int MAX_NODES = 128, MAX_EDITS = 128, MAX_TICKS = 2400, EDGE_TICKS = 240;
    // Native MagmaCube waits up to 116 grounded command ticks per hop.
    private static final int MAGMA_EDGE_TICKS = 720, MAGMA_MAX_DELAY = 116, HOP_FLIGHT_TICKS = 100;
    private static final double EPS = 1e-6;
    // Method ownership is immutable for a loaded class; body state is not.
    private static final ClassValue<Boolean> GROUND_METHODS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("travel", Vec3.class).getDeclaringClass() == (type == Drowned.class ? Drowned.class : LivingEntity.class)
                    && type.getMethod("jumpFromGround").getDeclaringClass() == (type == Slime.class || type == MagmaCube.class ? type : LivingEntity.class)
                    && inherits(type, "getJumpPower") && inherits(type, "getFlyingSpeed")
                    && inherits(type, "isAffectedByFluids");
            } catch (ReflectiveOperationException failure) { return false; }
        }
    };
    private final AgentActions owner;
    private final String lease;
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
    private JsonObject direct;
    private BlockPos mining;
    private int miningBefore;
    private boolean active, completed, stopRequested, stopped, sprintAllowed, edgeStarted, tower, waterTravel;
    private String phase = "idle";
    private Physics physics;
    private MoveControl controller;
    private RuntimeException movementFailure;
    private boolean fishControlTick, fishHadTarget;
    private Vec3 swimHold;
    private int slimeSize;

    private record Frame(Vec3 before, Vec3 after, Vec3 beforeVelocity, boolean beforeGround, Vec3 target,
                         boolean jump, boolean sprint, float wantedYaw, double modifier) {
        Frame(Vec3 before, Vec3 after, Vec3 beforeVelocity, boolean beforeGround, Vec3 target, boolean jump, boolean sprint) {
            this(before, after, beforeVelocity, beforeGround, target, jump, sprint, 0, 1);
        }
    }
    private record HopInput(float yaw, double modifier) {}
    private record SwimStep(Vec3 wanted, double modifier, float speed, float yaw, Vec3 after, Vec3 velocity) {}
    private record Motion(Vec3 delta, boolean ground, boolean wall) {}

    ScriptNavigation(AgentActions owner, Supplier<BodyBox> box) {
        this.owner = owner;
        this.lease = owner.currentScriptId();
        this.mob = owner.mob;
        this.hands = owner.hands;
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
        mining = null; returnTo = null; direct = null; lastTick = -1;
        physics = physics(mob);
        if (physics == Physics.UNSUPPORTED) throw error("route_unsupported_physics");
        controller = mob.getMoveControl();
        slimeSize = mob instanceof Slime slime ? slime.getSize() : 0;
        fishHadTarget = mob.getTarget() != null;
        swimHold = mob.position();
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
            if (physics == Physics.FISH && (node.has("direct") || parkour || Math.abs(dy) > 1))
                throw error("route_unsupported_aquatic_edge");
            if (slimeHopper(physics) && node.has("direct")) throw error("route_unsupported_hopping_direct");
            if (node.has("direct")) {
                JsonObject segment = node.getAsJsonObject("direct");
                Vec3 from = vector(segment.getAsJsonObject("from")), to = vector(segment);
                double min = segment.get("minY").getAsDouble(), max = segment.get("maxY").getAsDouble();
                if (from.distanceTo(to) > 8.1 || Math.abs(from.y - to.y) > 1 || dx > 9 || dz > 9 || Math.abs(dy) > 2
                    || !Double.isFinite(min) || !Double.isFinite(max) || min > Math.min(from.y, to.y)
                    || max < Math.max(from.y, to.y) || min < Math.min(from.y, to.y) - 0.2
                    || max > Math.max(from.y, to.y) + 4.5
                    || Math.abs(to.x - pos.getX() - 0.5) > 0.5 || Math.abs(to.z - pos.getZ() - 0.5) > 0.5
                    || to.y < pos.getY() - 1 || to.y > pos.getY() + EPS)
                    throw error("route_invalid_direct_segment");
            } else if (dx > (parkour ? 4 : 1) || dz > (parkour ? 4 : 1) || dy > 1 || dy < -16
                || parkour && dx != 0 && dz != 0) throw error("route_discontinuous_edge");
            for (String key : List.of("toBreak", "toPlace")) {
                if (!node.has(key)) node.add(key, new JsonArray());
                if (node.has("direct") && !node.getAsJsonArray(key).isEmpty()) throw error("route_direct_edits");
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
        if (!owner.vehicleLeaseActive(lease)) throw error("script_no_longer_controls_body");
        if (OWNED.containsKey(mob)) throw error("route_already_running");
        relevant = mob.getBoundingBox().inflate(0.4, 1, 0.4);
        checkVolume(relevant);
        checkDryVolume(mob.getBoundingBox());
        if (physics == Physics.FISH) requireSubmerged(mob.getBoundingBox());
        clearControls();
        movementFailure = null;
        active = true;
        OWNED.put(mob, this);
        phase = "starting";
    }

    void requestStop() { requireThread(); stopRequested = true; }

    JsonObject tick() {
        requireThread();
        try {
            if (completed) return progress();
            if (movementFailure != null) throw movementFailure;
            if (!active) throw error("route_not_running");
            int now = level.getServer().getTickCount();
            if (lastTick == now) throw error("route_duplicate_tick");
            lastTick = now;
            if (++ticks > MAX_TICKS) throw error("route_timeout");
            if (mob.isRemoved() || !mob.isAlive() || mob.level() != level) throw error("route_body_changed");
            requireMode();
            if (!inside(mob.position())) throw error("route_outside_body_box");
            clearInputs();
            if (!trajectory.isEmpty() && frameIndex == trajectory.size()) { trajectory.clear(); frameIndex = 0; clearControls(); }
            Frame predicted = null;
            SwimStep swimming = null;
            boolean checkArrival = false;
            if (index == nodes.size()) {
                if (settled()) complete();
                else { phase = "landing"; clearControls(); }
            } else if (stopRequested && !edgeStarted && settled()) {
                complete();
            } else {
                if (!edgeStarted) beginEdge();
                if (++edgeTicks > (physics == Physics.MAGMA ? MAGMA_EDGE_TICKS : EDGE_TICKS) && mining == null) throw error("route_edge_timeout");
                checkVolume(relevant);
                JsonObject node = nodes.get(index);
                if (mining != null) {
                    phase = "mining";
                    JsonObject result = hands.tickMine();
                    if ("completed".equals(result.get("status").getAsString())) finishMining();
                } else if (frameIndex < trajectory.size()) {
                    predicted = followFrame();
                } else if (returnTo != null) {
                    if (physics == Physics.FISH) {
                        swimming = swimStep(returnTo);
                        if (arrived(returnTo)) { swimHold = returnTo; returnTo = null; }
                    } else {
                        Vec3 destination = returnTo;
                        // A hopper may need several native hops to reach an edit stance.
                        if (!slimeHopper(physics) || arrived(destination)) returnTo = null;
                        plan(destination, false);
                        if (!trajectory.isEmpty()) predicted = followFrame();
                    }
                } else if (breakIndex < node.getAsJsonArray("toBreak").size()) {
                    beginMining(node.getAsJsonArray("toBreak").get(breakIndex).getAsJsonObject());
                } else if (placeIndex < node.getAsJsonArray("toPlace").size()) {
                    place(node.getAsJsonArray("toPlace").get(placeIndex).getAsJsonObject());
                } else {
                    target = direct == null ? destination(integerPosition(node)) : vector(direct);
                    waterTravel |= mob.isInWater() || level.getFluidState(mob.blockPosition()).is(FluidTags.WATER);
                    if (physics == Physics.FISH) {
                        phase = "swimming";
                        swimming = swimStep(target);
                        checkArrival = true;
                    } else if (arrivedAtNode(target)) { clearControls(); checkArrival = true; }
                    else if (direct == null && (waterTravel || mob.onClimbable() || waterOrClimb(integerPosition(node)))) {
                        // A swim exit can briefly leave and re-enter water before
                        // landing. Keep native controls for the entire selected edge.
                        specialTravel(target);
                        // A ladder descent can move .15 in one tick. Accept its
                        // first post-travel arrival, not two ticks in a .12 band.
                        checkArrival = true;
                    } else {
                        phase = flag(node, "parkour") ? "parkour" : "moving";
                        try { plan(target, flag(node, "parkour") || direct != null && flag(direct, "jump")); }
                        catch (IllegalStateException failure) {
                            // This name is proof of no route travel or edit. Only this
                            // failure permits the guest to attempt ordinary AStar.
                            if (direct != null && index == 0 && ticks == 1 && edits.isEmpty()
                                && List.of("route_edge_unexecutable", "route_parkour_unexecutable", "route_prediction_budget")
                                    .contains(failure.getMessage())) throw error("route_direct_preflight_rejected");
                            throw failure;
                        }
                        if (!trajectory.isEmpty()) predicted = followFrame();
                    }
                }
            }
            // Fish hold the selected stance during edit waits through their own
            // native controller, not a synthetic neutral velocity.
            if (physics == Physics.FISH) {
                if (swimming == null) swimming = swimStep(swimHold);
                swimControl(swimming);
            }
            // Exactly one native travel on a successful tick, including mining and building waits.
            travel();
            if (swimming != null && (mob.position().distanceTo(swimming.after) > 0.01
                || mob.getDeltaMovement().distanceTo(swimming.velocity) > 0.01))
                throw error("route_aquatic_trajectory_changed");
            if (predicted != null && mob.position().distanceTo(predicted.after) > 0.075)
                throw error("route_trajectory_changed");
            if (predicted != null) frameIndex++;
            if (checkArrival && arrivedAtNode(target)) finishEdge();
            if (completed) OWNED.remove(mob, this);
            return progress();
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    void stop() {
        requireThread();
        OWNED.remove(mob, this);
        active = false;
        try { hands.cancelMine(); }
        finally {
            mining = null; trajectory.clear(); clearControls();
        }
        // Outer AgentActions owns subsequent passive gravity after cancellation.
    }

    private void beginEdge() {
        edgeStarted = true; edgeTicks = breakIndex = placeIndex = frameIndex = 0;
        trajectory.clear(); tower = waterTravel = false; returnTo = null;
        edgeStart = mob.position();
        swimHold = edgeStart;
        direct = nodes.get(index).has("direct") ? nodes.get(index).getAsJsonObject("direct") : null;
        Vec3 raw = direct == null ? Vec3.atBottomCenterOf(integerPosition(nodes.get(index))) : vector(direct);
        if (direct != null && edgeStart.distanceTo(vector(direct.getAsJsonObject("from"))) > 0.2)
            throw error("route_direct_start_changed");
        if (physics == Physics.FISH) raw = raw.add(0, swimTargetOffset(mob), 0);
        target = raw;
        double rise = physics == Physics.FISH ? 0 : physics == Physics.MAGMA ? jumpRise(mob) : Math.min(4, jumpRise(mob));
        relevant = mob.getBoundingBox().minmax(mob.getBoundingBox().move(raw.subtract(edgeStart)))
            .inflate(0.1, 0, 0.1).expandTowards(0, rise + 0.1, 0).expandTowards(0, -1, 0);
        if (direct != null) {
            Vec3 from = vector(direct.getAsJsonObject("from"));
            double half = mob.getBbWidth() / 2.0 + 0.3;
            relevant = new AABB(Math.min(from.x, raw.x) - half, direct.get("minY").getAsDouble() - 1,
                Math.min(from.z, raw.z) - half, Math.max(from.x, raw.x) + half,
                direct.get("maxY").getAsDouble() + mob.getBbHeight(), Math.max(from.z, raw.z) + half);
        }
        checkVolume(relevant);
        for (String key : List.of("toBreak", "toPlace")) for (JsonElement entry : nodes.get(index).getAsJsonArray(key)) {
            BlockPos pos = integerPosition(entry.getAsJsonObject());
            checkCell(pos);
            if (Vec3.atCenterOf(pos).distanceTo(edgeStart) > 6) throw error("route_edit_outside_edge");
        }
    }

    private void finishEdge() {
        index++; edgeStarted = false; trajectory.clear(); frameIndex = 0; direct = null;
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
            if (physics == Physics.FISH) throw error("route_aquatic_jump_unavailable");
            if (!tower) {
                if (!mob.onGround() || jumpRise(mob) < desired.getY() + 1 - mob.getY()) throw error("route_jump_unavailable");
                AABB clearance = mob.getBoundingBox().expandTowards(0, jumpRise(mob), 0);
                if (slimeHopper(physics)) {
                    checkVolume(clearance);
                    checkDryVolume(clearance);
                    Vec3 apex = mob.position().add(0, jumpRise(mob), 0);
                    if (!inside(apex) || !withinEdge(apex)) throw error("route_jump_outside_edge");
                }
                if (!level.noCollision(mob, clearance)) throw error("route_jump_obstructed");
                if (slimeHopper(physics)) {
                    tower = hopControl(mob.getYRot(), 0);
                    if (!tower) phase = "waiting_hop";
                } else { mob.setSprinting(false); mob.jumpFromGround(); tower = true; }
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
        if (edit.has("returnPos")) returnTo = Vec3.atBottomCenterOf(integerPosition(edit.getAsJsonObject("returnPos")))
            .add(0, physics == Physics.FISH ? swimTargetOffset(mob) : 0, 0);
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
        List<Frame> best = null;
        if (slimeHopper(physics)) {
            // Native bound bodies can have faster attributes than wild mobs.
            // Choose input from pure landing predictions before consuming any
            // real hop delay/RNG; an overshoot is not a completed selected edge.
            double closest = Double.POSITIVE_INFINITY;
            for (double scale : new double[]{1, 0.75, 0.5, 0.25, 0.125}) {
                List<Frame> candidate = simulate(goal, -1, false, scale);
                if (candidate == null || candidate.isEmpty()) continue;
                double distance = candidate.getLast().after.distanceToSqr(goal);
                if (distance < closest) { best = candidate; closest = distance; }
                if (closest < 0.01) break;
            }
        } else best = simulate(goal, -1, false);
        if (!slimeHopper(physics) && best == null && mob.onGround() && jumpRise(mob) > 0 && (direct == null || flag(direct, "jump"))) {
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
        if (slimeHopper(physics)) {
            boolean willJump = mob.onGround() && ((Slime.SlimeMoveControl) controller).jumpDelay <= 0 && mob.noJumpDelay == 0;
            if (willJump != frame.jump) throw error("route_hop_timing_changed");
            if (hopControl(frame.wantedYaw, frame.modifier) != frame.jump) throw error("route_hop_timing_changed");
            return frame;
        }
        steer(frame.target, frame.sprint);
        if (frame.jump) {
            if (!mob.onGround()) throw error("route_takeoff_not_grounded");
            mob.jumpFromGround();
        }
        return frame;
    }

    /** Consume only the native controller's real request; never write its delay or sample RNG. */
    private boolean hopControl(float yaw, double modifier) {
        requireMode();
        Slime.SlimeMoveControl control = (Slime.SlimeMoveControl) controller;
        boolean requested = mob.onGround() && control.jumpDelay <= 0;
        mob.setSprinting(false);
        control.setDirection(yaw, false);
        control.setWantedMovement(modifier);
        control.tick();
        mob.getJumpControl().tick();
        boolean jump = requested && mob.onGround() && mob.noJumpDelay == 0;
        if (jump) mob.jumpFromGround();
        // Ordinary NoAI aiStep owns cooldown reset/decrement between Post calls.
        // We do not execute a second aiStep or write its private cooldown.
        mob.setJumping(false);
        requireMode();
        return jump;
    }

    private static HopInput hopInput(Vec3 pos, Vec3 velocity, Vec3 goal, float yaw) {
        // Feedback chooses native direction/speed, never an entity velocity.
        Vec3 toward = goal.subtract(pos).multiply(1, 0, 1).subtract(velocity.multiply(5, 0, 5));
        float desired = toward.horizontalDistanceSqr() < 1e-8 ? yaw
            : (float) (Mth.atan2(toward.z, toward.x) * 180 / (float) Math.PI) - 90;
        return new HopInput(desired, Math.min(1, toward.horizontalDistance() * 4));
    }

    /** Select bounded native controller input, then predict its next actual travel.
     * Feedback gains choose a steering target; only the native equations below
     * determine motion. No simulated entity mutation or second path search. */
    private SwimStep swimStep(Vec3 goal) {
        requireMode();
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement(), difference = goal.subtract(pos);
        double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (!Double.isFinite(baseSpeed) || baseSpeed <= 0 || baseSpeed > 4) throw error("route_aquatic_speed_unsupported");
        Vec3 horizontal = new Vec3(difference.x * 0.015 - velocity.x * 0.4, 0, difference.z * 0.015 - velocity.z * 0.4);
        double vertical = difference.y * 0.04 - velocity.y;
        double desiredSpeed = Math.min(baseSpeed, Math.max(horizontal.length() / 0.01, Math.abs(vertical - 0.005) / 0.095));
        double modifier = Math.clamp((desiredSpeed - mob.getSpeed() * 0.875) / (baseSpeed * 0.125), 0, 1);
        float speed = Mth.lerp(0.125F, mob.getSpeed(), (float) (modifier * baseSpeed));
        Vec3 heading = horizontal.lengthSqr() < 1e-12
            ? new Vec3(-Mth.sin(mob.getYRot() * ((float) Math.PI / 180)), 0, Mth.cos(mob.getYRot() * ((float) Math.PI / 180)))
            : horizontal.normalize();
        double yDirection = speed == 0 ? 0 : Math.clamp((vertical - 0.005) / (speed * 0.1), -0.95, 0.95);
        Vec3 wanted = pos.add(heading.x, yDirection / Math.sqrt(1 - yDirection * yDirection), heading.z);
        Vec3 toward = wanted.subtract(pos);
        float yaw = mob.getYRot();
        // Vec3.normalize may return zero for a tiny horizontal correction.
        // FishMoveControl leaves yaw unchanged for a purely vertical target.
        if (toward.x != 0 || toward.z != 0) {
            float desiredYaw = (float) (Mth.atan2(toward.z, toward.x) * 180 / (float) Math.PI) - 90;
            yaw += Mth.clamp(Mth.wrapDegrees(desiredYaw - yaw), -90, 90);
            if (yaw < 0) yaw += 360; else if (yaw > 360) yaw -= 360;
        }
        // FishMoveControl: eye buoyancy, then speed-dependent vertical impulse.
        velocity = velocity.add(0, 0.005, 0);
        if (toward.y != 0) velocity = velocity.add(0, speed * (toward.y / toward.length()) * 0.1, 0);
        // AbstractFish.travel / Entity.moveRelative: native forward input is speed.
        double forward = speed * speed < 1e-7 ? 0 : Math.min(1, speed) * 0.01F;
        float radians = yaw * ((float) Math.PI / 180);
        velocity = velocity.add(-forward * Mth.sin(radians), 0, forward * Mth.cos(radians));
        Motion motion = collide(mob.getBoundingBox(), velocity, mob.onGround());
        Vec3 after = pos.add(motion.delta);
        if (!inside(after) || !withinEdge(after)) throw error("route_aquatic_edge_unexecutable");
        requireSubmerged(mob.getBoundingBox().expandTowards(motion.delta));
        // Ground-impact callbacks can bounce a fish; this mode does not invent
        // their vertical result. Select submerged edges away from impact instead.
        if (Math.abs(velocity.y - motion.delta.y) > EPS) throw error("route_aquatic_vertical_collision");
        Vec3 remaining = new Vec3(Mth.equal(velocity.x, motion.delta.x) ? velocity.x : 0, velocity.y,
            Mth.equal(velocity.z, motion.delta.z) ? velocity.z : 0).scale(0.9);
        if (!fishHadTarget) remaining = remaining.add(0, -0.005, 0);
        return new SwimStep(wanted, modifier, speed, yaw, after, remaining);
    }

    private void swimControl(SwimStep step) {
        requireMode();
        mob.setSprinting(false);
        controller.setWantedPosition(step.wanted.x, step.wanted.y, step.wanted.z, step.modifier);
        fishControlTick = true;
        try { controller.tick(); }
        finally { fishControlTick = false; }
        requireMode();
        if (Math.abs(mob.getSpeed() - step.speed) > 1e-5 || Math.abs(Mth.wrapDegrees(mob.getYRot() - step.yaw)) > 1e-3)
            throw error("route_fish_controller_unavailable");
    }

    private void steer(Vec3 goal, boolean sprint) {
        requireMode();
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
        return simulate(goal, takeoff, sprint, 1);
    }

    private List<Frame> simulate(Vec3 goal, int takeoff, boolean sprint, double hopScale) {
        if (mob.isInWater() || mob.isInLava() || mob.onClimbable() || mob.isNoGravity() || mob.shouldDiscardFriction()
            || mob.hasEffect(MobEffects.LEVITATION) || mob.hasEffect(MobEffects.SLOW_FALLING)) return null;
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement();
        AABB bounds = mob.getBoundingBox();
        boolean ground = mob.onGround();
        BlockPos supporting = mob.mainSupportingBlockPos.orElse(null);
        boolean groundWithoutBlock = ground && supporting == null;
        double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1);
        double speed = baseSpeed * (sprint ? 1.3 : 1);
        boolean hopper = slimeHopper(physics);
        int hopDelay = hopper ? ((Slime.SlimeMoveControl) controller).jumpDelay : 0;
        float yaw = mob.getYRot();
        boolean hopped = !ground;
        double initialDistance = pos.distanceTo(goal);
        List<Frame> frames = new ArrayList<>();
        int horizon = HOP_FLIGHT_TICKS;
        if (physics == Physics.MAGMA) {
            if (hopDelay > MAGMA_MAX_DELAY) throw error("route_hop_delay_unsupported");
            horizon += Math.max(0, hopDelay) + 1; // observed wait plus one full flight/landing check
        }
        for (int tick = 0; tick < horizon; tick++) {
            if (++predictionSteps > 2700 || System.nanoTime() > predictionDeadline) throw error("route_prediction_budget");
            if (tick > 0) velocity = small(velocity.scale(0.98)); // normal NoAI aiStep between Post calls
            Vec3 beforeVelocity = velocity;
            boolean beforeGround = ground;
            Vec3 difference = goal.subtract(pos);
            double distance = difference.horizontalDistance();
            if (distance < 0.12 && Math.abs(difference.y) < 0.12 && ground) return frames;
            // Stop at the first landing. Only the real controller may sample the
            // next grounded delay; replan from its observed landed state.
            if (hopper && hopped && ground)
                return pos.distanceTo(goal) < initialDistance - 0.02 ? frames : null;
            Vec3 direction = distance < 0.05 ? Vec3.ZERO : new Vec3(difference.x / distance, 0, difference.z / distance);
            BlockPos support = movementSupport(pos, supporting);
            if (!known(support) || !known(BlockPos.containing(pos))) return null;
            BlockState state = level.getBlockState(support);
            if (!level.getFluidState(BlockPos.containing(pos)).isEmpty()) return null;
            float friction = state.getFriction(level, support, mob);
            if (!(friction >= 0.6F && friction <= 1)) return null;
            double drag = ground ? friction * 0.91F : 0.91F;
            // DrownedMoveControl.tick adds this before super.tick/travel, but
            // steer does not call the controller inside its .05 horizontal band.
            if (physics == Physics.DROWNED && !ground && distance >= 0.05)
                velocity = velocity.add(0, -0.008, 0);
            boolean jump = tick == takeoff;
            HopInput hop = null;
            if (hopper) {
                HopInput wanted = hopInput(pos, velocity, goal, yaw);
                hop = new HopInput(wanted.yaw, wanted.modifier * hopScale);
                yaw += Mth.clamp(Mth.wrapDegrees(hop.yaw - yaw), -90, 90);
                if (yaw < 0) yaw += 360; else if (yaw > 360) yaw -= 360;
                speed = (float) (hop.modifier * baseSpeed);
                jump = false;
                if (ground) {
                    if (hopDelay-- <= 0) {
                        // Subsequent NoAI aiStep clears the inactive jump cooldown.
                        jump = tick > 0 || mob.noJumpDelay == 0;
                        if (!jump) return null;
                        hopped = true;
                    } else speed = 0;
                }
                float radians = yaw * ((float) Math.PI / 180);
                direction = new Vec3(-Mth.sin(radians), 0, Mth.cos(radians));
            }
            if (jump) {
                if (!ground) return null;
                double power = jumpPower(mob, pos, support);
                if (power <= 0 || physics == Physics.MAGMA && magmaHopRise(power, mob.getGravity()) <= 0) return null;
                velocity = new Vec3(velocity.x, power, velocity.z);
                if (sprint) velocity = velocity.add(direction.scale(0.2));
            }
            double acceleration = ground ? speed * (0.21600002F / (friction * friction * friction)) : 0.02F;
            velocity = velocity.add(direction.scale(acceleration * Math.min(1, speed)));
            Motion motion = collide(bounds, velocity, ground);
            Vec3 next = pos.add(motion.delta);
            if (!inside(next) || !withinEdge(next)) return null;
            if ((physics == Physics.DROWNED || hopper) && hasFluid(bounds.expandTowards(motion.delta))) return null;
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
            frames.add(hopper
                ? new Frame(pos, next, beforeVelocity, beforeGround, goal, jump, false, hop.yaw, hop.modifier)
                : new Frame(pos, next, beforeVelocity, beforeGround, goal, jump, sprint));
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
        requireMode();
        if (!mob.isAlive() || mob.isRemoved()) throw error("route_body_changed");
        AABB sweep = mob.getBoundingBox().expandTowards(mob.getDeltaMovement()).inflate(0.4, mob.maxUpStep() + 0.1, 0.4);
        checkVolume(sweep);
        if (physics != Physics.FISH && box.get() != null) {
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
        requireMode();
        if (!inside(mob.position())) throw error("route_leaves_body_box");
        if (direct != null && !withinEdge(mob.position())) throw error("route_leaves_direct_corridor");
    }

    private void clearInputs() {
        mob.getNavigation().stop();
        mob.setXxa(0); mob.setYya(0); mob.setZza(0);
        if (physics != Physics.FISH) mob.setSpeed(0);
        mob.getJumpControl().tick(); mob.setJumping(false);
    }

    private void clearControls() {
        clearInputs();
        if (physics == Physics.FISH) mob.setSpeed(0);
        mob.setSprinting(false);
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
    }

    private boolean settled() { return mob.onGround() || mob.isInWater() || mob.onClimbable(); }
    private boolean arrived(Vec3 goal) {
        return goal.subtract(mob.position()).horizontalDistance() < 0.12 && Math.abs(goal.y - mob.getY()) < 0.12 && settled()
            && (physics != Physics.FISH || mob.getDeltaMovement().length() <= 0.03);
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
        if (direct != null) return arrived(goal);
        BlockPos node = integerPosition(nodes.get(index));
        if (!settled() || !logicalPosition(mob.position()).equals(node)
            || goal.subtract(mob.position()).horizontalDistance() >= 0.12) return false;
        if (physics == Physics.FISH) return arrived(goal);
        // Only water accepts the whole cell. A ladder may otherwise finish
        // almost a block above its target while still sliding down toward it.
        return level.getFluidState(node).is(FluidTags.WATER) || Math.abs(goal.y - mob.getY()) < 0.12;
    }

    private Vec3 destination(BlockPos node) {
        Vec3 center = Vec3.atBottomCenterOf(node);
        if (physics == Physics.FISH) return center.add(0, swimTargetOffset(mob), 0);
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
        if (physics == Physics.FISH && edgeStart != null && target != null) {
            Vec3 edge = target.subtract(edgeStart);
            double t = edge.lengthSqr() == 0 ? 0 : Math.clamp(position.subtract(edgeStart).dot(edge) / edge.lengthSqr(), 0, 1);
            return position.distanceTo(edgeStart.add(edge.scale(t))) <= 0.3;
        }
        if (direct == null) return relevant != null && relevant.inflate(0.4).contains(position);
        Vec3 from = vector(direct.getAsJsonObject("from")), to = vector(direct);
        double dx = to.x - from.x, dz = to.z - from.z, length = dx * dx + dz * dz;
        double t = length == 0 ? 0 : Math.clamp(((position.x - from.x) * dx + (position.z - from.z) * dz) / length, 0, 1);
        return Math.hypot(position.x - from.x - t * dx, position.z - from.z - t * dz) <= 0.2
            && position.y >= direct.get("minY").getAsDouble() && position.y <= direct.get("maxY").getAsDouble();
    }

    private void checkVolumeSize(AABB volume) {
        if ((volume.maxX - volume.minX + 2) * (volume.maxY - volume.minY + 2) * (volume.maxZ - volume.minZ + 2) > 8192)
            throw error("route_sweep_limit");
    }

    private void checkVolume(AABB volume) {
        checkVolumeSize(volume);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(volume.minX, volume.minY, volume.minZ),
            BlockPos.containing(volume.maxX - EPS, volume.maxY - EPS, volume.maxZ - EPS))) checkCell(pos);
    }

    private int availableCell(BlockPos pos) {
        int offset = cacheIndex(pos);
        if (expected[offset] < 0 || !level.hasChunkAt(pos) || !level.isPositionEntityTicking(pos) || !level.getWorldBorder().isWithinBounds(pos))
            throw error("route_cell_unavailable");
        return offset;
    }

    private void checkCell(BlockPos pos) {
        int offset = availableCell(pos);
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
        Physics mode = physics(mob);
        boolean supported = mode != Physics.UNSUPPORTED;
        if (mode == Physics.FISH) {
            JsonObject result = new JsonObject();
            result.addProperty("physics", "native-fish-submerged-post-tick");
            result.addProperty("locomotion", "submerged");
            result.addProperty("swimTargetYOffset", swimTargetOffset(mob));
            result.addProperty("stepHeight", Math.max(0, mob.maxUpStep()));
            result.addProperty("canSwim", true);
            result.addProperty("canJump", false);
            result.addProperty("jumpHeight", 0);
            result.addProperty("maxJumpDistance", 0);
            result.addProperty("maxSprintJumpDistance", 0);
            return result;
        }
        double gravity = supported ? mob.getGravity() : 0;
        double power = supported ? jumpPower(mob, mob.position()) : 0;
        // Drowned's vertical-only tower does not tick its sinking controller.
        // Slime/MagmaCube towers retain native hop timing; horizontal edges model every tick.
        double rise = supported && !mob.hasEffect(MobEffects.LEVITATION)
            ? mode == Physics.MAGMA ? magmaHopRise(power, gravity) : jumpRise(power, gravity) : 0;
        double speed = supported ? mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1) : 0;
        double velocity = mob.getDeltaMovement().horizontalDistance();
        double width = mob.getBbWidth();
        JsonObject result = new JsonObject();
        result.addProperty("stepHeight", Math.max(0, mob.maxUpStep()));
        result.addProperty("jumpHeight", rise);
        result.addProperty("canJump", supported && rise > 0);
        result.addProperty("canSwim", supported && !slimeHopper(mode) && mode != Physics.DROWNED && mob.canSwimInFluidType(NeoForgeMod.WATER_TYPE.value()));
        if (slimeHopper(mode)) {
            double reach = rise > 0 ? hopEnvelope(speed, velocity, power, gravity, width) : 0;
            result.addProperty("locomotion", "hopping");
            result.addProperty("maxJumpDistance", reach);
            result.addProperty("maxSprintJumpDistance", reach);
            result.addProperty("physics", mode == Physics.MAGMA ? "native-magma-hop-post-tick" : "native-slime-hop-post-tick");
            return result;
        }
        result.addProperty("maxJumpDistance", rise > 0 ? reachEnvelope(speed, velocity, power, gravity, width, false, mode == Physics.DROWNED) : 0);
        result.addProperty("maxSprintJumpDistance", rise > 0 ? reachEnvelope(speed, velocity, power, gravity, width, true, mode == Physics.DROWNED) : 0);
        result.addProperty("physics", mode == Physics.DROWNED ? "native-drowned-dry-post-tick" : mode == Physics.FOX ? "native-fox-awake-post-tick" : supported ? "native-ground-post-tick" : "unsupported");
        return result;
    }

    private static Physics physics(Mob mob) {
        if (mob.isPassenger() || mob.isNoGravity() || mob.noPhysics || mob.isFallFlying()
            || mob.shouldDiscardFriction()) return Physics.UNSUPPORTED;
        Class<?> control = mob.getMoveControl().getClass();
        // These three concrete native classes share AbstractFish's controller,
        // travel/aiStep and WaterAnimal's no-current-push behavior.
        if ((mob.getClass() == Cod.class || mob.getClass() == Salmon.class || mob.getClass() == TropicalFish.class)
            && control == AbstractFish.FishMoveControl.class && mob.isInWater() && mob.isEyeInFluid(FluidTags.WATER))
            return Physics.FISH;
        if (!GROUND_METHODS.get(mob.getClass())) return Physics.UNSUPPORTED;
        if (control == MoveControl.class && !(mob instanceof Drowned)) return Physics.ORDINARY;
        if (mob.hasEffect(MobEffects.LEVITATION) || mob.hasEffect(MobEffects.SLOW_FALLING)) return Physics.UNSUPPORTED;
        if ((mob.getClass() == Slime.class || mob.getClass() == MagmaCube.class) && control == Slime.SlimeMoveControl.class
            && mob.getJumpControl().getClass() == JumpControl.class
            && !mob.isInWater() && !mob.isInLava() && !mob.onClimbable()) {
            if (mob.getClass() == Slime.class) return Physics.SLIME;
            return magmaHopRise(jumpPower(mob, mob.position()), mob.getGravity()) > 0 ? Physics.MAGMA : Physics.UNSUPPORTED;
        }
        if (mob.getClass() == Fox.class && control == Fox.FoxMoveControl.class) {
            Fox fox = (Fox) mob;
            // Exact Fox.canMove predicate; do not clear its native state flags.
            return !fox.isSleeping() && !fox.isSitting() && !fox.isFaceplanted() ? Physics.FOX : Physics.UNSUPPORTED;
        }
        if (mob.getClass() == Drowned.class && control == Drowned.DrownedMoveControl.class
            && !mob.isInWater() && !mob.isInLava()) return Physics.DROWNED;
        return Physics.UNSUPPORTED;
    }

    private void requireMode() {
        if (movementFailure != null) throw movementFailure;
        if (mob.isRemoved() || !mob.isAlive() || mob.level() != level) throw error("route_body_changed");
        if (physics(mob) != physics || mob.getMoveControl() != controller) throw error("route_physics_changed");
        if (slimeHopper(physics) && ((Slime) mob).getSize() != slimeSize) throw error("route_hop_size_changed");
        checkDryVolume(mob.getBoundingBox());
        if (physics == Physics.FISH) {
            if ((mob.getTarget() != null) != fishHadTarget) throw error("route_aquatic_target_changed");
            requireSubmerged(mob.getBoundingBox());
        }
    }

    private static double swimTargetOffset(Mob mob) { return Math.max(0.05, (1 - mob.getBbHeight()) / 2); }

    private AABB fishVolume(AABB bounds) {
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX,
            bounds.maxY + Math.max(0, mob.getEyeHeight() - mob.getBbHeight() + EPS), bounds.maxZ);
    }

    private void requireSubmerged(AABB bodyBounds) {
        AABB volume = fishVolume(bodyBounds);
        checkVolumeSize(volume);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(volume.minX, volume.minY, volume.minZ),
            BlockPos.containing(volume.maxX - EPS, volume.maxY - EPS, volume.maxZ - EPS))) {
            availableCell(pos);
            var state = level.getBlockState(pos);
            var fluid = state.getFluidState();
            if (!state.is(Blocks.WATER) || !fluid.is(FluidTags.WATER)) throw error("route_requires_submersion");
            // getHeight consults the fluid above, even at a partial surface.
            availableCell(pos.above());
            if (pos.getY() + fluid.getHeight(level, pos) + EPS < Math.min(volume.maxY, pos.getY() + 1))
                throw error("route_requires_submersion");
        }
        // Classify actual mode loss before checking revision mismatches, so a
        // newly exposed body cannot be retried as a generic changed-cell route.
        checkVolume(volume.expandTowards(0, 1, 0));
    }

    private void checkDryVolume(AABB bounds) {
        if ((physics == Physics.DROWNED || slimeHopper(physics)) && hasFluid(bounds))
            throw error(slimeHopper(physics) ? "route_hop_requires_dry_ground" : "route_drowned_requires_dry_ground");
    }

    private boolean hasFluid(AABB bounds) {
        checkVolumeSize(bounds);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
            BlockPos.containing(bounds.maxX - EPS, bounds.maxY - EPS, bounds.maxZ - EPS))) {
            availableCell(pos);
            // Detect the actual mode even before fluid flags refresh. A newly
            // flooded body is terminal, not a generic changed-cell replan.
            var fluid = level.getFluidState(pos);
            if (!fluid.isEmpty() && pos.getY() + fluid.getHeight(level, pos) > bounds.minY + EPS) return true;
        }
        return false;
    }

    /** Entity.move HEAD: bound queries before native collision resolution. No unowned entity changes. */
    public static boolean allowMove(Entity entity, Vec3 delta) {
        return guardedMove(entity, delta, false);
    }

    /** Entity.move after collide, before setPos: validate the actual native clipped displacement. */
    public static boolean allowClippedMove(Entity entity, Vec3 delta) {
        return guardedMove(entity, delta, true);
    }

    private static boolean guardedMove(Entity entity, Vec3 delta, boolean clipped) {
        if (entity.level().isClientSide) return true;
        ScriptNavigation route = OWNED.get(entity);
        if (route == null) return true;
        try {
            route.requireThread();
            // Expiry owns cleanup and removes this fence. Cancel this rejected
            // call only; later unowned passive physics remains native.
            if (!route.owner.vehicleLeaseActive(route.lease)) throw error("script_no_longer_controls_body");
            route.requireMode();
            if (!Double.isFinite(delta.x) || !Double.isFinite(delta.y) || !Double.isFinite(delta.z)
                || Math.max(Math.abs(delta.x), Math.max(Math.abs(delta.y), Math.abs(delta.z))) > 16)
                throw error("route_move_limit");
            AABB sweep = entity.getBoundingBox().expandTowards(delta);
            route.checkVolume(clipped ? sweep : sweep.expandTowards(0, route.mob.maxUpStep(), 0));
            if (clipped) {
                Vec3 next = entity.position().add(delta);
                if (!route.inside(next)) throw error("route_leaves_body_box");
                if (!route.withinEdge(next)) throw error("route_leaves_selected_edge");
                route.checkDryVolume(sweep);
                if (route.physics == Physics.FISH) route.requireSubmerged(sweep);
            }
            return true;
        } catch (RuntimeException failure) {
            route.rejectMovement(failure);
            return false;
        }
    }

    private void rejectMovement(RuntimeException failure) {
        // Keep the fence until terminal cleanup; subsequent moves must reject.
        if (movementFailure == null) movementFailure = failure;
        try { clearControls(); }
        catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    }

    /** Used only at FishMoveControl's isDone query, during our explicit tick. */
    public static boolean selectedFishControl(AbstractFish fish) {
        if (fish.level().isClientSide) return false;
        ScriptNavigation route = OWNED.get(fish);
        if (route == null || !route.fishControlTick || route.physics != Physics.FISH) return false;
        try {
            route.requireThread();
            if (!route.owner.vehicleLeaseActive(route.lease)) throw error("script_no_longer_controls_body");
            route.requireMode();
            return true;
        } catch (RuntimeException failure) { route.rejectMovement(failure); return false; }
    }

    private static boolean inherits(Class<?> type, String method) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { current.getDeclaredMethod(method); return current == LivingEntity.class; }
            catch (NoSuchMethodException ignored) { }
        }
        return false;
    }

    private static boolean slimeHopper(Physics mode) { return mode == Physics.SLIME || mode == Physics.MAGMA; }

    private static double jumpPower(Mob mob, Vec3 position) {
        return jumpPower(mob, position, mob.getBlockPosBelowThatAffectsMyMovement());
    }

    private static double jumpPower(Mob mob, Vec3 position, BlockPos support) {
        BlockState feet = mob.level().getBlockState(BlockPos.containing(position));
        float factor = feet.getBlock().getJumpFactor();
        if (factor == 1) factor = mob.level().getBlockState(support).getBlock().getJumpFactor();
        float power = (float) mob.getAttributeValue(Attributes.JUMP_STRENGTH) * factor + mob.getJumpBoostPower();
        // MagmaCube.jumpFromGround adds this after native getJumpPower, in float precision.
        if (mob.getClass() == MagmaCube.class) power += (float) ((MagmaCube) mob).getSize() * 0.1F;
        return power;
    }

    private static double jumpRise(Mob mob) {
        if (mob.hasEffect(MobEffects.LEVITATION)) return 0;
        double power = jumpPower(mob, mob.position());
        return mob.getClass() == MagmaCube.class ? magmaHopRise(power, mob.getGravity()) : jumpRise(power, mob.getGravity());
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

    /** Reject, rather than truncate, a MagmaCube hop beyond this executor's finite envelope. */
    private static double magmaHopRise(double power, double gravity) {
        if (!Double.isFinite(power) || !Double.isFinite(gravity) || power <= 0 || gravity <= 0) return 0;
        double velocity = power, height = 0, rise = 0;
        for (int tick = 0; tick < HOP_FLIGHT_TICKS; tick++) {
            height += velocity;
            rise = Math.max(rise, height);
            if (rise > 16) return 0;
            if (height < 0) return rise;
            velocity = (velocity - gravity) * 0.98F * 0.98;
        }
        return 0;
    }

    /** Reviewed Slime/MagmaCube have one ground acceleration, no running start or sprint impulse. */
    private static double hopEnvelope(double speed, double initialVelocity, double power, double gravity, double width) {
        double horizontal = initialVelocity + speed * Math.min(1, speed) * 1.001;
        double vertical = power, height = 0, distance = 0;
        for (int tick = 0; tick < 100; tick++) {
            distance += horizontal; height += vertical;
            if (height < -1) break;
            horizontal = horizontal * 0.91F * 0.98 + 0.02F * Math.min(1, speed);
            vertical = (vertical - gravity) * 0.98F * 0.98;
        }
        return Math.min(4, distance + 1 + width);
    }

    /** Candidate envelope, not a promise: includes a bounded takeoff approach and body overlap. */
    private static double reachEnvelope(double speed, double initialVelocity, double power, double gravity, double width, boolean sprint, boolean drowned) {
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
            if (drowned) vertical -= 0.008; // airborne controller before the next travel
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
