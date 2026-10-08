package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.animal.Pufferfish;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Dolphin;
import net.minecraft.world.entity.animal.Salmon;
import net.minecraft.world.entity.animal.TropicalFish;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.ElderGuardian;
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
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
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
    private enum Physics { UNSUPPORTED, ORDINARY, FOX, PANDA, CAMEL, SNIFFER, DROWNED, DROWNED_WATER, DROWNED_SWIM, FISH, TADPOLE, DOLPHIN, AXOLOTL, FROG, GUARDIAN, TURTLE, SLIME, MAGMA, RABBIT, PARROT, ALLAY, BEE, GHAST, PHANTOM, WITHER }
    private static final int MAX_NODES = 128, MAX_EDITS = 128, MAX_TICKS = 2400, EDGE_TICKS = 240;
    // Native MagmaCube waits up to 116 grounded command ticks per hop.
    private static final int MAGMA_EDGE_TICKS = 720, MAGMA_MAX_DELAY = 116, HOP_FLIGHT_TICKS = 100;
    private static final double EPS = 1e-6;
    // Method ownership is immutable for a loaded class; body state is not.
    private static final ClassValue<Boolean> GROUND_METHODS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("travel", Vec3.class).getDeclaringClass() == (type == Drowned.class ? Drowned.class : type == Camel.class ? Camel.class : LivingEntity.class)
                    && type.getMethod("jumpFromGround").getDeclaringClass() == (type == Slime.class || type == MagmaCube.class || type == Rabbit.class || type == Sniffer.class ? type : LivingEntity.class)
                    && (type == Rabbit.class || inherits(type, "getJumpPower")) && inherits(type, "getFlyingSpeed")
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
    private int puffState = -1;
    private float puffWidth, puffHeight, puffEyeHeight;
    private Pose puffPose;
    private Vec3 swimHold;
    private boolean smoothPrepared, smoothControlTick, smoothTravelTick;
    private boolean axolotlBaby, axolotlCanFloat, frogCanFloat;
    private PathNavigation smoothNavigation;
    private LivingEntity smoothTarget;
    private Pose smoothPose;
    private float smoothWidth, smoothHeight, smoothEyeHeight;
    private DryStep smoothStep;
    private PathNavigation guardianNavigation;
    private LookControl guardianLook;
    private LivingEntity guardianTarget;
    private Pose guardianPose;
    private float guardianWidth, guardianHeight, guardianEyeHeight;
    private boolean guardianCanFloat, guardianBeam, guardianMoving, guardianPrepared, guardianControlTick, guardianTravelTick;
    private DryStep guardianStep;
    private record GuardianCommand(boolean active, Vec3 wanted, double modifier, float yaw, float speed,
                                   Vec3 preparedVelocity, DryStep step) {}
    private PathNavigation drownedNavigation;
    private LivingEntity drownedTarget;
    private boolean drownedCanFloat, drownedPrepared, drownedTravelTick, drownedControlTick, drownedSearching;
    private Pose drownedPose;
    private float drownedWidth, drownedHeight, drownedEyeHeight;
    private Vec3 drownedHold;
    private AABB drownedPolicyVolume, drownedInitialVolume;
    private DryStep drownedStep;
    private PathNavigation turtleNavigation;
    private LivingEntity turtleTarget;
    private BlockPos turtleHome;
    private Pose turtlePose;
    private boolean turtleBaby, turtleGoingHome, turtleCanFloat, turtlePrepared, turtleControlTick, turtleTravelTick;
    private float turtleWidth, turtleHeight, turtleEyeHeight;
    private DryStep turtleStep;
    private Sniffer.State snifferState;
    private boolean snifferPrepared;
    private boolean groundBaby;
    private Pose groundPose;
    private PathNavigation groundNavigation;
    private float groundWidth, groundHeight, groundEyeHeight;
    private int slimeSize;
    private Rabbit.Variant rabbitVariant;
    private boolean rabbitBaby, rabbitPrepared;
    private float rabbitWidth, rabbitHeight;
    private RabbitStep rabbitStep;
    private DryStep flightStep;
    private Vec3 flightHold;
    private boolean flightPrepared, flightLanding;
    private float flightWidth, flightHeight, flightEyeHeight;
    private PathNavigation hoverNavigation;
    private Pose hoverPose;
    private boolean hoverTravelTick, beeBaby;
    private PathNavigation ghastNavigation;
    private LivingEntity ghastTarget;
    private Pose ghastPose;
    private boolean ghastCharging, ghastNoGravity, ghastCanFloat, ghastTravelTick;

    private PathNavigation phantomNavigation;
    private LivingEntity phantomTarget;
    private Vec3 phantomOriginalPoint, phantomInput;
    private Pose phantomPose;
    private int phantomSize;
    private boolean phantomNoGravity, phantomCanFloat, phantomControlTick, phantomTravelTick;

    private PathNavigation witherNavigation;
    private LivingEntity witherTarget;
    private final int[] witherHeadIds = new int[3];
    private final Entity[] witherHeadEntities = new Entity[3];
    private Pose witherPose;
    private int witherInvulnerableTicks;
    private boolean witherPowered, witherCanFloat, witherTravelTick;

    private record PhantomControl(Vec3 point, Vec3 velocity, float yaw, float pitch, float bodyYaw, float speed) {}
    private record PhantomCommand(PhantomControl control, FlyingMobStep step) {}
    private record Frame(Vec3 before, Vec3 after, Vec3 beforeVelocity, boolean beforeGround, Vec3 target,
                         boolean jump, boolean sprint, float wantedYaw, double modifier, SnifferControl sniffer) {
        Frame(Vec3 before, Vec3 after, Vec3 beforeVelocity, boolean beforeGround, Vec3 target, boolean jump, boolean sprint, float wantedYaw, double modifier) {
            this(before, after, beforeVelocity, beforeGround, target, jump, sprint, wantedYaw, modifier, null);
        }
        Frame(Vec3 before, Vec3 after, Vec3 beforeVelocity, boolean beforeGround, Vec3 target, boolean jump, boolean sprint) {
            this(before, after, beforeVelocity, beforeGround, target, jump, sprint, 0, 1);
        }
    }
    private record GhastCommand(Vec3 wanted, Vec3 velocity, FlyingMobStep step) {}
    private record FlyingMobStep(Vec3 after, Vec3 velocity, boolean ground) {}
    private record SnifferControl(MoveControl.Operation before, MoveControl.Operation after, boolean kick) {}
    private record TurtleCommand(boolean active, Vec3 wanted, double modifier, float yaw, float speed, Vec3 preparedVelocity, DryStep step) {}
    private record HopInput(float yaw, double modifier) {}
    private record SwimStep(Vec3 wanted, double modifier, float speed, float yaw, Vec3 after, Vec3 velocity) {}
    private record Motion(Vec3 delta, boolean ground, boolean wall) {}
    private record RabbitStep(Vec3 after, Vec3 velocity, boolean jump) {}
    private record DryStep(Vec3 after, Vec3 velocity) {}

    ScriptNavigation(AgentActions owner, Supplier<BodyBox> box) {
        this.owner = owner;
        this.lease = owner.currentScriptId();
        this.mob = owner.mob;
        this.hands = owner.hands;
        this.box = box;
        this.level = (ServerLevel) mob.level();
    }

    static void validateStartPosition(Mob mob, JsonObject request) {
        Vec3 start = vector(request.getAsJsonObject("start"));
        if (mob.position().distanceTo(start) > 0.2)
            throw ScriptRequestRejection.beforeStart("route_start_changed: requested=" + start + ", actual=" + mob.position());
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
        if (mob instanceof Rabbit rabbit) {
            rabbitVariant = rabbit.getVariant(); rabbitBaby = rabbit.isBaby();
            rabbitWidth = rabbit.getBbWidth(); rabbitHeight = rabbit.getBbHeight();
        }
        snifferPrepared = false;
        if (physics == Physics.SNIFFER) snifferState = ((Sniffer) mob).getState();
        if (groundWrapper(physics)) {
            groundBaby = mob.isBaby(); groundPose = mob.getPose(); groundNavigation = mob.getNavigation();
            groundWidth = mob.getBbWidth(); groundHeight = mob.getBbHeight(); groundEyeHeight = mob.getEyeHeight();
        }
        rabbitPrepared = false; rabbitStep = null;
        flightPrepared = flightLanding = false; flightStep = null; flightHold = mob.position();
        flightWidth = mob.getBbWidth(); flightHeight = mob.getBbHeight(); flightEyeHeight = mob.getEyeHeight();
        if (hoverMode(physics)) { hoverNavigation = mob.getNavigation(); hoverPose = mob.getPose(); }
        beeBaby = physics == Physics.BEE && mob.isBaby();
        if (physics == Physics.GHAST) {
            ghastNavigation = mob.getNavigation(); ghastTarget = mob.getTarget(); ghastPose = mob.getPose();
            ghastCharging = ((Ghast) mob).isCharging(); ghastNoGravity = mob.isNoGravity(); ghastCanFloat = ghastNavigation.canFloat();
        }
        if (physics == Physics.PHANTOM) {
            Phantom phantom = (Phantom) mob;
            phantomNavigation = mob.getNavigation(); phantomTarget = mob.getTarget(); phantomPose = mob.getPose();
            phantomOriginalPoint = phantom.moveTargetPoint; phantomSize = phantom.getPhantomSize();
            phantomNoGravity = mob.isNoGravity(); phantomCanFloat = phantomNavigation.canFloat();
            phantomInput = null; phantomControlTick = phantomTravelTick = false;
        }
        if (physics == Physics.WITHER) {
            WitherBoss wither = (WitherBoss) mob;
            witherNavigation = mob.getNavigation(); witherTarget = mob.getTarget(); witherPose = mob.getPose();
            witherInvulnerableTicks = wither.getInvulnerableTicks(); witherPowered = wither.isPowered();
            witherCanFloat = witherNavigation.canFloat(); witherTravelTick = false;
            for (int head = 0; head < 3; head++) {
                witherHeadIds[head] = wither.getAlternativeTarget(head);
                witherHeadEntities[head] = witherHeadIds[head] > 0 ? level.getEntity(witherHeadIds[head]) : null;
            }
        }
        fishHadTarget = mob.getTarget() != null;
        puffState = mob.getClass() == Pufferfish.class ? ((Pufferfish) mob).getPuffState() : -1;
        if (puffState >= 0) {
            puffWidth = mob.getBbWidth(); puffHeight = mob.getBbHeight(); puffEyeHeight = mob.getEyeHeight();
            puffPose = mob.getPose();
        }
        swimHold = mob.position();
        smoothPrepared = false; smoothStep = null;
        if (physics == Physics.AXOLOTL) { axolotlBaby = mob.isBaby(); axolotlCanFloat = mob.getNavigation().canFloat(); }
        if (physics == Physics.FROG) frogCanFloat = mob.getNavigation().canFloat();
        if (smoothSwimmer(physics)) {
            smoothNavigation = mob.getNavigation(); smoothTarget = mob.getTarget(); smoothPose = mob.getPose();
            smoothWidth = mob.getBbWidth(); smoothHeight = mob.getBbHeight(); smoothEyeHeight = mob.getEyeHeight();
        }
        turtlePrepared = false; turtleStep = null;
        if (physics == Physics.TURTLE) {
            Turtle turtle = (Turtle) mob;
            turtleNavigation = mob.getNavigation(); turtleTarget = mob.getTarget(); turtleHome = turtle.getHomePos().immutable();
            turtlePose = mob.getPose(); turtleBaby = mob.isBaby(); turtleGoingHome = turtle.isGoingHome();
            turtleCanFloat = turtleNavigation.canFloat();
            turtleWidth = mob.getBbWidth(); turtleHeight = mob.getBbHeight(); turtleEyeHeight = mob.getEyeHeight();
        }
        guardianPrepared = false; guardianStep = null;
        if (physics == Physics.GUARDIAN) {
            Guardian guardian = (Guardian) mob;
            guardianNavigation = mob.getNavigation(); guardianLook = mob.getLookControl(); guardianTarget = mob.getTarget();
            guardianPose = mob.getPose(); guardianWidth = mob.getBbWidth(); guardianHeight = mob.getBbHeight(); guardianEyeHeight = mob.getEyeHeight();
            guardianCanFloat = guardianNavigation.canFloat(); guardianBeam = guardian.hasActiveAttackTarget(); guardianMoving = guardian.isMoving();
        }
        drownedPrepared = false; drownedStep = null; drownedHold = mob.position();
        drownedPolicyVolume = drownedInitialVolume = null;
        if (drownedWaterMode(physics)) {
            drownedSearching = physics == Physics.DROWNED_SWIM && ((Drowned) mob).searchingForLand;
            drownedNavigation = mob.getNavigation(); drownedCanFloat = drownedNavigation.canFloat();
            drownedTarget = mob.getTarget(); drownedPose = mob.getPose();
            drownedWidth = mob.getBbWidth(); drownedHeight = mob.getBbHeight(); drownedEyeHeight = mob.getEyeHeight();
        }
        if (!inside(mob.position())) throw error("route_outside_body_box");
        Vec3 start = vector(request.getAsJsonObject("start"));
        if (mob.position().distanceTo(start) > 0.2)
            throw error("route_start_changed: requested=" + start + ", actual=" + mob.position());
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
            if ((fishMode(physics) || physics == Physics.DROWNED_WATER) && (node.has("direct") || parkour || Math.abs(dy) > 1))
                throw error("route_unsupported_aquatic_edge");
            if (flightMode(physics) && (node.has("direct") || parkour || Math.abs(dy) > 1))
                throw error("route_unsupported_flying_edge");
            if (physics == Physics.SNIFFER && node.has("direct")) throw error("route_unsupported_sniffer_direct");
            if ((slimeHopper(physics) || physics == Physics.RABBIT) && node.has("direct")) throw error("route_unsupported_hopping_direct");
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
        if (fishMode(physics) || physics == Physics.DROWNED_WATER) requireSubmerged(mob.getBoundingBox());
        if (physics == Physics.PHANTOM) { requireMode(); clearPhantomControls(); }
        else if (physics == Physics.GHAST) { requireMode(); clearGhastControls(); }
        else if (physics == Physics.TURTLE) { requireMode(); clearInputs(); }
        else clearControls();
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
            rabbitStep = null; flightStep = null; drownedStep = null; smoothStep = null; guardianStep = null; turtleStep = null;
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
                    } else if (smoothSwimmer(physics)) {
                        prepareSmoothSwim(returnTo);
                        if (arrived(returnTo)) { swimHold = returnTo; returnTo = null; }
                    } else if (physics == Physics.TURTLE) {
                        prepareTurtle(returnTo);
                        if (arrived(returnTo)) { swimHold = returnTo; returnTo = null; }
                    } else if (physics == Physics.GUARDIAN) {
                        prepareGuardian(returnTo);
                        if (arrived(returnTo)) { swimHold = returnTo; returnTo = null; }
                    } else if (physics == Physics.DROWNED_SWIM) {
                        prepareDrownedSwim(returnTo);
                        if (arrived(returnTo)) { drownedHold = returnTo; returnTo = null; }
                    } else if (physics == Physics.DROWNED_WATER) {
                        prepareDrownedWater(returnTo);
                        if (arrived(returnTo)) { drownedHold = returnTo; returnTo = null; }
                    } else if (flightMode(physics)) {
                        if (arrived(returnTo)) { flightHold = returnTo; returnTo = null; }
                        else prepareFlight(returnTo, false);
                    } else if (physics == Physics.RABBIT) {
                        if (arrived(returnTo)) returnTo = null;
                        else prepareRabbit(returnTo, 0);
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
                    } else if (smoothSwimmer(physics)) {
                        phase = "swimming";
                        prepareSmoothSwim(target);
                        checkArrival = true;
                    } else if (physics == Physics.TURTLE) {
                        phase = "swimming";
                        prepareTurtle(target);
                        checkArrival = true;
                    } else if (physics == Physics.GUARDIAN) {
                        phase = "swimming";
                        prepareGuardian(target);
                        checkArrival = true;
                    } else if (physics == Physics.DROWNED_SWIM) {
                        phase = "swimming";
                        prepareDrownedSwim(target);
                        checkArrival = true;
                    } else if (physics == Physics.DROWNED_WATER) {
                        phase = "swimming";
                        prepareDrownedWater(target);
                        checkArrival = true;
                    } else if (flightMode(physics)) {
                        phase = flightLanding ? "landing" : "flying";
                        prepareFlight(target, flightLanding);
                        checkArrival = true;
                    } else if (arrivedAtNode(target)) { clearControls(); checkArrival = true; }
                    else if (physics == Physics.RABBIT) {
                        phase = "hopping";
                        prepareRabbit(target, 0);
                        checkArrival = true;
                    } else if (direct == null && (waterTravel || mob.onClimbable() || waterOrClimb(integerPosition(node)))) {
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
            if (flightMode(physics) && active && flightStep == null) prepareFlight(flightHold, false);
            if (physics == Physics.DROWNED_WATER && drownedStep == null) {
                if (active) prepareDrownedWater(drownedHold);
                else drownedStep = predictDrownedWater(mob.getDeltaMovement());
            }
            if (physics == Physics.DROWNED_SWIM && drownedStep == null) {
                if (active) prepareDrownedSwim(drownedHold);
                else drownedStep = predictDrownedSwim();
            }
            if (nativeFlyingMob(physics) && flightStep == null) flightStep = predictFlyingMobStep(mob.getDeltaMovement());
            if (physics == Physics.ALLAY && flightStep == null) flightStep = predictAllayStep();
            if ((physics == Physics.BEE || physics == Physics.WITHER) && flightStep == null)
                flightStep = predictDryStep(mob.getDeltaMovement(), flightFriction());
            if (smoothSwimmer(physics) && smoothStep == null) {
                if (active) prepareSmoothSwim(swimHold);
                else smoothStep = smoothSwimTravelStep();
            }
            if (physics == Physics.TURTLE && turtleStep == null) {
                if (active) prepareTurtle(swimHold);
                else {
                    turtleStep = turtleTravelStep(mob.getDeltaMovement(), mob.getSpeed(), mob.getYRot());
                    if (turtleStep == null) throw error("route_turtle_edge_unexecutable");
                }
            }
            if (physics == Physics.GUARDIAN && guardianStep == null) {
                if (active) prepareGuardian(swimHold);
                else {
                    guardianStep = guardianTravelStep(mob.getDeltaMovement(), mob.getSpeed(), mob.getYRot(), ((Guardian) mob).isMoving());
                    if (guardianStep == null) throw error("route_guardian_edge_unexecutable");
                }
            }
            // Exactly one native travel on a successful tick, including mining and building waits.
            travel();
            if (turtleStep != null && (mob.position().distanceTo(turtleStep.after) > 0.01
                || mob.getDeltaMovement().distanceTo(turtleStep.velocity) > 0.01)) throw error("route_turtle_trajectory_changed");
            if (guardianStep != null && (mob.position().distanceTo(guardianStep.after) > 0.01
                || mob.getDeltaMovement().distanceTo(guardianStep.velocity) > 0.01)) throw error("route_guardian_trajectory_changed");
            if (smoothStep != null && (mob.position().distanceTo(smoothStep.after) > 0.01
                || mob.getDeltaMovement().distanceTo(smoothStep.velocity) > 0.01))
                throw error(smoothReason("trajectory_changed"));
            if (drownedStep != null && (mob.position().distanceTo(drownedStep.after) > 0.01
                || mob.getDeltaMovement().distanceTo(drownedStep.velocity) > 0.01))
                throw error(physics == Physics.DROWNED_SWIM ? "route_drowned_swim_trajectory_changed" : "route_drowned_water_trajectory_changed");
            if (flightStep != null && (mob.position().distanceTo(flightStep.after) > 0.01
                || mob.getDeltaMovement().distanceTo(flightStep.velocity) > 0.01))
                throw error("route_flight_trajectory_changed");
            if (rabbitStep != null && (mob.position().distanceTo(rabbitStep.after) > 0.01
                || mob.getDeltaMovement().distanceTo(rabbitStep.velocity) > 0.01))
                throw error("route_rabbit_trajectory_changed");
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
            // Native timer/animation/RNG preparation is not a replay-safe preflight.
            if (physics == Physics.SNIFFER && snifferPrepared)
                throw new IllegalStateException("route_sniffer_prepared_failed: " + failure.getMessage(), failure);
            if (physics == Physics.RABBIT && rabbitPrepared)
                throw new IllegalStateException("route_rabbit_prepared_failed: " + failure.getMessage(), failure);
            if (flightMode(physics) && flightPrepared)
                throw new IllegalStateException("route_flight_prepared_failed: " + failure.getMessage(), failure);
            if (physics == Physics.TURTLE && turtlePrepared)
                throw new IllegalStateException("route_turtle_prepared_failed: " + failure.getMessage(), failure);
            if (physics == Physics.GUARDIAN && guardianPrepared)
                throw new IllegalStateException("route_guardian_prepared_failed: " + failure.getMessage(), failure);
            if (smoothSwimmer(physics) && smoothPrepared)
                throw new IllegalStateException(smoothReason("prepared_failed") + ": " + failure.getMessage(), failure);
            if (physics == Physics.DROWNED_SWIM && drownedPrepared)
                throw new IllegalStateException("route_drowned_swim_prepared_failed: " + failure.getMessage(), failure);
            if (physics == Physics.DROWNED_WATER && drownedPrepared)
                throw new IllegalStateException("route_drowned_water_prepared_failed: " + failure.getMessage(), failure);
            throw failure;
        }
    }

    void stop() {
        requireThread();
        boolean cleanup = (physics != Physics.WITHER || ownsWitherCleanup()) && (physics != Physics.PHANTOM || ownsPhantomCleanup()) && (physics != Physics.GHAST || ownsGhastCleanup()) && (!hoverMode(physics) || ownsHoverCleanup())
            && (!smoothSwimmer(physics) || ownsSmoothSwimCleanup())
            && (physics != Physics.TURTLE || ownsTurtleCleanup())
            && (physics != Physics.GUARDIAN || ownsGuardianCleanup())
            && (puffState < 0 || ownsPufferfishCleanup())
            && (!drownedWaterMode(physics) || ownsDrownedWaterCleanup())
            && (!groundWrapper(physics) || ownsGroundCleanup());
        phantomInput = null; phantomControlTick = false;
        OWNED.remove(mob, this);
        active = false;
        if (!cleanup) { mining = null; trajectory.clear(); return; }
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
        swimHold = edgeStart; drownedHold = edgeStart; flightHold = edgeStart; flightLanding = false;
        direct = nodes.get(index).has("direct") ? nodes.get(index).getAsJsonObject("direct") : null;
        Vec3 raw = direct == null ? Vec3.atBottomCenterOf(integerPosition(nodes.get(index))) : vector(direct);
        if (direct != null && edgeStart.distanceTo(vector(direct.getAsJsonObject("from"))) > 0.2)
            throw error("route_direct_start_changed");
        if (fishMode(physics) || flightMode(physics)) raw = raw.add(0, swimTargetOffset(mob), 0);
        if (physics == Physics.DROWNED_WATER) raw = raw.add(0, 0.5, 0);
        target = raw;
        if (physics == Physics.DROWNED_WATER) {
            drownedInitialVolume = fishVolume(mob.getBoundingBox());
            Vec3 source = Vec3.atBottomCenterOf(BlockPos.containing(edgeStart)).add(0, 0.5, 0);
            AABB atSource = drownedInitialVolume.move(source.subtract(edgeStart));
            drownedPolicyVolume = atSource.minmax(atSource.move(raw.subtract(source))).deflate(EPS);
        }
        double rise = fishMode(physics) || flightMode(physics) || physics == Physics.DROWNED_WATER ? 0 : physics == Physics.MAGMA || physics == Physics.RABBIT ? jumpRise(mob) : Math.min(4, jumpRise(mob));
        relevant = mob.getBoundingBox().minmax(mob.getBoundingBox().move(raw.subtract(edgeStart)))
            .inflate(0.1, 0, 0.1).expandTowards(0, rise + 0.1, 0).expandTowards(0, -1, 0);
        if (direct != null) {
            Vec3 from = vector(direct.getAsJsonObject("from"));
            double half = mob.getBbWidth() / 2.0 + 0.3;
            relevant = new AABB(Math.min(from.x, raw.x) - half, direct.get("minY").getAsDouble() - 1,
                Math.min(from.z, raw.z) - half, Math.max(from.x, raw.x) + half,
                direct.get("maxY").getAsDouble() + mob.getBbHeight(), Math.max(from.z, raw.z) + half);
        }
        if (flightMode(physics)) relevant = flightVolume(relevant);
        checkVolume(relevant);
        for (String key : List.of("toBreak", "toPlace")) for (JsonElement entry : nodes.get(index).getAsJsonArray(key)) {
            BlockPos pos = integerPosition(entry.getAsJsonObject());
            checkCell(pos);
            if (Vec3.atCenterOf(pos).distanceTo(edgeStart) > 6) throw error("route_edit_outside_edge");
        }
    }

    private void finishEdge() {
        index++; edgeStarted = false; trajectory.clear(); frameIndex = 0; direct = null;
        if (physics == Physics.TURTLE && !stopRequested && index < nodes.size()) clearInputs();
        else clearControls();
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
            if (fishMode(physics) || physics == Physics.DROWNED_WATER) throw error("route_aquatic_jump_unavailable");
            if (flightMode(physics)) throw error("route_flying_jump_placement_unavailable");
            if (!tower) {
                if (!mob.onGround() || jumpRise(mob) < desired.getY() + 1 - mob.getY()) throw error("route_jump_unavailable");
                AABB clearance = mob.getBoundingBox().expandTowards(0, jumpRise(mob), 0);
                if (slimeHopper(physics) || physics == Physics.RABBIT) {
                    checkVolume(clearance);
                    checkDryVolume(clearance);
                    Vec3 apex = mob.position().add(0, jumpRise(mob), 0);
                    if (!inside(apex) || !withinEdge(apex)) throw error("route_jump_outside_edge");
                }
                if (!level.noCollision(mob, clearance)) throw error("route_jump_obstructed");
                if (slimeHopper(physics)) {
                    tower = hopControl(mob.getYRot(), 0);
                    if (!tower) phase = "waiting_hop";
                } else if (physics == Physics.RABBIT) {
                    prepareRabbit(new Vec3(mob.getX(), desired.getY() + 1, mob.getZ()), desired.getY() + 1 - mob.getY());
                    tower = rabbitStep.jump;
                    if (!tower) phase = "waiting_hop";
                } else {
                    if (physics == Physics.SNIFFER) {
                        preflightSnifferTower(desired.getY() + 1);
                        snifferPrepared = true;
                    }
                    mob.setSprinting(false); mob.jumpFromGround(); tower = true;
                }
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
            .add(0, physics == Physics.DROWNED_WATER ? 0.5 : fishMode(physics) || flightMode(physics) ? swimTargetOffset(mob) : 0, 0);
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
        if (physics == Physics.SNIFFER && (frame.sniffer == null || controller.operation != frame.sniffer.before))
            throw error("route_sniffer_control_changed");
        steer(frame.target, frame.sprint);
        if (physics == Physics.SNIFFER && (controller.operation != frame.sniffer.after
            || Math.abs(Mth.wrapDegrees(mob.getYRot() - frame.wantedYaw)) > 1e-4
            || controller.getSpeedModifier() != frame.modifier)) throw error("route_sniffer_control_changed");
        if (frame.jump) {
            if (!mob.onGround()) throw error("route_takeoff_not_grounded");
            if (physics == Physics.SNIFFER) {
                Vec3 afterSuper = groundJumpVelocity(mob.getDeltaMovement(), jumpPower(mob, mob.position()), mob.getYRot(), frame.sprint);
                boolean kick = snifferKick(afterSuper, controller.getSpeedModifier());
                if (kick != frame.sniffer.kick) throw error("route_sniffer_jump_changed");
            }
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

    /** Only Rabbit's timing component and controls prepare inputs. No AI/navigation tick or movement. */
    private void prepareRabbit(Vec3 goal, double requiredRise) {
        requireRabbitLease();
        if (rabbitStep != null) throw error("route_duplicate_rabbit_control");
        Rabbit rabbit = (Rabbit) mob;
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement();
        Vec3 toward = goal.subtract(pos).multiply(1, 0, 1).subtract(velocity.multiply(5, 0, 5));
        BlockPos support = mob.getBlockPosBelowThatAffectsMyMovement();
        checkCell(support);
        float friction = level.getBlockState(support).getFriction(level, support, mob);
        if (!(friction >= 0.6F && friction <= 1)) throw error("route_rabbit_friction_unsupported");
        double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1);
        if (!Double.isFinite(baseSpeed) || baseSpeed <= 0) throw error("route_rabbit_speed_unsupported");
        // Attribute-normalized input avoids a saturated high-speed takeoff.
        // This is steering feedback, not proof of a later landing; the actual
        // post-controller jump/travel is preflighted below on every tick.
        double accelerationAtFull = baseSpeed * Math.min(1, baseSpeed) * (0.21600002F / (friction * friction * friction));
        double modifier = mob.onGround()
            ? Math.sqrt(toward.horizontalDistance() * mob.getGravity() / accelerationAtFull)
            : toward.horizontalDistance() * 0.5 / baseSpeed;
        modifier = Math.clamp(modifier, 0.001, 1);
        // Above .6 keeps native Rabbit's ordinary/high jump branches available.
        if (mob.onGround() && goal.y > pos.y + 0.5) modifier = Math.max(0.61, modifier);
        Vec3 wanted = new Vec3(pos.x + toward.x, goal.y, pos.z + toward.z);
        mob.setSprinting(false);
        // Mark before any native preparation: even a failed component can have
        // advanced native timers, carrot RNG, sounds or animation.
        rabbitPrepared = true;
        controller.setWantedPosition(wanted.x, wanted.y, wanted.z, modifier);
        rabbit.customServerAiStep();
        requireRabbitLease();
        controller.tick();
        mob.getJumpControl().tick();
        requireRabbitLease();
        if (!mob.position().equals(pos) || !mob.getDeltaMovement().equals(velocity))
            throw error("route_rabbit_preparation_moved");

        // MOVE_TO has been consumed by the real controller. Read native power
        // now, not from the earlier wanted Y or a guessed rabbit jump constant.
        boolean jump = mob.jumping && mob.onGround() && mob.noJumpDelay == 0;
        float power = jump ? rabbit.getJumpPower() : 0;
        if (jump && requiredRise > 0 && jumpRise(power, mob.getGravity()) + EPS < requiredRise)
            throw error("route_jump_unavailable");
        float radians = mob.getYRot() * ((float) Math.PI / 180);
        Vec3 forward = new Vec3(-Mth.sin(radians), 0, Mth.cos(radians));
        if (jump) {
            if (power > 1.0e-5F) velocity = new Vec3(velocity.x, power, velocity.z);
            if (controller.getSpeedModifier() > 0 && velocity.horizontalDistanceSqr() < 0.01)
                velocity = velocity.add(forward.scale(0.1F));
        }
        DryStep step = predictDryStep(velocity, friction);
        rabbitStep = new RabbitStep(step.after, step.velocity, jump);
        // Only the native method applies the impulse, after collision/loaded/
        // revision/box checks. Actual travel still happens once in tick().
        if (jump) rabbit.jumpFromGround();
        requireRabbitLease();
    }

    /** Native LivingEntity dry travel from already prepared inputs; no entity mutation. */
    private DryStep predictDryStep(Vec3 velocity, float friction) {
        boolean falling = velocity.y <= 0;
        Vec3 pos = mob.position();
        float radians = mob.getYRot() * ((float) Math.PI / 180);
        double drag = mob.onGround() ? friction * 0.91F : 0.91F;
        float acceleration = mob.onGround() ? mob.getSpeed() * (0.21600002F / (friction * friction * friction)) : 0.02F;
        Vec3 input = new Vec3(mob.xxa, mob.yya, mob.zza);
        if (input.lengthSqr() >= 1.0e-7) {
            if (input.lengthSqr() > 1) input = input.normalize();
            input = input.scale(acceleration);
            float sin = Mth.sin(radians), cos = Mth.cos(radians);
            velocity = velocity.add(input.x * cos - input.z * sin, input.y, input.z * cos + input.x * sin);
        }
        Motion motion = collide(mob.getBoundingBox(), velocity, mob.onGround());
        Vec3 after = pos.add(motion.delta);
        if (!inside(after) || !withinEdge(after)) throw error(flightMode(physics) ? "route_flight_edge_unexecutable" : "route_rabbit_edge_unexecutable");
        checkDryVolume(mob.getBoundingBox().expandTowards(motion.delta));
        Vec3 remaining = new Vec3(Mth.equal(velocity.x, motion.delta.x) ? velocity.x : 0,
            (physics == Physics.WITHER ? velocity.y != motion.delta.y : Math.abs(velocity.y - motion.delta.y) > EPS) ? 0 : velocity.y,
            Mth.equal(velocity.z, motion.delta.z) ? velocity.z : 0);
        AABB nextBounds = mob.getBoundingBox().move(motion.delta);
        BlockPos nextSupport = motion.ground ? supportingBlock(nextBounds, after) : null;
        if (motion.ground && nextSupport == null && mob.mainSupportingBlockPos.isPresent())
            nextSupport = supportingBlock(nextBounds.move(-motion.delta.x, 0, -motion.delta.z), after);
        if (physics == Physics.BEE || physics == Physics.WITHER) requireHoverImpact(nextSupport, after, velocity.y != motion.delta.y, motion.ground);
        BlockPos below = movementSupport(after, nextSupport), feet = BlockPos.containing(after);
        checkCell(feet); checkCell(below);
        float factor = level.getBlockState(feet).getBlock().getSpeedFactor();
        if (factor == 1) factor = level.getBlockState(below).getBlock().getSpeedFactor();
        if ((physics == Physics.BEE || physics == Physics.WITHER) && (!Float.isFinite(factor) || factor < 0 || factor > 4))
            throw error(physics == Physics.WITHER ? "route_wither_block_speed_unsupported" : "route_bee_block_speed_unsupported");
        remaining = remaining.multiply(factor * drag, 1, factor * drag);
        double gravity = mob.getGravity(), vertical = remaining.y;
        if ((physics == Physics.BEE || physics == Physics.WITHER) && mob.hasEffect(MobEffects.LEVITATION))
            vertical += (0.05 * (mob.getEffect(MobEffects.LEVITATION).getAmplifier() + 1) - vertical) * 0.2;
        else {
            if ((physics == Physics.BEE || physics == Physics.WITHER) && falling && mob.hasEffect(MobEffects.SLOW_FALLING)) gravity = Math.min(gravity, 0.01);
            vertical -= gravity;
        }
        // Native FlyingAnimal vertical motion uses the horizontal drag.
        remaining = new Vec3(remaining.x, vertical * (physics == Physics.PARROT || physics == Physics.BEE ? drag : 0.98F), remaining.z);
        return new DryStep(after, remaining);
    }

    /** Only this exact leased explicit controller tick substitutes the three native field reads. */
    public static Vec3 selectedPhantomPoint(Phantom body, MoveControl control) {
        if (body.getClass() != Phantom.class || body.level().isClientSide || body.level().getServer() == null
            || !body.level().getServer().isSameThread()) return body.moveTargetPoint;
        ScriptNavigation route = OWNED.get(body);
        if (route == null || route.physics != Physics.PHANTOM || !route.phantomControlTick
            || route.phantomInput == null || control != route.controller) return body.moveTargetPoint;
        route.requireThread();
        route.requireFlightLease();
        return route.phantomInput;
    }

    private void preparePhantom(Vec3 goal, boolean landing) {
        requireFlightLease();
        if (flightStep != null) throw error("route_duplicate_flight_control");
        Vec3 pos = mob.position(), toward = goal.subtract(pos), velocity = mob.getDeltaMovement();
        if (landing && toward.y <= 0.12) toward = new Vec3(toward.x, Math.min(toward.y, -0.001), toward.z);
        float speed = ((Phantom.PhantomMoveControl) controller).speed;
        if (!Double.isFinite(toward.lengthSqr()) || !Double.isFinite(velocity.lengthSqr())
            || !Float.isFinite(speed) || speed <= 0 || speed > 1.8F)
            throw error("route_phantom_input_invalid");
        PhantomCommand chosen = null;
        // The vertical candidate has nonzero original XZ, but the native .7F
        // transform suppresses its horizontal thrust. No velocity is supplied.
        Vec3 vertical = new Vec3(Math.abs(toward.y * 0.7F), toward.y, 0);
        for (Vec3 direction : new Vec3[]{Vec3.ZERO, toward, velocity.scale(-1),
            toward.subtract(velocity.scale(4)), new Vec3(toward.x, 0, toward.z), vertical}) {
            Vec3 point = pos.add(direction.normalize().scale(0.5));
            if (!Double.isFinite(point.lengthSqr()) || point.distanceTo(pos) > 0.5 + EPS)
                throw error("route_phantom_wanted_limit");
            PhantomControl prepared = phantomControl(point, speed);
            FlyingMobStep step = flyingMobTravelStep(prepared.velocity);
            if (step == null) continue;
            PhantomCommand candidate = new PhantomCommand(prepared, step);
            if (chosen == null || betterPhantom(candidate, chosen, goal, landing)) chosen = candidate;
        }
        if (chosen == null) throw error("route_phantom_edge_unexecutable");
        requireFlightLease();
        flightPrepared = true;
        mob.setSprinting(false);
        phantomInput = chosen.control.point;
        phantomControlTick = true;
        try { controller.tick(); }
        finally { phantomControlTick = false; phantomInput = null; }
        requireFlightLease();
        PhantomControl expected = chosen.control;
        if (!pos.equals(mob.position()) || mob.getDeltaMovement().distanceTo(expected.velocity) > 1e-8
            || mob.getYRot() != expected.yaw || mob.getXRot() != expected.pitch || mob.yBodyRot != expected.bodyYaw
            || ((Phantom.PhantomMoveControl) controller).speed != expected.speed
            || mob.xxa != 0 || mob.yya != 0 || mob.zza != 0)
            throw error("route_phantom_preparation_changed");
        flightStep = predictFlyingMobStep(mob.getDeltaMovement());
    }

    /** Pure PhantomMoveControl.tick, including its original-horizontal skip and float casts. */
    private PhantomControl phantomControl(Vec3 point, float speed) {
        float yaw = mob.getYRot(), pitch = mob.getXRot(), bodyYaw = mob.yBodyRot;
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(bodyYaw))
            throw error("route_phantom_rotation_invalid");
        if (mob.horizontalCollision) { yaw += 180.0F; speed = 0.1F; }
        Vec3 velocity = mob.getDeltaMovement();
        double dx = point.x - mob.getX(), dy = point.y - mob.getY(), dz = point.z - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (Math.abs(horizontal) > 1.0E-5F) {
            double factor = 1.0 - Math.abs(dy * 0.7F) / horizontal;
            dx *= factor; dz *= factor;
            horizontal = Math.sqrt(dx * dx + dz * dz);
            double distance = Math.sqrt(dx * dx + dz * dz + dy * dy);
            float oldYaw = yaw;
            float heading = (float) Mth.atan2(dz, dx);
            float from = Mth.wrapDegrees(yaw + 90.0F);
            float to = Mth.wrapDegrees(heading * (180.0F / (float) Math.PI));
            yaw = Mth.approachDegrees(from, to, 4.0F) - 90.0F;
            bodyYaw = yaw;
            if (Mth.degreesDifferenceAbs(oldYaw, yaw) < 3.0F)
                speed = Mth.approach(speed, 1.8F, 0.005F * (1.8F / speed));
            else speed = Mth.approach(speed, 0.2F, 0.025F);
            pitch = (float) (-(Mth.atan2(-dy, horizontal) * 180.0F / (float) Math.PI));
            float forward = yaw + 90.0F;
            double x = (double) (speed * Mth.cos(forward * (float) (Math.PI / 180.0))) * Math.abs(dx / distance);
            double z = (double) (speed * Mth.sin(forward * (float) (Math.PI / 180.0))) * Math.abs(dz / distance);
            double y = (double) (speed * Mth.sin(pitch * (float) (Math.PI / 180.0))) * Math.abs(dy / distance);
            velocity = velocity.add(new Vec3(x, y, z).subtract(velocity).scale(0.2));
        }
        if (!Double.isFinite(velocity.lengthSqr())) throw error("route_phantom_input_invalid");
        return new PhantomControl(point, velocity, yaw, pitch, bodyYaw, speed);
    }

    private boolean betterPhantom(PhantomCommand candidate, PhantomCommand old, Vec3 goal, boolean landing) {
        boolean near = flyingMobPositionReached(candidate.step, goal, landing);
        boolean oldNear = flyingMobPositionReached(old.step, goal, landing);
        // At rest facing away, neutral can beat every real turning input in
        // immediate distance. Allow a safe native turn when neither advances.
        if (!near && !oldNear && mob.getDeltaMovement().length() <= 0.03 && goal.subtract(mob.position()).horizontalDistance() > EPS
            && candidate.step.after.distanceToSqr(goal) >= mob.position().distanceToSqr(goal)
            && old.step.after.distanceToSqr(goal) >= mob.position().distanceToSqr(goal)) {
            float heading = (float) (Mth.atan2(goal.z - mob.getZ(), goal.x - mob.getX()) * (180.0F / (float) Math.PI)) - 90.0F;
            float angle = Mth.degreesDifferenceAbs(candidate.control.yaw, heading);
            float oldAngle = Mth.degreesDifferenceAbs(old.control.yaw, heading);
            if (angle != oldAngle) return angle < oldAngle;
        }
        return betterFlyingMobStep(candidate.step, old.step, goal, landing);
    }

    private boolean ownsPhantomCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller
            && mob.getNavigation() == phantomNavigation && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
    }

    private void clearPhantomControls() {
        phantomInput = null; phantomControlTick = false;
        if (!ownsPhantomCleanup()) return;
        clearInputs();
        mob.setSprinting(false);
        // Never tick: even native neutral can rotate/reset speed on collision.
        // Original moveTargetPoint, private speed, gravity and velocity stay native.
    }

    public static void beforePhantomSizeChange(Phantom body) {
        if (body.getClass() != Phantom.class || body.level().isClientSide || body.level().getServer() == null
            || !body.level().getServer().isSameThread()) return;
        ScriptNavigation route = OWNED.get(body);
        if (route == null || !route.active || route.physics != Physics.PHANTOM || body.getPhantomSize() == route.phantomSize) return;
        RuntimeException failure = error("route_phantom_size_changed");
        if (route.movementFailure == null) route.movementFailure = failure;
        try {
            if (body.level() == route.level && route.owner.vehicleLeaseActive(route.lease) && route.ownsPhantomCleanup()) route.stop();
        } catch (RuntimeException | LinkageError cleanup) {
            if (cleanup != route.movementFailure) route.movementFailure.addSuppressed(cleanup);
        } finally {
            route.phantomInput = null; route.phantomControlTick = false;
            OWNED.remove(body, route); route.active = false;
        }
        // Native refresh/reposition and attack-attribute update proceed unchanged.
    }

    /** Four pure current-tick pulse directions; no future cooldown/RNG prediction. */
    private void prepareGhast(Vec3 goal, boolean landing) {
        requireFlightLease();
        if (flightStep != null) throw error("route_duplicate_flight_control");
        Ghast.GhastMoveControl control = (Ghast.GhastMoveControl) controller;
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement(), toward = goal.subtract(pos);
        if (!Double.isFinite(velocity.lengthSqr()) || !Double.isFinite(toward.lengthSqr())) throw error("route_ghast_input_invalid");
        int countdown = control.floatDuration;
        // Supported landing requires a real downward native move, not zero-motion onGround bookkeeping.
        if (landing && toward.y <= 0.12) toward = new Vec3(toward.x, Math.min(toward.y, -0.001), toward.z);
        GhastCommand chosen = null;
        for (Vec3 direction : new Vec3[]{Vec3.ZERO, toward, velocity.scale(-1), toward.subtract(velocity.scale(4))}) {
            Vec3 wanted = pos.add(direction.normalize().scale(0.5));
            Vec3 delta = wanted.subtract(pos);
            // ceil(length)<=1 means native canReach has no iterations. This is
            // only a query bound; our full actual movement sweep is checked below.
            if (!Double.isFinite(delta.lengthSqr()) || delta.length() > 0.5 + EPS || Mth.ceil(delta.length()) > 1)
                throw error("route_ghast_wanted_limit");
            Vec3 prepared = countdown <= 0 ? velocity.add(delta.normalize().scale(0.1)) : velocity;
            FlyingMobStep step = flyingMobTravelStep(prepared);
            if (step == null) continue;
            GhastCommand candidate = new GhastCommand(wanted, prepared, step);
            if (chosen == null || betterFlyingMobStep(candidate.step, chosen.step, goal, landing)) chosen = candidate;
        }
        if (chosen == null) throw error("route_ghast_edge_unexecutable");
        requireFlightLease();
        if (control.floatDuration != countdown) throw error("route_ghast_countdown_changed");
        flightPrepared = true;
        mob.setSprinting(false);
        controller.setWantedPosition(chosen.wanted.x, chosen.wanted.y, chosen.wanted.z, 0); // native modifier is ignored
        controller.tick();
        requireFlightLease();
        int increment = control.floatDuration - countdown;
        if (!pos.equals(mob.position()) || mob.getDeltaMovement().distanceTo(chosen.velocity) > 1e-8
            || (countdown <= 0 ? increment < 1 || increment > 5 : control.floatDuration != countdown - 1)
            || controller.operation != MoveControl.Operation.MOVE_TO || mob.xxa != 0 || mob.yya != 0 || mob.zza != 0)
            throw error("route_ghast_preparation_changed");
        flightStep = predictFlyingMobStep(mob.getDeltaMovement());
    }

    private static boolean flyingMobPositionReached(FlyingMobStep step, Vec3 goal, boolean landing) {
        return goal.subtract(step.after).horizontalDistance() < 0.12 && Math.abs(goal.y - step.after.y) < 0.12
            && (!landing || step.ground);
    }

    private static boolean betterFlyingMobStep(FlyingMobStep candidate, FlyingMobStep old, Vec3 goal, boolean landing) {
        boolean near = flyingMobPositionReached(candidate, goal, landing), oldNear = flyingMobPositionReached(old, goal, landing);
        boolean arrived = near && candidate.velocity.length() <= 0.03, oldArrived = oldNear && old.velocity.length() <= 0.03;
        if (arrived != oldArrived) return arrived;
        if (near != oldNear) return near;
        // A velocity penalty outside the arrival band creates a permanent idle
        // dead zone for a fixed .1 pulse. Inside it, favor actual settling.
        double score = near ? candidate.velocity.lengthSqr() : candidate.after.distanceToSqr(goal);
        double oldScore = oldNear ? old.velocity.lengthSqr() : old.after.distanceToSqr(goal);
        if (score != oldScore) return score < oldScore;
        return candidate.velocity.lengthSqr() < old.velocity.lengthSqr();
    }

    private String flyingMobError(String reason) {
        return (physics == Physics.PHANTOM ? "route_phantom_" : "route_ghast_") + reason;
    }

    private DryStep predictFlyingMobStep(Vec3 velocity) {
        FlyingMobStep step = flyingMobTravelStep(velocity);
        if (step == null) throw error(flyingMobError("edge_unexecutable"));
        return new DryStep(step.after, step.velocity);
    }

    /** FlyingMob dry travel: zero route input, native collision, then XYZ friction drag with no gravity. */
    private FlyingMobStep flyingMobTravelStep(Vec3 velocity) {
        requireMode();
        if (!Double.isFinite(velocity.lengthSqr()) || mob.xxa != 0 || mob.yya != 0 || mob.zza != 0)
            throw error(flyingMobError("input_invalid"));
        BlockPos oldSupport = mob.getBlockPosBelowThatAffectsMyMovement();
        checkCell(oldSupport);
        float drag = 0.91F;
        if (mob.onGround()) {
            float friction = level.getBlockState(oldSupport).getFriction(level, oldSupport, mob);
            if (!Float.isFinite(friction) || friction <= 0 || friction > 1) throw error(flyingMobError("friction_unsupported"));
            drag = friction * 0.91F;
        }
        // FlyingMob's .1*(.16277137/drag^3) or airborne .02 acceleration
        // multiplies our actual zero input. The controller pulse is not input acceleration.
        Motion motion = collide(mob.getBoundingBox(), velocity, mob.onGround());
        Vec3 after = mob.position().add(motion.delta);
        if (!inside(after) || !withinEdge(after)) return null;
        AABB sweep = flightVolume(mob.getBoundingBox().expandTowards(motion.delta));
        checkVolume(sweep);
        if (!level.noCollision(mob, sweep)) return null;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(sweep.minX, sweep.minY, sweep.minZ),
            BlockPos.containing(sweep.maxX - EPS, sweep.maxY - EPS, sweep.maxZ - EPS))) {
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) return null;
            if (!ordinaryFlightInsideBlock(state.getBlock())) throw error(flyingMobError("block_effect_unsupported"));
        }
        AABB bounds = mob.getBoundingBox().move(motion.delta);
        BlockPos support = motion.ground ? supportingBlock(bounds, after) : null;
        if (motion.ground && support == null && mob.mainSupportingBlockPos.isPresent())
            support = supportingBlock(bounds.move(-motion.delta.x, 0, -motion.delta.z), after);
        boolean verticalCollision = velocity.y != motion.delta.y;
        requireHoverImpact(support, after, verticalCollision, motion.ground);
        BlockPos feet = BlockPos.containing(after), below = movementSupport(after, support);
        checkCell(feet); checkCell(below);
        float factor = level.getBlockState(feet).getBlock().getSpeedFactor();
        if (factor == 1) factor = level.getBlockState(below).getBlock().getSpeedFactor();
        if (!Float.isFinite(factor) || factor < 0 || factor > 4) throw error(flyingMobError("block_speed_unsupported"));
        Vec3 remaining = new Vec3(Mth.equal(velocity.x, motion.delta.x) ? velocity.x : 0,
            verticalCollision ? 0 : velocity.y, Mth.equal(velocity.z, motion.delta.z) ? velocity.z : 0);
        return new FlyingMobStep(after, remaining.multiply(factor, 1, factor).scale((double) drag), motion.ground);
    }

    private boolean ownsGhastCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller
            && mob.getNavigation() == ghastNavigation && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
    }

    private void clearGhastControls() {
        if (!ownsGhastCleanup()) return;
        clearInputs();
        mob.setSprinting(false);
        controller.setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        // No controller tick: preserve cooldown/RNG, native gravity flag and velocity.
        // Outer AgentActions cleanup retains its existing horizontal-momentum policy.
    }

    private void prepareFlight(Vec3 goal, boolean landing) {
        if (physics == Physics.PHANTOM) { preparePhantom(goal, landing); return; }
        if (physics == Physics.GHAST) { prepareGhast(goal, landing); return; }
        if (physics == Physics.ALLAY) { prepareAllayFlight(goal, landing); return; }
        requireFlightLease();
        if (flightStep != null) throw error("route_duplicate_flight_control");
        // Wither ordinary aiStep has already applied .6 Y damping and pursuit,
        // then NoAI .98/cutoff. Consume that observed state; do not repeat it.
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement(), difference = goal.subtract(pos);
        BlockPos support = mob.getBlockPosBelowThatAffectsMyMovement();
        checkCell(support);
        float friction = level.getBlockState(support).getFriction(level, support, mob);
        if (!(friction >= 0.6F && friction <= 1)) throw error("route_flight_friction_unsupported");
        double speed = mob.getAttributeValue(mob.onGround() ? Attributes.MOVEMENT_SPEED : Attributes.FLYING_SPEED);
        if (mob.isSprinting() && mob.onGround()) speed /= 1.3;
        if (!Double.isFinite(speed) || speed <= 0 || speed > 4) throw error("route_flight_speed_unsupported");
        Vec3 horizontal = difference.multiply(0.05, 0, 0.05).subtract(velocity.multiply(0.7, 0, 0.7));
        double vertical = difference.y * 0.02 - velocity.y * 0.5;
        // Keep actual downward contact for a supported endpoint; a zero-motion
        // noGravity tick would clear native onGround instead of proving landing.
        if (landing && difference.y <= 0.12) vertical = Math.min(vertical, -0.001);
        double desired = Math.max(horizontal.horizontalDistance(), Math.abs(vertical));
        double modifier = mob.onGround()
            ? Math.sqrt(desired / (speed * speed * (0.21600002F / (friction * friction * friction))))
            : desired / (0.02F * speed);
        modifier = Math.clamp(modifier, 0, Math.min(1, 0.5 / speed));
        Vec3 wanted = pos;
        if (desired > 1e-5) {
            double length = horizontal.horizontalDistance();
            Vec3 heading = length > 1e-12 ? horizontal.scale(1 / length)
                : new Vec3(-Mth.sin(mob.getYRot() * ((float) Math.PI / 180)), 0, Mth.cos(mob.getYRot() * ((float) Math.PI / 180)));
            wanted = pos.add(heading.x, Math.signum(vertical), heading.z);
        }
        flightPrepared = true;
        mob.setSprinting(false);
        controller.setWantedPosition(wanted.x, wanted.y, wanted.z, modifier);
        controller.tick();
        requireFlightLease();
        if (!mob.isNoGravity() || !mob.position().equals(pos) || !mob.getDeltaMovement().equals(velocity))
            throw error("route_flight_preparation_changed");
        // Input is coupled by FlyingMoveControl. Predict the actual values,
        // not the desired feedback vector, and never write simulated velocity.
        flightStep = predictDryStep(velocity, friction);
    }

    private void requireFlightLease() {
        if (OWNED.get(mob) != this || !active || !owner.vehicleLeaseActive(lease))
            throw error("script_no_longer_controls_body");
        requireMode();
        if (mob.getNavigation().getPath() != null) throw error("route_flight_navigation_changed");
    }

    /** Cleanup uses the original native WAIT branch and never changes velocity. */
    private void clearFlightControls() {
        if (physics == Physics.WITHER && !ownsWitherCleanup()) return;
        if (physics == Physics.PHANTOM) { clearPhantomControls(); return; }
        if (physics == Physics.GHAST) { clearGhastControls(); return; }
        if (hoverMode(physics)) { clearHoverFlightControls(); return; }
        if (mob.level() != level || mob.isRemoved() || mob.getMoveControl() != controller
            || OWNED.get(mob) != null && OWNED.get(mob) != this
            || owner.currentScriptId() != null && !lease.equals(owner.currentScriptId())) return;
        mob.getNavigation().stop();
        mob.setXxa(0); mob.setYya(0); mob.setZza(0); mob.setSpeed(0);
        mob.getJumpControl().tick(); mob.setJumping(false); mob.setSprinting(false);
        if (controller.hasWanted()) {
            controller.setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
            controller.tick(); // consume MOVE_TO at the current position, without travel
        }
        controller.tick(); // native WAIT clears noGravity (hoversInPlace=false)
        if (mob.isNoGravity()) throw error("route_flight_gravity_not_released");
    }

    /** Allay's native acceleration differs from LivingEntity/Parrot dry travel. */
    private void prepareAllayFlight(Vec3 goal, boolean landing) {
        requireFlightLease();
        if (flightStep != null) throw error("route_duplicate_flight_control");
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement(), difference = goal.subtract(pos);
        double speed = mob.getAttributeValue(mob.onGround() ? Attributes.MOVEMENT_SPEED : Attributes.FLYING_SPEED);
        if (mob.isSprinting() && mob.onGround()) speed /= 1.3;
        if (!Double.isFinite(speed) || speed <= 0 || speed > 4) throw error("route_flight_speed_unsupported");
        Vec3 horizontal = difference.multiply(0.02, 0, 0.02).subtract(velocity.multiply(0.35, 0, 0.35));
        double vertical = difference.y * 0.02 - velocity.y * 0.35;
        if (landing && difference.y <= 0.12) vertical = Math.min(vertical, -0.001);
        double desired = Math.max(horizontal.horizontalDistance(), Math.abs(vertical));
        // Actual forward/Y input and moveRelative acceleration both use speed.
        // Keep their combined length below one; the predictor reads actual input.
        double modifier = Math.clamp(Math.sqrt(desired) / speed, 0, Math.min(1, 0.5 / speed));
        Vec3 wanted = pos;
        if (desired > 1e-5) {
            double length = horizontal.horizontalDistance();
            Vec3 heading = length > 1e-12 ? horizontal.scale(1 / length)
                : new Vec3(-Mth.sin(mob.getYRot() * ((float) Math.PI / 180)), 0, Mth.cos(mob.getYRot() * ((float) Math.PI / 180)));
            wanted = pos.add(heading.x, Math.signum(vertical), heading.z);
        }
        flightPrepared = true;
        mob.setSprinting(false);
        controller.setWantedPosition(wanted.x, wanted.y, wanted.z, modifier);
        controller.tick();
        requireFlightLease();
        if (!mob.isNoGravity() || !mob.position().equals(pos) || !mob.getDeltaMovement().equals(velocity))
            throw error("route_flight_preparation_changed");
        flightStep = predictAllayStep();
    }

    private DryStep predictAllayStep() {
        requireMode();
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement(), input = new Vec3(mob.xxa, mob.yya, mob.zza);
        float speed = mob.getSpeed();
        if (!Float.isFinite(speed) || speed < 0 || speed > 4 || !Double.isFinite(input.lengthSqr())
            || !Double.isFinite(velocity.lengthSqr())) throw error("route_allay_input_invalid");
        if (input.lengthSqr() >= 1e-7) {
            if (input.lengthSqr() > 1) input = input.normalize();
            input = input.scale(speed);
            float radians = mob.getYRot() * ((float) Math.PI / 180), sin = Mth.sin(radians), cos = Mth.cos(radians);
            velocity = velocity.add(input.x * cos - input.z * sin, input.y, input.z * cos + input.x * sin);
        }
        Motion motion = collide(mob.getBoundingBox(), velocity, mob.onGround());
        Vec3 after = pos.add(motion.delta);
        if (!inside(after) || !withinEdge(after)) throw error("route_flight_edge_unexecutable");
        requireFlightVolume(mob.getBoundingBox().expandTowards(motion.delta));
        AABB bounds = mob.getBoundingBox().move(motion.delta);
        BlockPos support = motion.ground ? supportingBlock(bounds, after) : null;
        if (motion.ground && support == null && mob.mainSupportingBlockPos.isPresent())
            support = supportingBlock(bounds.move(-motion.delta.x, 0, -motion.delta.z), after);
        boolean verticalCollision = velocity.y != motion.delta.y;
        requireHoverImpact(support, after, verticalCollision, motion.ground);
        BlockPos feet = BlockPos.containing(after), below = movementSupport(after, support);
        checkCell(feet); checkCell(below);
        float factor = level.getBlockState(feet).getBlock().getSpeedFactor();
        if (factor == 1) factor = level.getBlockState(below).getBlock().getSpeedFactor();
        if (!Float.isFinite(factor) || factor < 0 || factor > 4) throw error("route_allay_block_speed_unsupported");
        Vec3 remaining = new Vec3(Mth.equal(velocity.x, motion.delta.x) ? velocity.x : 0,
            verticalCollision ? 0 : velocity.y, Mth.equal(velocity.z, motion.delta.z) ? velocity.z : 0);
        return new DryStep(after, remaining.multiply(factor, 1, factor).scale(0.91F));
    }

    private void requireHoverImpact(BlockPos support, Vec3 after, boolean verticalCollision, boolean ground) {
        BlockPos legacy = BlockPos.containing(after.x, after.y - (double) 0.2F, after.z);
        if (support != null) {
            checkCell(support);
            legacy = level.getBlockState(support).collisionExtendsVertically(level, support, mob)
                ? support : support.atY(legacy.getY());
        }
        checkCell(legacy);
        Block block = level.getBlockState(legacy).getBlock();
        try {
            if (verticalCollision && block.getClass().getMethod("updateEntityAfterFallOn",
                net.minecraft.world.level.BlockGetter.class, Entity.class).getDeclaringClass() != Block.class
                || ground && block.getClass().getMethod("stepOn", net.minecraft.world.level.Level.class,
                    BlockPos.class, BlockState.class, Entity.class).getDeclaringClass() != Block.class)
                throw error(nativeFlyingMob(physics) ? flyingMobError("impact_unsupported") : physics == Physics.WITHER ? "route_wither_impact_unsupported" : physics == Physics.BEE ? "route_bee_impact_unsupported" : "route_allay_impact_unsupported");
        } catch (ReflectiveOperationException failure) { throw error(nativeFlyingMob(physics) ? flyingMobError("impact_unsupported") : physics == Physics.WITHER ? "route_wither_impact_unsupported" : physics == Physics.BEE ? "route_bee_impact_unsupported" : "route_allay_impact_unsupported"); }
    }

    private float flightFriction() {
        BlockPos support = mob.getBlockPosBelowThatAffectsMyMovement();
        checkCell(support);
        float friction = level.getBlockState(support).getFriction(level, support, mob);
        if (!(friction >= 0.6F && friction <= 1)) throw error("route_flight_friction_unsupported");
        return friction;
    }

    private boolean ownsWitherCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller
            && mob.getNavigation() == witherNavigation && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
    }

    private boolean ownsHoverCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller
            && mob.getNavigation() == hoverNavigation && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
    }

    /** Hover WAIT intentionally retains noGravity and actual momentum. */
    private void clearHoverFlightControls() {
        if (!ownsHoverCleanup()) return;
        mob.getNavigation().stop();
        mob.setXxa(0); mob.setYya(0); mob.setZza(0); mob.setSpeed(0);
        mob.getJumpControl().tick(); mob.setJumping(false); mob.setSprinting(false);
        if (controller.hasWanted()) {
            controller.setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
            controller.tick();
        }
        controller.tick();
    }

    private void requireRabbitLease() {
        if (OWNED.get(mob) != this || !active || !owner.vehicleLeaseActive(lease))
            throw error("script_no_longer_controls_body");
        requireMode();
        if (mob.getNavigation().getPath() != null) throw error("route_rabbit_navigation_changed");
    }

    /** Existing native intent chooses this branch; selected input never creates it. */
    private void prepareDrownedSwim(Vec3 goal) {
        requireDrownedWaterLease();
        if (drownedStep != null) throw error("route_duplicate_drowned_swim_control");
        Vec3 pos = mob.position(), before = mob.getDeltaMovement();
        float oldSpeed = mob.getSpeed();
        double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1);
        if (!Double.isFinite(baseSpeed) || baseSpeed <= 0 || baseSpeed > 4 || !Float.isFinite(oldSpeed)
            || oldSpeed < 0 || oldSpeed > 4 || !Double.isFinite(before.lengthSqr()))
            throw error("route_drowned_swim_input_invalid");
        double buoyancy = drownedSearching || drownedTarget != null && drownedTarget.getY() > pos.y ? 0.002 : 0;
        Vec3 correction = goal.subtract(pos).scale(0.015).subtract(before.scale(0.4)).add(0, -buoyancy, 0);
        // Select a bounded command from native horizontal (.005*s + .01*s*s)
        // and vertical (.1*s) authority. Real interpolation/turning decides the step.
        double horizontal = correction.horizontalDistance();
        double horizontalSpeed = horizontal <= 0.015 ? (Math.sqrt(0.000025 + 0.04 * horizontal) - 0.005) / 0.02
            : horizontal / 0.015;
        double wantedSpeed = Math.max(horizontalSpeed, Math.abs(correction.y) / 0.1);
        double modifier = Math.clamp((wantedSpeed - 0.875 * oldSpeed) / (0.125 * baseSpeed), 0, 1);
        Vec3 heading = correction.multiply(1, (0.005 + 0.01 * Math.min(wantedSpeed, 1)) / 0.1, 1);
        double length = heading.length();
        // DrownedMoveControl divides by distance even for a zero speed command.
        if (length > 1e-10) heading = heading.scale(1 / length);
        else {
            float yaw = mob.getYRot() * ((float) Math.PI / 180);
            heading = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        }
        Vec3 wanted = pos.add(heading), delta = wanted.subtract(pos);
        if (!Double.isFinite(delta.lengthSqr()) || delta.lengthSqr() < 1e-12 || delta.lengthSqr() > 1.000001)
            throw error("route_drowned_swim_input_invalid");
        drownedPrepared = true;
        mob.setSprinting(false);
        float speed = Mth.lerp(0.125F, oldSpeed, (float) (modifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
        controller.setWantedPosition(wanted.x, wanted.y, wanted.z, modifier);
        drownedControlTick = true;
        try { controller.tick(); }
        finally { drownedControlTick = false; }
        requireDrownedWaterLease();
        Vec3 expectedVelocity = before.add(speed * delta.x * 0.005,
            buoyancy + speed * (delta.y / delta.length()) * 0.1, speed * delta.z * 0.005);
        if (!pos.equals(mob.position()) || Math.abs(mob.getSpeed() - speed) > 1e-6
            || mob.getDeltaMovement().distanceTo(expectedVelocity) > 1e-8)
            throw error("route_drowned_swim_preparation_changed");
        drownedStep = predictDrownedSwim();
    }

    /** Drowned's true-intent travel has neither ordinary-water gravity nor Fish's sink. */
    private DryStep predictDrownedSwim() {
        requireMode();
        Vec3 velocity = mob.getDeltaMovement(), input = new Vec3(mob.xxa, mob.yya, mob.zza);
        if (!Double.isFinite(velocity.lengthSqr()) || !Double.isFinite(input.lengthSqr()))
            throw error("route_drowned_swim_input_invalid");
        if (input.lengthSqr() >= 1e-7) {
            if (input.lengthSqr() > 1) input = input.normalize();
            input = input.scale(0.01F);
            float yaw = mob.getYRot() * ((float) Math.PI / 180), sin = Mth.sin(yaw), cos = Mth.cos(yaw);
            velocity = velocity.add(input.x * cos - input.z * sin, input.y, input.z * cos + input.x * sin);
        }
        Motion motion = collide(mob.getBoundingBox(), velocity, mob.onGround());
        Vec3 after = mob.position().add(motion.delta);
        if (!inside(after) || !withinEdge(after)) throw error("route_drowned_swim_edge_unexecutable");
        AABB sweep = mob.getBoundingBox().expandTowards(motion.delta);
        requireSubmerged(sweep);
        if (!level.noCollision(mob, fishVolume(sweep))) throw error("route_drowned_swim_volume_obstructed");
        if (Math.abs(velocity.y - motion.delta.y) > EPS) throw error("route_drowned_swim_vertical_collision");
        Vec3 remaining = new Vec3(Mth.equal(velocity.x, motion.delta.x) ? velocity.x : 0, velocity.y,
            Mth.equal(velocity.z, motion.delta.z) ? velocity.z : 0).scale(0.9);
        return new DryStep(after, remaining);
    }

    /** Native no-intent controller preparation, then preflight the chosen real fluid impulse. */
    private void prepareDrownedWater(Vec3 goal) {
        requireDrownedWaterLease();
        if (drownedStep != null) throw error("route_duplicate_drowned_water_control");
        Vec3 pos = mob.position(), before = mob.getDeltaMovement();
        double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1);
        double swimSpeed = mob.getAttributeValue(NeoForgeMod.SWIM_SPEED);
        if (!Double.isFinite(baseSpeed) || baseSpeed <= 0 || baseSpeed > 4
            || !Double.isFinite(swimSpeed) || swimSpeed <= 0 || swimSpeed > 4)
            throw error("route_drowned_water_attributes_unsupported");
        Vec3 toward = goal.subtract(pos).multiply(1, 0, 1).subtract(before.multiply(4, 0, 4));
        double modifier = Math.clamp(toward.horizontalDistance() * 0.8 / baseSpeed, 0, 1);
        drownedPrepared = true;
        mob.setSprinting(false);
        // Vertical control belongs to the actual fluid methods, not an ordinary
        // MoveControl ground-jump request caused by a high wanted Y.
        controller.setWantedPosition(pos.x + toward.x, pos.y, pos.z + toward.z, modifier);
        controller.tick();
        mob.getJumpControl().tick(); mob.setJumping(false);
        requireDrownedWaterLease();
        Vec3 prepared = before.add(0, mob.onGround() ? 0 : -0.008, 0);
        if (!mob.position().equals(pos) || mob.getDeltaMovement().distanceTo(prepared) > 1e-8)
            throw error("route_drowned_water_preparation_changed");
        Vec3 velocity = mob.getDeltaMovement();
        double jump = drownedCanFloat ? (double) 0.04F * swimSpeed : 0.3;
        double sink = (double) 0.04F * swimSpeed;
        // Brake each ascent with actual sink input. Admit a new pulse only
        // when that native stopping arc fits; neutral descent can make room.
        int impulse = velocity.y > 0 ? -1
            : pos.y + velocity.y < goal.y && drownedPulseFits(velocity.y + jump, sink) ? 1 : 0;
        double amount = impulse > 0 ? jump : impulse < 0 ? -sink : 0;
        Vec3 nextVelocity = velocity.add(0, amount, 0);
        drownedStep = predictDrownedWater(nextVelocity);
        // Only native methods apply the prospective impulse after preflight.
        if (impulse > 0) mob.jumpInFluid(NeoForgeMod.WATER_TYPE.value());
        else if (impulse < 0) mob.sinkInFluid(NeoForgeMod.WATER_TYPE.value());
        requireDrownedWaterLease();
        if (mob.getDeltaMovement().distanceTo(nextVelocity) > 1e-8)
            throw error("route_drowned_water_impulse_changed");
    }

    /** Input selection only: no future displacement is authorized by this estimate.
     * New currents are read/preflighted on the next real tick. */
    private boolean drownedPulseFits(double velocityY, double sink) {
        double rise = 0, gravity = mob.getGravity();
        if (!Double.isFinite(velocityY) || !Double.isFinite(gravity) || gravity < 0 || gravity > 1)
            throw error("route_drowned_water_attributes_unsupported");
        boolean stopped = false;
        for (int step = 0; step < 32; step++) {
            if (velocityY <= 0) { stopped = true; break; }
            rise += velocityY;
            // Native travel, then ordinary NoAI aiStep, then next airborne
            // Drowned controller and the sink input used throughout ascent.
            velocityY = mob.getFluidFallingAdjustedMovement(gravity, false,
                new Vec3(0, velocityY * (double) 0.8F, 0)).y * 0.98;
            if (Math.abs(velocityY) < 0.003) velocityY = 0;
            velocityY -= 0.008;
            if (velocityY > 0) velocityY -= sink;
        }
        if (!stopped) return false;
        Vec3 top = mob.position().add(0, rise, 0);
        if (!inside(top) || !withinEdge(top)) return false;
        AABB volume = fishVolume(mob.getBoundingBox().expandTowards(0, rise, 0));
        checkVolumeSize(volume);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(volume.minX, volume.minY, volume.minZ),
            BlockPos.containing(volume.maxX - EPS, volume.maxY - EPS, volume.maxZ - EPS))) {
            AABB cell = new AABB(pos);
            if (drownedPolicyVolume != null && !cell.intersects(drownedPolicyVolume) && !cell.intersects(drownedInitialVolume))
                return false;
            checkCell(pos); checkCell(pos.above());
            var state = level.getBlockState(pos);
            var fluid = state.getFluidState();
            if (!state.is(Blocks.WATER) || !fluid.is(FluidTags.WATER)
                || pos.getY() + fluid.getHeight(level, pos) + EPS < Math.min(volume.maxY, pos.getY() + 1)) return false;
        }
        return level.noCollision(mob, volume);
    }

    /** LivingEntity's ordinary water branch, not AbstractFish/Drowned true-intent swimming. */
    private DryStep predictDrownedWater(Vec3 velocity) {
        requireMode();
        Vec3 pos = mob.position();
        double gravity = mob.getGravity();
        float efficiency = (float) mob.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY);
        float swimSpeed = (float) mob.getAttributeValue(NeoForgeMod.SWIM_SPEED);
        if (!Double.isFinite(gravity) || gravity < 0 || gravity > 1 || !Float.isFinite(efficiency)
            || efficiency < 0 || efficiency > 1 || !Float.isFinite(swimSpeed) || swimSpeed <= 0 || swimSpeed > 4)
            throw error("route_drowned_water_attributes_unsupported");
        if (mob.isSprinting()) throw error("route_drowned_water_sprint_changed");
        boolean falling = velocity.y <= 0;
        float drag = 0.8F, acceleration = 0.02F;
        if (!mob.onGround()) efficiency *= 0.5F;
        if (efficiency > 0) {
            drag += (0.54600006F - drag) * efficiency;
            acceleration += (mob.getSpeed() - acceleration) * efficiency;
        }
        if (mob.hasEffect(MobEffects.DOLPHINS_GRACE)) drag = 0.96F;
        acceleration *= swimSpeed;
        Vec3 input = new Vec3(mob.xxa, mob.yya, mob.zza);
        if (input.lengthSqr() >= 1e-7) {
            if (input.lengthSqr() > 1) input = input.normalize();
            input = input.scale(acceleration);
            float radians = mob.getYRot() * ((float) Math.PI / 180), sin = Mth.sin(radians), cos = Mth.cos(radians);
            velocity = velocity.add(input.x * cos - input.z * sin, input.y, input.z * cos + input.x * sin);
        }
        Motion motion = collide(mob.getBoundingBox(), velocity, mob.onGround());
        Vec3 after = pos.add(motion.delta);
        AABB bounds = mob.getBoundingBox().move(motion.delta), sweep = mob.getBoundingBox().expandTowards(motion.delta);
        if (!inside(after) || !withinEdge(after)) throw error("route_drowned_water_edge_unexecutable");
        requireSubmerged(sweep);
        if (!level.noCollision(mob, fishVolume(sweep))) throw error("route_drowned_water_volume_obstructed");
        boolean wallX = !Mth.equal(velocity.x, motion.delta.x), wallZ = !Mth.equal(velocity.z, motion.delta.z);
        double y = velocity.y;
        if (velocity.y != motion.delta.y) {
            // Native Entity.move calls the supporting block's fall response.
            // Prove the ordinary zero-Y implementation; do not guess a bounce.
            BlockPos support = motion.ground ? supportingBlock(bounds, after) : null;
            if (motion.ground && support == null && mob.mainSupportingBlockPos.isPresent())
                support = supportingBlock(bounds.move(-motion.delta.x, 0, -motion.delta.z), after);
            BlockPos legacy = BlockPos.containing(after.x, after.y - (double) 0.2F, after.z);
            if (support != null) {
                checkCell(support);
                legacy = level.getBlockState(support).collisionExtendsVertically(level, support, mob)
                    ? support : support.atY(legacy.getY());
            }
            checkCell(legacy);
            Block block = level.getBlockState(legacy).getBlock();
            try {
                if (block.getClass().getMethod("updateEntityAfterFallOn", net.minecraft.world.level.BlockGetter.class, Entity.class)
                    .getDeclaringClass() != Block.class || motion.ground && block.getClass()
                    .getMethod("stepOn", net.minecraft.world.level.Level.class, BlockPos.class, BlockState.class, Entity.class)
                    .getDeclaringClass() != Block.class) throw error("route_drowned_water_impact_unsupported");
            } catch (ReflectiveOperationException failure) { throw error("route_drowned_water_impact_unsupported"); }
            y = 0;
        }
        Vec3 remaining = new Vec3(wallX ? 0 : velocity.x, y, wallZ ? 0 : velocity.z).multiply(drag, 0.8F, drag);
        remaining = mob.getFluidFallingAdjustedMovement(gravity, falling, remaining);
        if (wallX || wallZ) {
            // Native isFree runs at the post-move position and may read above
            // the chosen edge. Bound/check that query before actual travel.
            AABB escape = bounds.move(remaining.x, remaining.y + 0.6F - after.y + pos.y, remaining.z);
            checkVolume(escape.inflate(EPS));
            if (level.noCollision(mob, escape) && !level.containsAnyLiquid(escape))
                remaining = new Vec3(remaining.x, 0.3F, remaining.z);
        }
        return new DryStep(after, remaining);
    }

    /** Only the native controller's activity query sees the leased selected edge. */
    public static boolean selectedDrownedSwimControl(Mob body, MoveControl control, PathNavigation navigation) {
        if (body.level().isClientSide) return false;
        ScriptNavigation route = OWNED.get(body);
        if (route == null || !route.active || route.physics != Physics.DROWNED_SWIM || !route.drownedControlTick
            || route.controller != control || route.drownedNavigation != navigation) return false;
        try {
            route.requireThread();
            route.requireDrownedWaterLease();
            return true;
        } catch (RuntimeException failure) { route.rejectMovement(failure); return false; }
    }

    private boolean ownsDrownedWaterCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller
            && mob.getNavigation() == drownedNavigation && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
    }

    private void requireDrownedWaterLease() {
        if (OWNED.get(mob) != this || !active || !owner.vehicleLeaseActive(lease))
            throw error("script_no_longer_controls_body");
        requireMode();
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

    /** Eleven pure input candidates at most; execute only the chosen native controller tick. */
    private void prepareTurtle(Vec3 goal) {
        requireTurtleLease();
        if (turtleStep != null) throw error("route_duplicate_turtle_control");
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement();
        double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1);
        float history = turtleAdjustedSpeed();
        if (!Double.isFinite(baseSpeed) || baseSpeed <= 0 || baseSpeed > 4 || !Float.isFinite(history) || history < 0 || history > 4
            || !Double.isFinite(velocity.lengthSqr()) || !Double.isFinite(goal.distanceToSqr(pos))) throw error("route_turtle_input_invalid");
        Vec3 correction = goal.subtract(pos).scale(0.015).subtract(velocity.scale(0.4));
        // This estimate chooses input only. The travel tail below evaluates sink at its actual predicted destination.
        double vertical = correction.y + (turtleSinks(pos) ? 0.005 / 0.9 : 0) - 0.005;
        double horizontal = correction.horizontalDistance();
        double wantedSpeed = Math.max(horizontal, Math.abs(vertical)) / 0.1;
        double modifier = Math.clamp((wantedSpeed - 0.875 * history) / (0.125 * baseSpeed), 0, 1);
        double heading = horizontal > 1e-10 ? Math.atan2(correction.z, correction.x) * 180 / Math.PI - 90 : mob.getYRot();
        Vec3 toward = goal.subtract(pos);
        double goalHeading = toward.horizontalDistanceSqr() > 1e-12 ? Math.atan2(toward.z, toward.x) * 180 / Math.PI - 90 : heading;
        TurtleCommand chosen = turtleCommand(false, heading, 0, vertical, baseSpeed);
        double score = chosen == null ? Double.POSITIVE_INFINITY : turtleScore(chosen, goal);
        for (double yaw : new double[]{heading, goalHeading}) {
            for (double input : new double[]{modifier, 0, 0.25, 0.5, 1}) {
                TurtleCommand candidate = turtleCommand(true, yaw, input, vertical, baseSpeed);
                if (candidate == null) continue;
                double candidateScore = turtleScore(candidate, goal);
                if (candidateScore < score) { chosen = candidate; score = candidateScore; }
            }
        }
        if (chosen == null) throw error("route_turtle_edge_unexecutable");
        requireTurtleLease();
        turtlePrepared = true;
        mob.setSprinting(false);
        controller.setWantedPosition(chosen.wanted.x, chosen.wanted.y, chosen.wanted.z, chosen.modifier);
        turtleControlTick = chosen.active;
        try { controller.tick(); }
        finally { turtleControlTick = false; }
        requireTurtleLease();
        if (!pos.equals(mob.position()) || Math.abs(mob.getSpeed() - chosen.speed) > 1e-6
            || Math.abs(Mth.wrapDegrees(mob.getYRot() - chosen.yaw)) > 1e-4
            || mob.getDeltaMovement().distanceTo(chosen.preparedVelocity) > 1e-8
            || Math.abs(mob.zza - chosen.speed) > 1e-6 || mob.xxa != 0 || mob.yya != 0)
            throw error("route_turtle_preparation_changed");
        turtleStep = turtleTravelStep(mob.getDeltaMovement(), mob.getSpeed(), mob.getYRot());
        if (turtleStep == null) throw error("route_turtle_edge_unexecutable");
    }

    private float turtleAdjustedSpeed() {
        float speed = mob.getSpeed();
        if (!turtleHome.closerToCenterThan(mob.position(), 16)) speed = Math.max(speed / 2.0F, 0.08F);
        if (turtleBaby) speed = Math.max(speed / 3.0F, 0.06F);
        return speed;
    }

    private boolean turtleSinks(Vec3 position) {
        return turtleTarget == null && (!turtleGoingHome || !turtleHome.closerToCenterThan(position, 20));
    }

    private TurtleCommand turtleCommand(boolean activeControl, double wantedYaw, double modifier, double vertical, double baseSpeed) {
        Vec3 pos = mob.position(), wanted = pos, velocity = mob.getDeltaMovement().add(0, 0.005, 0);
        float speed = 0, yaw = mob.getYRot();
        if (activeControl) {
            speed = Mth.lerp(0.125F, turtleAdjustedSpeed(), (float) (modifier * baseSpeed));
            double up = speed > 1e-8 ? Math.clamp(vertical / (speed * 0.1), -0.999, 0.999) : 0;
            double horizontal = Math.sqrt(1 - up * up), angle = wantedYaw * Math.PI / 180;
            wanted = pos.add(-Math.sin(angle) * horizontal, up, Math.cos(angle) * horizontal);
            Vec3 delta = wanted.subtract(pos);
            if (!Double.isFinite(delta.lengthSqr()) || delta.lengthSqr() < 1e-12 || delta.lengthSqr() > 1.000001)
                throw error("route_turtle_input_invalid");
            float nativeWanted = (float) (Mth.atan2(delta.z, delta.x) * 180.0F / (float) Math.PI) - 90.0F;
            yaw = guardianYaw(yaw, nativeWanted); // identical native MoveControl.rotlerp
            velocity = velocity.add(0, (double) speed * (delta.y / delta.length()) * 0.1, 0);
        }
        DryStep step = turtleTravelStep(velocity, speed, yaw);
        return step == null ? null : new TurtleCommand(activeControl, wanted, modifier, yaw, speed, velocity, step);
    }

    private double turtleScore(TurtleCommand command, Vec3 goal) {
        Vec3 toward = goal.subtract(command.step.after);
        double score = toward.lengthSqr() + command.step.velocity.lengthSqr() * 4;
        double horizontal = toward.horizontalDistance();
        if (horizontal > 1e-8) {
            double yaw = command.yaw * Math.PI / 180;
            double facing = (-Math.sin(yaw) * toward.x + Math.cos(yaw) * toward.z) / horizontal;
            score += 0.05 * Math.min(1, horizontal * horizontal) * (1 - facing);
        }
        return score;
    }

    private DryStep turtleTravelStep(Vec3 prepared, float speed, float yaw) {
        requireMode();
        if (!Double.isFinite(prepared.lengthSqr()) || !Float.isFinite(speed) || speed < 0 || speed > 4 || !Float.isFinite(yaw))
            throw error("route_turtle_input_invalid");
        Vec3 velocity = prepared;
        if ((double) speed * speed >= 1e-7) {
            double acceleration = Math.min(1, speed) * (double) 0.1F;
            float radians = yaw * ((float) Math.PI / 180);
            velocity = velocity.add(-acceleration * Mth.sin(radians), 0, acceleration * Mth.cos(radians));
        }
        Vec3 prospective = mob.position().add(velocity);
        if (!inside(prospective) || !withinEdge(prospective)) return null;
        AABB sweep = fishVolume(mob.getBoundingBox().expandTowards(velocity));
        checkVolume(sweep.expandTowards(0, 1, 0));
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(sweep.minX, sweep.minY, sweep.minZ),
            BlockPos.containing(sweep.maxX - EPS, sweep.maxY - EPS, sweep.maxZ - EPS))) {
            var state = level.getBlockState(pos);
            var fluid = state.getFluidState();
            if (!state.is(Blocks.WATER) || !fluid.is(FluidTags.WATER)
                || pos.getY() + fluid.getHeight(level, pos) + EPS < Math.min(sweep.maxY, pos.getY() + 1)) return null;
        }
        if (!level.noCollision(mob, sweep)) return null;
        Motion motion = collide(mob.getBoundingBox(), velocity, mob.onGround());
        if (Math.abs(velocity.y - motion.delta.y) > EPS) return null;
        Vec3 after = mob.position().add(motion.delta);
        if (!inside(after) || !withinEdge(after)) return null;
        Vec3 remaining = new Vec3(Mth.equal(velocity.x, motion.delta.x) ? velocity.x : 0, velocity.y,
            Mth.equal(velocity.z, motion.delta.z) ? velocity.z : 0).scale(0.9);
        // Turtle evaluates home20 after its native move, not at the old position.
        if (turtleSinks(after)) remaining = remaining.add(0, -0.005, 0);
        return new DryStep(after, remaining);
    }

    private void requireTurtleLease() {
        requireMode();
        if (!active || OWNED.get(mob) != this || !owner.vehicleLeaseActive(lease)) throw error("script_no_longer_controls_body");
    }

    public static boolean selectedTurtleControl(Turtle body, MoveControl control, PathNavigation navigation) {
        if (body.level().isClientSide) return false;
        ScriptNavigation route = OWNED.get(body);
        if (route == null || route.physics != Physics.TURTLE || !route.turtleControlTick
            || route.controller != control || route.turtleNavigation != navigation) return false;
        try { route.requireThread(); route.requireTurtleLease(); return true; }
        catch (RuntimeException failure) { route.rejectMovement(failure); return false; }
    }

    private boolean ownsTurtleCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller
            && mob.getNavigation() == turtleNavigation && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
    }

    private void clearTurtleControls() {
        if (!ownsTurtleCleanup()) return;
        turtleControlTick = false;
        clearInputs();
        mob.setSpeed(0);
        mob.setSprinting(false);
        controller.setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        // Do not tick: Turtle's native idle branch adds buoyancy, too.
    }

    /** Release old geometry before native growth/pose refresh; native scute/drop behavior proceeds. */
    public static void beforeTurtleDimensions(Turtle body) {
        if (body.getClass() != Turtle.class || body.level().isClientSide || body.level().getServer() == null
            || !body.level().getServer().isSameThread()) return;
        ScriptNavigation route = OWNED.get(body);
        if (route == null || !route.active || route.physics != Physics.TURTLE
            || body.isBaby() == route.turtleBaby && body.getPose() == route.turtlePose) return;
        if (route.movementFailure == null) route.movementFailure = error("route_turtle_dimensions_changed");
        try {
            if (body.level() == route.level && route.owner.vehicleLeaseActive(route.lease) && route.ownsTurtleCleanup()) route.stop();
        } catch (RuntimeException | LinkageError cleanup) {
            if (cleanup != route.movementFailure) route.movementFailure.addSuppressed(cleanup);
        } finally {
            OWNED.remove(body, route);
            route.active = false;
        }
    }

    /** Nine pure candidates at most; only the selected native branch is executed. */
    private void prepareGuardian(Vec3 goal) {
        requireGuardianLease();
        if (guardianStep != null) throw error("route_duplicate_guardian_control");
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement();
        float oldSpeed = mob.getSpeed();
        double baseSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1);
        if (!Double.isFinite(baseSpeed) || baseSpeed <= 0 || baseSpeed > 4 || !Float.isFinite(oldSpeed) || oldSpeed < 0 || oldSpeed > 4
            || !Double.isFinite(velocity.lengthSqr()) || !Double.isFinite(goal.distanceToSqr(pos))) throw error("route_guardian_input_invalid");
        Vec3 correction = goal.subtract(pos).scale(0.015).subtract(velocity.scale(0.4));
        double horizontal = correction.horizontalDistance();
        double wantedSpeed = Math.max(horizontal, Math.abs(correction.y)) / 0.1;
        double modifier = Math.clamp((wantedSpeed - 0.875 * oldSpeed) / (0.125 * baseSpeed), 0, 1);
        double heading = horizontal > 1e-10 ? Math.atan2(correction.z, correction.x) * 180 / Math.PI - 90 : mob.getYRot();
        double wave = Math.sin((double) (mob.tickCount + mob.getId()) * 0.5) * 0.05;
        double compensation = Math.atan2(wave, Math.max(1e-9, horizontal)) * 180 / Math.PI;
        GuardianCommand chosen = guardianCommand(false, heading, 0, correction.y, baseSpeed);
        double score = chosen == null ? Double.POSITIVE_INFINITY : guardianScore(chosen, goal);
        for (double yaw : new double[]{heading, heading + compensation}) {
            for (double input : new double[]{modifier, 0, 0.5, 1}) {
                GuardianCommand candidate = guardianCommand(true, yaw, input, correction.y, baseSpeed);
                if (candidate == null) continue;
                double candidateScore = guardianScore(candidate, goal);
                if (candidateScore < score) { chosen = candidate; score = candidateScore; }
            }
        }
        if (chosen == null) throw error("route_guardian_edge_unexecutable");
        requireGuardianLease();
        guardianPrepared = true;
        mob.setSprinting(false);
        controller.setWantedPosition(chosen.wanted.x, chosen.wanted.y, chosen.wanted.z, chosen.modifier);
        guardianControlTick = chosen.active;
        try { controller.tick(); }
        finally { guardianControlTick = false; }
        guardianMoving = chosen.active;
        requireGuardianLease();
        if (!pos.equals(mob.position()) || Math.abs(mob.getSpeed() - chosen.speed) > 1e-6
            || Math.abs(Mth.wrapDegrees(mob.getYRot() - chosen.yaw)) > 1e-4
            || mob.getDeltaMovement().distanceTo(chosen.preparedVelocity) > 1e-8
            || Math.abs(mob.zza - chosen.speed) > 1e-6 || mob.xxa != 0 || mob.yya != 0)
            throw error("route_guardian_preparation_changed");
        // Recheck actual prepared state before authorizing physical travel.
        guardianStep = guardianTravelStep(mob.getDeltaMovement(), mob.getSpeed(), mob.getYRot(), ((Guardian) mob).isMoving());
        if (guardianStep == null) throw error("route_guardian_edge_unexecutable");
    }

    private GuardianCommand guardianCommand(boolean moving, double wantedYaw, double modifier, double vertical, double baseSpeed) {
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement(), wanted = pos;
        float speed = 0, yaw = mob.getYRot();
        if (moving) {
            speed = Mth.lerp(0.125F, mob.getSpeed(), (float) (modifier * baseSpeed));
            // Choose wanted Y using the actual yaw-dependent native oscillation.
            float aimed = guardianYaw(yaw, (float) wantedYaw);
            double radians = (double) (aimed * (float) (Math.PI / 180));
            double verticalWave = Math.sin((double) (mob.tickCount + mob.getId()) * 0.75) * 0.05
                * (Math.sin(radians) + Math.cos(radians)) * 0.25;
            double up = speed > 1e-8 ? Math.clamp((vertical - verticalWave) / (speed * 0.1), -0.999, 0.999) : 0;
            double horizontal = Math.sqrt(1 - up * up), angle = wantedYaw * Math.PI / 180;
            wanted = pos.add(-Math.sin(angle) * horizontal, up, Math.cos(angle) * horizontal);
            Vec3 delta = wanted.subtract(pos);
            if (!Double.isFinite(delta.lengthSqr()) || delta.lengthSqr() < 1e-12 || delta.lengthSqr() > 1.000001)
                throw error("route_guardian_input_invalid");
            float nativeWanted = (float) (Mth.atan2(delta.z, delta.x) * 180.0F / (float) Math.PI) - 90.0F;
            yaw = guardianYaw(yaw, nativeWanted);
            radians = (double) (yaw * (float) (Math.PI / 180));
            double sin = Math.sin(radians), cos = Math.cos(radians);
            double wave = Math.sin((double) (mob.tickCount + mob.getId()) * 0.5) * 0.05;
            verticalWave = Math.sin((double) (mob.tickCount + mob.getId()) * 0.75) * 0.05;
            velocity = velocity.add(wave * cos, verticalWave * (sin + cos) * 0.25 + (double) speed * (delta.y / delta.length()) * 0.1, wave * sin);
        }
        DryStep step = guardianTravelStep(velocity, speed, yaw, moving);
        return step == null ? null : new GuardianCommand(moving, wanted, modifier, yaw, speed, velocity, step);
    }

    /** MoveControl.rotlerp's native wrapping, without mutating its controller. */
    private static float guardianYaw(float current, float wanted) {
        float yaw = current + Mth.clamp(Mth.wrapDegrees(wanted - current), -90, 90);
        if (yaw < 0) yaw += 360;
        else if (yaw > 360) yaw -= 360;
        return yaw;
    }

    private double guardianScore(GuardianCommand command, Vec3 goal) {
        Vec3 toward = goal.subtract(command.step.after);
        double score = toward.lengthSqr() + command.step.velocity.lengthSqr() * 4;
        double horizontal = toward.horizontalDistance();
        if (horizontal > 1e-8) {
            double yaw = command.yaw * Math.PI / 180;
            double facing = (-Math.sin(yaw) * toward.x + Math.cos(yaw) * toward.z) / horizontal;
            // Idle cannot turn. Reward native heading progress away from arrival.
            score += 0.05 * Math.min(1, horizontal * horizontal) * (1 - facing);
        }
        return score;
    }

    private DryStep guardianTravelStep(Vec3 prepared, float speed, float yaw, boolean moving) {
        requireMode();
        if (!Double.isFinite(prepared.lengthSqr()) || !Float.isFinite(speed) || speed < 0 || speed > 4 || !Float.isFinite(yaw))
            throw error("route_guardian_input_invalid");
        Vec3 velocity = prepared;
        if ((double) speed * speed >= 1e-7) {
            double acceleration = Math.min(1, speed) * (double) 0.1F;
            float radians = yaw * ((float) Math.PI / 180);
            velocity = velocity.add(-acceleration * Mth.sin(radians), 0, acceleration * Mth.cos(radians));
        }
        Vec3 prospective = mob.position().add(velocity);
        if (!inside(prospective) || !withinEdge(prospective)) return null;
        AABB sweep = fishVolume(mob.getBoundingBox().expandTowards(velocity));
        // Invalid candidate geometry is not selected. Changed/unknown cells
        // still throw immediately; no mutation or retry has occurred here.
        checkVolume(sweep.expandTowards(0, 1, 0));
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(sweep.minX, sweep.minY, sweep.minZ),
            BlockPos.containing(sweep.maxX - EPS, sweep.maxY - EPS, sweep.maxZ - EPS))) {
            var state = level.getBlockState(pos);
            var fluid = state.getFluidState();
            if (!state.is(Blocks.WATER) || !fluid.is(FluidTags.WATER)
                || pos.getY() + fluid.getHeight(level, pos) + EPS < Math.min(sweep.maxY, pos.getY() + 1)) return null;
        }
        if (!level.noCollision(mob, sweep)) return null;
        Motion motion = collide(mob.getBoundingBox(), velocity, mob.onGround());
        if (Math.abs(velocity.y - motion.delta.y) > EPS) return null;
        Vec3 after = mob.position().add(motion.delta);
        if (!inside(after) || !withinEdge(after)) return null;
        Vec3 remaining = new Vec3(Mth.equal(velocity.x, motion.delta.x) ? velocity.x : 0, velocity.y,
            Mth.equal(velocity.z, motion.delta.z) ? velocity.z : 0).scale(0.9);
        if (!moving && guardianTarget == null) remaining = remaining.add(0, -0.005, 0);
        return new DryStep(after, remaining);
    }

    private void requireGuardianLease() {
        requireMode();
        if (!active || OWNED.get(mob) != this || !owner.vehicleLeaseActive(lease)) throw error("script_no_longer_controls_body");
    }

    /** Only this exact active native preparation borrows selected-route activity. */
    public static boolean selectedGuardianControl(Guardian body, MoveControl control, PathNavigation navigation) {
        if (body.level().isClientSide) return false;
        ScriptNavigation route = OWNED.get(body);
        if (route == null || route.physics != Physics.GUARDIAN || !route.guardianControlTick
            || route.controller != control || route.guardianNavigation != navigation) return false;
        try { route.requireThread(); route.requireGuardianLease(); return true; }
        catch (RuntimeException failure) { route.rejectMovement(failure); return false; }
    }

    private boolean ownsGuardianCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller && mob.getLookControl() == guardianLook
            && mob.getNavigation() == guardianNavigation && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
    }

    private void clearGuardianControls() {
        if (!ownsGuardianCleanup()) return;
        guardianControlTick = false;
        clearInputs();
        mob.setSprinting(false);
        controller.setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        // With the original navigation stopped, the native idle branch clears
        // moving/speed without sinusoids, look changes or physical travel.
        if (((Guardian) mob).isMoving() || mob.getSpeed() != 0) {
            guardianPrepared = true;
            controller.tick();
        }
        guardianMoving = ((Guardian) mob).isMoving();
        if (guardianMoving || mob.getSpeed() != 0) throw error("route_guardian_idle_failed");
    }

    /** Native preparation, not a copied FishMoveControl or a brain/navigation tick. */
    private void prepareSmoothSwim(Vec3 goal) {
        requireMode();
        if (OWNED.get(mob) != this || !active || !owner.vehicleLeaseActive(lease))
            throw error("script_no_longer_controls_body");
        if (smoothStep != null) throw error(physics == Physics.FROG ? "route_duplicate_frog_control"
            : physics == Physics.AXOLOTL ? "route_duplicate_axolotl_control"
            : physics == Physics.DOLPHIN ? "route_duplicate_dolphin_control" : "route_duplicate_tadpole_control");
        Vec3 pos = mob.position(), velocity = mob.getDeltaMovement();
        double speed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1);
        if (!Double.isFinite(speed) || speed <= 0 || speed > 4) throw error("route_aquatic_speed_unsupported");
        // Desired acceleration selects input only. The actual limited native
        // pitch/yaw and complete XYZ input below determine the next movement.
        Vec3 correction = goal.subtract(pos).scale(0.015).subtract(velocity.scale(0.4));
        if (physics == Physics.FROG) correction = correction.add(0, -0.005, 0);
        else if (physics != Physics.AXOLOTL) correction = correction.add(0, fishHadTarget ? -0.005 : 0.005 / 0.9 - 0.005, 0);
        double length = correction.length();
        Vec3 heading = length > 1e-12 ? correction.scale(1 / length) : new Vec3(0, 0, 1);
        double alignment = Math.max(0, mob.getLookAngle().dot(heading));
        double modifier;
        if (physics == Physics.DOLPHIN || physics == Physics.AXOLOTL || physics == Physics.FROG) {
            // These native travel methods use prepared speed, not AbstractFish's .01F.
            // Coupled normalized input gives factor*f1*min(f1,1) acceleration.
            float factor = physics == Physics.AXOLOTL ? 0.1F : 0.02F;
            double requestedSpeed = length <= factor ? Math.sqrt(length / factor) : length / factor;
            modifier = Math.clamp(requestedSpeed / speed, 0, 1) * alignment;
        } else modifier = Math.clamp(length / (0.01F * speed), 0, 1) * alignment;
        Vec3 wanted = pos.add(heading);
        smoothPrepared = true;
        mob.setSprinting(false);
        controller.setWantedPosition(wanted.x, wanted.y, wanted.z, modifier);
        smoothControlTick = true;
        try { controller.tick(); }
        finally { smoothControlTick = false; }
        requireMode();
        if (!active || OWNED.get(mob) != this || !owner.vehicleLeaseActive(lease))
            throw error("script_no_longer_controls_body");
        Vec3 preparedVelocity = physics == Physics.AXOLOTL ? velocity : velocity.add(0, 0.005, 0);
        if (!pos.equals(mob.position()) || mob.getDeltaMovement().distanceTo(preparedVelocity) > 1e-8)
            throw error(smoothReason("preparation_changed"));
        smoothStep = smoothSwimTravelStep();
    }

    /** Reviewed native water travel with the real controller's full XYZ input. */
    private DryStep smoothSwimTravelStep() {
        requireMode();
        Vec3 velocity = mob.getDeltaMovement(), input = new Vec3(mob.xxa, mob.yya, mob.zza);
        if (!Double.isFinite(input.lengthSqr()) || !Double.isFinite(velocity.lengthSqr()))
            throw error(smoothReason("input_invalid"));
        float acceleration = physics == Physics.DOLPHIN || physics == Physics.AXOLOTL || physics == Physics.FROG ? mob.getSpeed() : 0.01F;
        if (!Float.isFinite(acceleration) || acceleration < 0 || acceleration > 4)
            throw error(smoothReason("input_invalid"));
        if (input.lengthSqr() >= 1e-7) {
            if (input.lengthSqr() > 1) input = input.normalize();
            input = input.scale(acceleration);
            float radians = mob.getYRot() * ((float) Math.PI / 180), sin = Mth.sin(radians), cos = Mth.cos(radians);
            velocity = velocity.add(input.x * cos - input.z * sin, input.y, input.z * cos + input.x * sin);
        }
        Motion motion = collide(mob.getBoundingBox(), velocity, mob.onGround());
        Vec3 after = mob.position().add(motion.delta);
        if (!inside(after) || !withinEdge(after)) throw error("route_aquatic_edge_unexecutable");
        AABB sweep = mob.getBoundingBox().expandTowards(motion.delta);
        requireSubmerged(sweep);
        if (!level.noCollision(mob, fishVolume(sweep))) throw error(smoothReason("volume_obstructed"));
        if (Math.abs(velocity.y - motion.delta.y) > EPS) throw error("route_aquatic_vertical_collision");
        Vec3 remaining = new Vec3(Mth.equal(velocity.x, motion.delta.x) ? velocity.x : 0, velocity.y,
            Mth.equal(velocity.z, motion.delta.z) ? velocity.z : 0).scale(0.9);
        if (physics != Physics.AXOLOTL && physics != Physics.FROG && !fishHadTarget) remaining = remaining.add(0, -0.005, 0);
        return new DryStep(after, remaining);
    }

    private void steer(Vec3 goal, boolean sprint) {
        requireMode();
        if (physics == Physics.SNIFFER) snifferPrepared = true;
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
        double snifferModifier = physics == Physics.SNIFFER ? controller.getSpeedModifier() : 0;
        MoveControl.Operation snifferOperation = physics == Physics.SNIFFER ? controller.operation : null;
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
            MoveControl.Operation snifferBeforeOperation = snifferOperation;
            boolean snifferKick = false;
            if (physics == Physics.SNIFFER && distance >= 0.05) {
                // Exact steer -> setWantedPosition -> native MoveControl.tick order.
                yaw = (float) (Math.atan2(difference.z, difference.x) * 180 / Math.PI - 90);
                snifferModifier = 1;
                speed = (float) (baseSpeed * (sprint ? 1.3 : 1));
                if (snifferOperation != MoveControl.Operation.JUMPING) snifferOperation = MoveControl.Operation.MOVE_TO;
                if (snifferOperation == MoveControl.Operation.MOVE_TO) {
                    snifferOperation = MoveControl.Operation.WAIT;
                    float desiredYaw = (float) (Mth.atan2(difference.z, difference.x) * 180.0F / (float) Math.PI) - 90.0F;
                    yaw += Mth.clamp(Mth.wrapDegrees(desiredYaw - yaw), -90.0F, 90.0F);
                    if (yaw < 0) yaw += 360; else if (yaw > 360) yaw -= 360;
                    BlockPos feet = BlockPos.containing(pos);
                    checkCell(feet);
                    BlockState feetState = level.getBlockState(feet);
                    VoxelShape shape = feetState.getCollisionShape(level, feet);
                    if (difference.y > mob.maxUpStep() && difference.horizontalDistanceSqr() < Math.max(1.0F, mob.getBbWidth())
                        || !shape.isEmpty() && pos.y < shape.max(Direction.Axis.Y) + feet.getY()
                            && !feetState.is(BlockTags.DOORS) && !feetState.is(BlockTags.FENCES))
                        snifferOperation = MoveControl.Operation.JUMPING;
                } else if (ground) snifferOperation = MoveControl.Operation.WAIT;
                direction = forward(yaw);
            }
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
                if (power <= 0 || physics == Physics.MAGMA && boundedHopRise(power, mob.getGravity()) <= 0) return null;
                if (physics == Physics.SNIFFER) {
                    velocity = groundJumpVelocity(velocity, power, yaw, sprint);
                    snifferKick = snifferKick(velocity, snifferModifier);
                    if (snifferKick) velocity = velocity.add(forward(yaw).scale((double) 0.1F));
                } else {
                    velocity = new Vec3(velocity.x, power, velocity.z);
                    if (sprint) velocity = velocity.add(direction.scale(0.2));
                }
            }
            double acceleration = ground ? speed * (0.21600002F / (friction * friction * friction)) : 0.02F;
            if (physics == Physics.SNIFFER && ground)
                acceleration = (float) speed * (0.21600002F / (friction * friction * friction));
            velocity = velocity.add(direction.scale(acceleration * Math.min(1, speed)));
            Motion motion = collide(bounds, velocity, ground);
            Vec3 next = pos.add(motion.delta);
            if (!inside(next) || !withinEdge(next)) return null;
            if ((physics == Physics.DROWNED || groundWrapper(physics) || hopper) && hasFluid(bounds.expandTowards(motion.delta))) return null;
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
                : physics == Physics.SNIFFER
                    ? new Frame(pos, next, beforeVelocity, beforeGround, goal, jump, sprint, yaw, snifferModifier, new SnifferControl(snifferBeforeOperation, snifferOperation, snifferKick))
                    : new Frame(pos, next, beforeVelocity, beforeGround, goal, jump, sprint));
            bounds = nextBounds; pos = next; velocity = remaining; ground = motion.ground;
            if (motion.wall && motion.delta.horizontalDistanceSqr() < 1e-7 && tick > 15) return null;
        }
        return null;
    }

    private static Vec3 forward(float yaw) {
        float radians = yaw * ((float) Math.PI / 180);
        return new Vec3(-Mth.sin(radians), 0, Mth.cos(radians));
    }

    /** Native superclass order: jump power, then sprint, before Sniffer's conditional tail. */
    private static Vec3 groundJumpVelocity(Vec3 velocity, double power, float yaw, boolean sprint) {
        if (power <= 1e-5F) return velocity;
        Vec3 result = new Vec3(velocity.x, power, velocity.z);
        return sprint ? result.add(forward(yaw).scale(0.2)) : result;
    }

    private static boolean snifferKick(Vec3 afterSuper, double modifier) {
        return modifier > 0 && afterSuper.horizontalDistanceSqr() < 0.01;
    }

    /** Pure no-input tower arc. The later native placement and each real move remain separately guarded. */
    private void preflightSnifferTower(double requiredY) {
        requireMode();
        Vec3 position = mob.position();
        AABB bounds = mob.getBoundingBox();
        double power = jumpPower(mob, position);
        if (!(power > 1e-5F)) throw error("route_jump_unavailable");
        Vec3 velocity = groundJumpVelocity(mob.getDeltaMovement(), power, mob.getYRot(), false);
        if (snifferKick(velocity, controller.getSpeedModifier()))
            velocity = velocity.add(forward(mob.getYRot()).scale((double) 0.1F));
        boolean ground = mob.onGround(), reached = false;
        BlockPos supporting = mob.mainSupportingBlockPos.orElse(null);
        boolean groundWithoutBlock = ground && supporting == null;
        long deadline = System.nanoTime() + 25_000_000L;
        for (int tick = 0; tick < HOP_FLIGHT_TICKS; tick++) {
            if (System.nanoTime() > deadline) throw error("route_prediction_budget");
            if (tick > 0) velocity = small(velocity.scale(0.98));
            BlockPos support = movementSupport(position, supporting);
            checkCell(support);
            float friction = level.getBlockState(support).getFriction(level, support, mob);
            if (!(friction >= 0.6F && friction <= 1)) throw error("route_sniffer_friction_unsupported");
            // clearInputs supplies zero native travel input throughout the build wait.
            Motion motion = collide(bounds, velocity, ground);
            AABB sweep = bounds.expandTowards(motion.delta);
            checkDryVolume(sweep);
            Vec3 next = position.add(motion.delta);
            if (!inside(next) || !withinEdge(next)) throw error("route_sniffer_tower_outside_edge");
            reached |= next.y >= requiredY - EPS;
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
            BlockPos feet = BlockPos.containing(next), below = movementSupport(next, supporting);
            checkCell(feet); checkCell(below);
            float factor = level.getBlockState(feet).getBlock().getSpeedFactor();
            if (factor == 1) factor = level.getBlockState(below).getBlock().getSpeedFactor();
            double drag = ground ? friction * 0.91F : 0.91F;
            remaining = remaining.multiply(factor * drag, 1, factor * drag);
            velocity = new Vec3(remaining.x, (remaining.y - mob.getGravity()) * 0.98F, remaining.z);
            if (motion.ground) {
                if (!reached) throw error("route_sniffer_tower_clearance_failed");
                return;
            }
            bounds = nextBounds; position = next; ground = motion.ground;
        }
        throw error("route_sniffer_tower_arc_unbounded");
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
        if (!fishMode(physics) && physics != Physics.DROWNED_WATER && !hoverMode(physics) && !nativeFlyingMob(physics) && box.get() != null) {
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
        drownedTravelTick = drownedWaterMode(physics);
        smoothTravelTick = smoothSwimmer(physics);
        guardianTravelTick = physics == Physics.GUARDIAN;
        turtleTravelTick = physics == Physics.TURTLE;
        hoverTravelTick = hoverMode(physics);
        ghastTravelTick = physics == Physics.GHAST;
        phantomTravelTick = physics == Physics.PHANTOM;
        witherTravelTick = physics == Physics.WITHER;
        mob.setNoAi(false);
        try { mob.travel(new Vec3(mob.xxa, mob.yya, mob.zza)); }
        finally { mob.setNoAi(true); mob.setJumping(false); drownedTravelTick = false; smoothTravelTick = false; guardianTravelTick = false; turtleTravelTick = false; hoverTravelTick = false; ghastTravelTick = false; phantomTravelTick = false; witherTravelTick = false; }
        requireMode();
        if (!inside(mob.position())) throw error("route_leaves_body_box");
        if (direct != null && !withinEdge(mob.position())) throw error("route_leaves_direct_corridor");
    }

    private void clearInputs() {
        mob.getNavigation().stop();
        mob.setXxa(0); mob.setYya(0); mob.setZza(0);
        if (physics != Physics.FISH && physics != Physics.GUARDIAN && physics != Physics.TURTLE) mob.setSpeed(0);
        if (physics != Physics.RABBIT) mob.getJumpControl().tick();
        mob.setJumping(false);
    }

    private void clearControls() {
        if (groundWrapper(physics) && !ownsGroundCleanup()) return;
        if (physics == Physics.TURTLE) { clearTurtleControls(); return; }
        if (physics == Physics.GUARDIAN) { clearGuardianControls(); return; }
        if (smoothSwimmer(physics) && !ownsSmoothSwimCleanup()) return;
        if (drownedWaterMode(physics) && !ownsDrownedWaterCleanup()) return;
        if (puffState >= 0 && !ownsPufferfishCleanup()) return;
        if (flightMode(physics)) { clearFlightControls(); return; }
        clearInputs();
        if (physics == Physics.RABBIT) { mob.getJumpControl().tick(); mob.setJumping(false); }
        if (physics == Physics.FISH) mob.setSpeed(0);
        mob.setSprinting(false);
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        // These modes change inputs only; outer action cancellation retains its existing policy.
        if (physics != Physics.DOLPHIN && physics != Physics.AXOLOTL && physics != Physics.FROG && physics != Physics.DROWNED_SWIM) mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
    }

    private boolean settled() {
        return mob.onGround() || mob.isInWater() || mob.onClimbable()
            || flightMode(physics) && mob.getDeltaMovement().length() <= 0.03;
    }
    private boolean arrived(Vec3 goal) {
        return goal.subtract(mob.position()).horizontalDistance() < 0.12 && Math.abs(goal.y - mob.getY()) < 0.12 && settled()
            && (!fishMode(physics) && !flightMode(physics) && physics != Physics.DROWNED_WATER || mob.getDeltaMovement().length() <= 0.03);
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
        if (flightMode(physics))
            return BlockPos.containing(mob.position()).equals(node) && arrived(goal) && (!flightLanding || mob.onGround());
        if (physics == Physics.DROWNED_WATER)
            return BlockPos.containing(mob.position()).equals(node) && goal.subtract(mob.position()).horizontalDistance() < 0.12
                && mob.getDeltaMovement().length() <= 0.03;
        if (physics == Physics.RABBIT) return rabbitArrivedAtNode(node, goal);
        if (!settled() || !logicalPosition(mob.position()).equals(node)
            || goal.subtract(mob.position()).horizontalDistance() >= 0.12) return false;
        if (fishMode(physics)) return arrived(goal);
        // Only water accepts the whole cell. A ladder may otherwise finish
        // almost a block above its target while still sliding down toward it.
        return level.getFluidState(node).is(FluidTags.WATER) || Math.abs(goal.y - mob.getY()) < 0.12;
    }

    private boolean rabbitArrivedAtNode(BlockPos node, Vec3 goal) {
        // Native hops need not converge to a cell's center. Accept only a quiet,
        // fully contained footprint with real support at the resolved feet Y.
        if (!mob.onGround() || !logicalPosition(mob.position()).equals(node)
            || Math.abs(goal.y - mob.getY()) > EPS || mob.getDeltaMovement().horizontalDistanceSqr() > 0.03 * 0.03)
            return false;
        AABB bounds = mob.getBoundingBox();
        if (bounds.minX < node.getX() - EPS || bounds.maxX > node.getX() + 1 + EPS
            || bounds.minZ < node.getZ() - EPS || bounds.maxZ > node.getZ() + 1 + EPS) return false;
        if (!inside(mob.position())) throw error("route_outside_body_box");
        checkVolume(bounds);
        checkDryVolume(bounds);
        if (!level.noCollision(mob, bounds)) return false;

        // Project actual top faces to one common Y slab, so native shape union
        // can prove coverage rather than accepting a tiny supporting overlap.
        VoxelShape support = Shapes.empty();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(bounds.minX, bounds.minY - 1, bounds.minZ),
            BlockPos.containing(bounds.maxX - EPS, bounds.minY, bounds.maxZ - EPS))) {
            checkCell(pos);
            for (AABB local : level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(mob)).toAabbs()) {
                AABB shape = local.move(pos);
                if (Math.abs(shape.maxY - bounds.minY) > EPS) continue;
                double minX = Math.max(bounds.minX, shape.minX), maxX = Math.min(bounds.maxX, shape.maxX);
                double minZ = Math.max(bounds.minZ, shape.minZ), maxZ = Math.min(bounds.maxZ, shape.maxZ);
                if (maxX > minX && maxZ > minZ)
                    support = Shapes.or(support, Shapes.box(minX, 0, minZ, maxX, 1, maxZ));
            }
        }
        VoxelShape footprint = Shapes.box(bounds.minX + EPS, 0, bounds.minZ + EPS, bounds.maxX - EPS, 1, bounds.maxZ - EPS);
        return !Shapes.joinIsNotEmpty(footprint, support, BooleanOp.ONLY_FIRST);
    }

    private Vec3 destination(BlockPos node) {
        Vec3 center = Vec3.atBottomCenterOf(node);
        if (fishMode(physics)) return center.add(0, swimTargetOffset(mob), 0);
        if (physics == Physics.DROWNED_WATER) return center.add(0, 0.5, 0);
        if (flightMode(physics)) return flightDestination(node);
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

    private Vec3 flightDestination(BlockPos node) {
        Vec3 center = Vec3.atBottomCenterOf(node);
        AABB footprint = mob.getBoundingBox().move(center.subtract(mob.position()));
        flightLanding = false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(footprint.minX, node.getY() - 1, footprint.minZ),
            BlockPos.containing(footprint.maxX - EPS, node.getY(), footprint.maxZ - EPS))) {
            checkCell(pos);
            for (AABB local : level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(mob)).toAabbs()) {
                AABB shape = local.move(pos);
                if (Math.abs(shape.maxY - node.getY()) <= EPS && shape.maxX > footprint.minX + EPS
                    && shape.minX < footprint.maxX - EPS && shape.maxZ > footprint.minZ + EPS && shape.minZ < footprint.maxZ - EPS)
                    flightLanding = true;
            }
        }
        if (flightLanding) {
            // The graph surveyed y+offset. Cover the additional descent to real
            // support; it stays in the same policy-surveyed feet block cells.
            requireFlightVolume(footprint.expandTowards(0, swimTargetOffset(mob), 0));
            return center;
        }
        return center.add(0, swimTargetOffset(mob), 0);
    }

    private boolean inside(Vec3 position) {
        BodyBox confined = box.get();
        return confined == null || confined.dimension().equals(level.dimension().location().toString()) && confined.holds(position);
    }

    private boolean withinEdge(Vec3 position) {
        if (physics == Physics.DROWNED_WATER && edgeStart != null && target != null) {
            // Native fluid pulses can rise before horizontal acceleration has
            // caught up. Bound Y by the selected endpoint feet cells; occupied
            // body/eye cells are separately limited to surveyed/start volumes.
            Vec3 edge = target.subtract(edgeStart).multiply(1, 0, 1);
            double t = edge.lengthSqr() == 0 ? 0
                : Math.clamp(position.subtract(edgeStart).dot(edge) / edge.lengthSqr(), 0, 1);
            return position.subtract(edgeStart.add(edge.scale(t))).horizontalDistance() <= 0.3
                && position.y >= Math.floor(Math.min(edgeStart.y, target.y))
                && position.y < Math.floor(Math.max(edgeStart.y, target.y)) + 1;
        }
        if ((fishMode(physics) || flightMode(physics)) && edgeStart != null && target != null) {
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
        if (flightMode(mode)) {
            JsonObject result = new JsonObject();
            result.addProperty("physics", mode == Physics.WITHER ? "native-wither-flight-post-tick" : mode == Physics.PHANTOM ? "native-phantom-flight-post-tick" : mode == Physics.GHAST ? "native-ghast-flight-post-tick" : mode == Physics.BEE ? "native-bee-flight-post-tick"
                : mode == Physics.ALLAY ? "native-allay-flight-post-tick" : "native-parrot-flight-post-tick");
            result.addProperty("locomotion", "flying");
            result.addProperty("canFly", true);
            result.addProperty("flightTargetYOffset", swimTargetOffset(mob));
            result.addProperty("stepHeight", Math.max(0, mob.maxUpStep()));
            result.addProperty("canSwim", false); result.addProperty("canJump", false);
            result.addProperty("jumpHeight", 0); result.addProperty("maxJumpDistance", 0); result.addProperty("maxSprintJumpDistance", 0);
            return result;
        }
        if (fishMode(mode) || mode == Physics.DROWNED_WATER) {
            JsonObject result = new JsonObject();
            result.addProperty("physics", mode == Physics.DROWNED_WATER ? "native-drowned-water-post-tick"
                : mode == Physics.DROWNED_SWIM ? "native-drowned-true-intent-post-tick"
                : mode == Physics.AXOLOTL ? "native-axolotl-submerged-post-tick"
                : mode == Physics.FROG ? "native-frog-submerged-post-tick"
                : mode == Physics.TURTLE ? "native-turtle-submerged-post-tick"
                : mode == Physics.GUARDIAN ? "native-guardian-submerged-post-tick"
                : mode == Physics.TADPOLE ? "native-tadpole-submerged-post-tick"
                : mode == Physics.DOLPHIN ? "native-dolphin-submerged-post-tick" : "native-fish-submerged-post-tick");
            result.addProperty("locomotion", "submerged");
            result.addProperty("swimTargetYOffset", mode == Physics.DROWNED_WATER ? 0.5 : swimTargetOffset(mob));
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
            ? mode == Physics.MAGMA || mode == Physics.RABBIT ? boundedHopRise(power, gravity) : jumpRise(power, gravity) : 0;
        double speed = supported ? mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / (mob.isSprinting() ? 1.3 : 1) : 0;
        double velocity = mob.getDeltaMovement().horizontalDistance();
        double width = mob.getBbWidth();
        JsonObject result = new JsonObject();
        result.addProperty("stepHeight", Math.max(0, mob.maxUpStep()));
        result.addProperty("jumpHeight", rise);
        result.addProperty("canJump", supported && rise > 0);
        result.addProperty("canSwim", supported && mode != Physics.RABBIT && !slimeHopper(mode) && mode != Physics.DROWNED && !groundWrapper(mode) && mob.canSwimInFluidType(NeoForgeMod.WATER_TYPE.value()));
        if (slimeHopper(mode) || mode == Physics.RABBIT) {
            double takeoffVelocity = mode == Physics.RABBIT && velocity < 0.1 ? velocity + 0.1F : velocity;
            double reach = rise > 0 ? hopEnvelope(speed, takeoffVelocity, power, gravity, width) : 0;
            result.addProperty("locomotion", "hopping");
            result.addProperty("maxJumpDistance", reach);
            result.addProperty("maxSprintJumpDistance", reach);
            result.addProperty("physics", mode == Physics.RABBIT ? "native-rabbit-hop-post-tick"
                : mode == Physics.MAGMA ? "native-magma-hop-post-tick" : "native-slime-hop-post-tick");
            return result;
        }
        result.addProperty("maxJumpDistance", rise > 0 ? reachEnvelope(speed, velocity, power, gravity, width, false, mode == Physics.DROWNED, mode == Physics.SNIFFER) : 0);
        result.addProperty("maxSprintJumpDistance", rise > 0 ? reachEnvelope(speed, velocity, power, gravity, width, true, mode == Physics.DROWNED, mode == Physics.SNIFFER) : 0);
        result.addProperty("physics", mode == Physics.DROWNED ? "native-drowned-dry-post-tick" : mode == Physics.FOX ? "native-fox-awake-post-tick" : mode == Physics.PANDA ? "native-panda-ground-post-tick" : mode == Physics.CAMEL ? "native-camel-ground-post-tick" : mode == Physics.SNIFFER ? "native-sniffer-ground-post-tick" : supported ? "native-ground-post-tick" : "unsupported");
        return result;
    }

    private static boolean flightMode(Physics mode) { return mode == Physics.PARROT || mode == Physics.WITHER || nativeFlyingMob(mode) || hoverMode(mode); }

    private static boolean nativeFlyingMob(Physics mode) { return mode == Physics.GHAST || mode == Physics.PHANTOM; }

    private static boolean hoverMode(Physics mode) { return mode == Physics.ALLAY || mode == Physics.BEE; }

    /** Identity only, including after lease release; no transient gravity/state test. */
    static boolean reviewedBeeFlightBody(Mob mob) {
        if (mob.getClass() != Bee.class || mob.getMoveControl().getClass() != FlyingMoveControl.class) return false;
        FlyingMoveControl control = (FlyingMoveControl) mob.getMoveControl();
        return control.maxTurn == 20 && control.hoversInPlace;
    }

    private static final ClassValue<Boolean> BEE_NAVIGATION = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            var enclosing = type.getEnclosingMethod();
            if (!type.isAnonymousClass() || type.getSuperclass() != FlyingPathNavigation.class || enclosing == null
                || enclosing.getDeclaringClass() != Bee.class || !enclosing.getName().equals("createNavigation")
                || enclosing.getReturnType() != PathNavigation.class || enclosing.getParameterCount() != 1
                || enclosing.getParameterTypes()[0] != net.minecraft.world.level.Level.class) return false;
            try {
                return type.getDeclaredMethod("tick").getReturnType() == void.class
                    && type.getDeclaredMethod("isStableDestination", BlockPos.class).getReturnType() == boolean.class;
            } catch (ReflectiveOperationException failure) { return false; }
        }
    };

    /** Identity only: cleanup must still recognize a released native hover body. */
    static boolean reviewedAllayFlightBody(Mob mob) {
        if (mob.getClass() != Allay.class || mob.getMoveControl().getClass() != FlyingMoveControl.class) return false;
        FlyingMoveControl control = (FlyingMoveControl) mob.getMoveControl();
        return control.maxTurn == 20 && control.hoversInPlace;
    }

    /** Stable native controller identity for outer input cleanup; no route/state mutation. */
    static boolean reviewedParrotFlightBody(Mob mob) {
        if (mob.getClass() != Parrot.class || mob.getMoveControl().getClass() != FlyingMoveControl.class) return false;
        FlyingMoveControl control = (FlyingMoveControl) mob.getMoveControl();
        return control.maxTurn == 10 && !control.hoversInPlace;
    }

    private static Physics physics(Mob mob) {
        if (mob.isPassenger() || mob.noPhysics || mob.isFallFlying() || mob.shouldDiscardFriction()) return Physics.UNSUPPORTED;
        Class<?> control = mob.getMoveControl().getClass();
        // Only this reviewed native controller owns its noGravity switch.
        if (reviewedParrotFlightBody(mob) && GROUND_METHODS.get(Parrot.class)) {
            Parrot parrot = (Parrot) mob;
            if (!parrot.isSleeping() && !parrot.isInSittingPose() && !parrot.isOrderedToSit() && !mob.isInWater() && !mob.isInLava()
                && !mob.onClimbable() && !mob.hasEffect(MobEffects.LEVITATION) && !mob.hasEffect(MobEffects.SLOW_FALLING))
                return Physics.PARROT;
            return Physics.UNSUPPORTED;
        }
        if (reviewedBeeFlightBody(mob) && GROUND_METHODS.get(Bee.class)) {
            return !mob.isSleeping() && !mob.isInWater() && !mob.isInLava() && !mob.onClimbable()
                && BEE_NAVIGATION.get(mob.getNavigation().getClass()) ? Physics.BEE : Physics.UNSUPPORTED;
        }
        if (reviewedAllayFlightBody(mob)) {
            return !mob.isSleeping() && !mob.isInWater() && !mob.isInLava()
                && mob.getNavigation().getClass() == FlyingPathNavigation.class ? Physics.ALLAY : Physics.UNSUPPORTED;
        }
        if (mob.getClass() == WitherBoss.class && control == FlyingMoveControl.class && GROUND_METHODS.get(WitherBoss.class)) {
            FlyingMoveControl flying = (FlyingMoveControl) mob.getMoveControl();
            return flying.maxTurn == 10 && !flying.hoversInPlace && mob.getNavigation().getClass() == FlyingPathNavigation.class
                && !mob.isSleeping() && !mob.isInWater() && !mob.isInLava() && !mob.onClimbable()
                ? Physics.WITHER : Physics.UNSUPPORTED;
        }
        if (mob.getClass() == Phantom.class && control == Phantom.PhantomMoveControl.class
            && mob.getNavigation().getClass() == GroundPathNavigation.class && !mob.isSleeping()
            && !mob.isInWater() && !mob.isInLava()) return Physics.PHANTOM;
        if (mob.getClass() == Ghast.class && control == Ghast.GhastMoveControl.class
            && mob.getNavigation().getClass() == GroundPathNavigation.class && !mob.isSleeping()
            && !mob.isInWater() && !mob.isInLava()) return Physics.GHAST;
        if (mob.isNoGravity()) return Physics.UNSUPPORTED;
        // Pufferfish adds native contact effects and puff geometry, but shares
        // AbstractFish's controller/travel and WaterAnimal's no-current-push rule.
        boolean puffer = mob.getClass() == Pufferfish.class && ((Pufferfish) mob).getPuffState() >= 0
            && ((Pufferfish) mob).getPuffState() <= 2;
        if ((mob.getClass() == Cod.class || mob.getClass() == Salmon.class || mob.getClass() == TropicalFish.class || puffer)
            && control == AbstractFish.FishMoveControl.class && mob.isInWater() && mob.isEyeInFluid(FluidTags.WATER))
            return Physics.FISH;
        if ((mob.getClass() == Tadpole.class || mob.getClass() == Dolphin.class) && control == SmoothSwimmingMoveControl.class
            && mob.getNavigation().getClass() == WaterBoundPathNavigation.class
            && mob.isInWater() && mob.isEyeInFluid(FluidTags.WATER) && !mob.isInLava()
            && smoothSwimParameters((SmoothSwimmingMoveControl) mob.getMoveControl()))
            return mob.getClass() == Dolphin.class ? Physics.DOLPHIN : Physics.TADPOLE;
        if (mob.getClass() == Turtle.class && control == Turtle.TurtleMoveControl.class
            && mob.getNavigation().getClass() == Turtle.TurtlePathNavigation.class
            && mob.isInWater() && mob.isEyeInFluid(FluidTags.WATER) && !mob.isInLava()) return Physics.TURTLE;
        if ((mob.getClass() == Guardian.class || mob.getClass() == ElderGuardian.class) && control == Guardian.GuardianMoveControl.class
            && mob.getNavigation().getClass() == WaterBoundPathNavigation.class && mob.getLookControl().getClass() == LookControl.class
            && mob.isInWater() && mob.isEyeInFluid(FluidTags.WATER) && !mob.isInLava()) return Physics.GUARDIAN;
        if (mob.getClass() == Frog.class && control == SmoothSwimmingMoveControl.class
            && mob.getNavigation().getClass() == Frog.FrogPathNavigation.class && mob.getPose() == Pose.STANDING
            && mob.isInWater() && mob.isEyeInFluid(FluidTags.WATER) && !mob.isInLava()
            && smoothSwimParameters((SmoothSwimmingMoveControl) mob.getMoveControl())) return Physics.FROG;
        if (mob.getClass() == Axolotl.class && control == Axolotl.AxolotlMoveControl.class
            && mob.getNavigation().getClass() == AmphibiousPathNavigation.class
            && mob.isInWater() && mob.isEyeInFluid(FluidTags.WATER) && !mob.isInLava()
            && !mob.isSleeping() && !((Axolotl) mob).isPlayingDead()) {
            SmoothSwimmingMoveControl swim = (SmoothSwimmingMoveControl) mob.getMoveControl();
            if (swim.maxTurnX == 85 && swim.maxTurnY == 10 && swim.inWaterSpeedModifier == 0.1F
                && swim.outsideWaterSpeedModifier == 0.5F && !swim.applyGravity) return Physics.AXOLOTL;
        }
        if (!GROUND_METHODS.get(mob.getClass())) return Physics.UNSUPPORTED;
        if (control == MoveControl.class && !(mob instanceof Drowned)
            && mob.getClass() != Slime.class && mob.getClass() != MagmaCube.class && mob.getClass() != Rabbit.class
            && mob.getClass() != Parrot.class && mob.getClass() != Panda.class && mob.getClass() != Camel.class && mob.getClass() != Sniffer.class)
            return Physics.ORDINARY;
        if (mob.hasEffect(MobEffects.LEVITATION) || mob.hasEffect(MobEffects.SLOW_FALLING)) return Physics.UNSUPPORTED;
        if ((mob.getClass() == Slime.class || mob.getClass() == MagmaCube.class) && control == Slime.SlimeMoveControl.class
            && mob.getJumpControl().getClass() == JumpControl.class
            && !mob.isInWater() && !mob.isInLava() && !mob.onClimbable()) {
            if (mob.getClass() == Slime.class) return Physics.SLIME;
            return boundedHopRise(jumpPower(mob, mob.position()), mob.getGravity()) > 0 ? Physics.MAGMA : Physics.UNSUPPORTED;
        }
        if (mob.getClass() == Rabbit.class && control == Rabbit.RabbitMoveControl.class
            && mob.getJumpControl().getClass() == Rabbit.RabbitJumpControl.class
            && !mob.isInWater() && !mob.isInLava() && !mob.onClimbable()) {
            Rabbit rabbit = (Rabbit) mob;
            if (rabbit.getVariant() == Rabbit.Variant.EVIL && rabbit.getTarget() != null
                && rabbit.distanceToSqr(rabbit.getTarget()) < 16) return Physics.UNSUPPORTED;
            return boundedHopRise(jumpPower(mob, mob.position()), mob.getGravity()) > 0 ? Physics.RABBIT : Physics.UNSUPPORTED;
        }
        if (mob.getClass() == Sniffer.class && control == MoveControl.class
            && mob.getJumpControl().getClass() == JumpControl.class
            && mob.getNavigation().getClass() == GroundPathNavigation.class
            && !mob.isInWater() && !mob.isInLava() && !mob.onClimbable() && !mob.isSleeping()) return Physics.SNIFFER;
        if (mob.getClass() == Camel.class && control == Camel.CamelMoveControl.class
            && mob.getJumpControl().getClass() == JumpControl.class
            && mob.getNavigation().getClass() == GroundPathNavigation.class
            && mob.getPose() == Pose.STANDING && !((Camel) mob).refuseToMove() && !((Camel) mob).isImmobile()
            && !mob.isInWater() && !mob.isInLava() && !mob.onClimbable() && !mob.isSleeping()) return Physics.CAMEL;
        if (mob.getClass() == Panda.class && control == Panda.PandaMoveControl.class
            && mob.getJumpControl().getClass() == JumpControl.class
            && mob.getNavigation().getClass() == GroundPathNavigation.class
            && !mob.isInWater() && !mob.isInLava() && !mob.onClimbable() && !mob.isSleeping()
            && ((Panda) mob).canPerformAction()) return Physics.PANDA;
        if (mob.getClass() == Fox.class && control == Fox.FoxMoveControl.class) {
            Fox fox = (Fox) mob;
            // Exact Fox.canMove predicate; do not clear its native state flags.
            return !fox.isSleeping() && !fox.isSitting() && !fox.isFaceplanted() ? Physics.FOX : Physics.UNSUPPORTED;
        }
        if (mob.getClass() == Drowned.class && control == Drowned.DrownedMoveControl.class
            && !mob.isInWater() && !mob.isInLava()) return Physics.DROWNED;
        if (mob.getClass() == Drowned.class && control == Drowned.DrownedMoveControl.class && mob.isInWater()
            && mob.isEyeInFluid(FluidTags.WATER) && !mob.isInLava() && !mob.isSwimming()
            && ((Drowned) mob).wantsToSwim() && mob.getNavigation().getClass() == GroundPathNavigation.class
            && mob.canSwimInFluidType(NeoForgeMod.WATER_TYPE.value())) return Physics.DROWNED_SWIM;
        if (mob.getClass() == Drowned.class && control == Drowned.DrownedMoveControl.class && mob.isInWater()
            && mob.isEyeInFluid(FluidTags.WATER) && !mob.isInLava() && !mob.isSleeping() && !mob.onClimbable()
            && !mob.isSwimming() && !((Drowned) mob).wantsToSwim()
            && mob.getNavigation().getClass() == GroundPathNavigation.class
            && mob.canSwimInFluidType(NeoForgeMod.WATER_TYPE.value())) return Physics.DROWNED_WATER;
        return Physics.UNSUPPORTED;
    }

    private void requireMode() {
        if (movementFailure != null) throw movementFailure;
        if (mob.isRemoved() || !mob.isAlive() || mob.level() != level) throw error("route_body_changed");
        if (puffState >= 0 && (((Pufferfish) mob).getPuffState() != puffState || mob.getPose() != puffPose
            || mob.getBbWidth() != puffWidth || mob.getBbHeight() != puffHeight || mob.getEyeHeight() != puffEyeHeight))
            throw error("route_pufferfish_body_changed");
        if (physics(mob) != physics || mob.getMoveControl() != controller) throw error("route_physics_changed");
        if (physics == Physics.SNIFFER && ((Sniffer) mob).getState() != snifferState) throw error("route_sniffer_state_changed");
        if (groundWrapper(physics) && (mob.isBaby() != groundBaby || mob.getPose() != groundPose
            || mob.getNavigation() != groundNavigation || mob.getBbWidth() != groundWidth
            || mob.getBbHeight() != groundHeight || mob.getEyeHeight() != groundEyeHeight))
            throw error(physics == Physics.PANDA ? "route_panda_body_changed" : physics == Physics.CAMEL ? "route_camel_body_changed" : "route_sniffer_body_changed");
        if (slimeHopper(physics) && ((Slime) mob).getSize() != slimeSize) throw error("route_hop_size_changed");
        checkDryVolume(mob.getBoundingBox());
        if (flightMode(physics) && (mob.getBbWidth() != flightWidth || mob.getBbHeight() != flightHeight
            || mob.getEyeHeight() != flightEyeHeight)) throw error("route_flight_body_changed");
        if (physics == Physics.WITHER) {
            WitherBoss wither = (WitherBoss) mob;
            if ((!mob.isNoAi() && !witherTravelTick) || mob.getNavigation() != witherNavigation
                || witherNavigation.getPath() != null || witherNavigation.canFloat() != witherCanFloat
                || mob.getTarget() != witherTarget || mob.getPose() != witherPose
                || wither.getInvulnerableTicks() != witherInvulnerableTicks || wither.isPowered() != witherPowered
                || mob.stuckSpeedMultiplier.lengthSqr() > 1e-7) throw error("route_wither_state_changed");
            for (int head = 0; head < 3; head++) {
                int id = wither.getAlternativeTarget(head);
                if (id != witherHeadIds[head] || (id > 0 ? level.getEntity(id) : null) != witherHeadEntities[head])
                    throw error("route_wither_target_changed");
            }
        }
        if (physics == Physics.PHANTOM && ((!mob.isNoAi() && !phantomTravelTick) || mob.getNavigation() != phantomNavigation
            || phantomNavigation.getPath() != null || phantomNavigation.canFloat() != phantomCanFloat || mob.getTarget() != phantomTarget
            || !((Phantom) mob).moveTargetPoint.equals(phantomOriginalPoint) || ((Phantom) mob).getPhantomSize() != phantomSize
            || mob.isNoGravity() != phantomNoGravity || mob.getPose() != phantomPose || mob.stuckSpeedMultiplier.lengthSqr() > 1e-7))
            throw error("route_phantom_state_changed");
        if (physics == Physics.GHAST && ((!mob.isNoAi() && !ghastTravelTick) || mob.getNavigation() != ghastNavigation
            || ghastNavigation.getPath() != null || ghastNavigation.canFloat() != ghastCanFloat || mob.getTarget() != ghastTarget
            || ((Ghast) mob).isCharging() != ghastCharging || mob.isNoGravity() != ghastNoGravity || mob.getPose() != ghastPose
            || mob.stuckSpeedMultiplier.lengthSqr() > 1e-7)) throw error("route_ghast_state_changed");
        if (hoverMode(physics) && ((!mob.isNoAi() && !hoverTravelTick) || mob.getNavigation() != hoverNavigation
            || hoverNavigation.getPath() != null || mob.getPose() != hoverPose || mob.stuckSpeedMultiplier.lengthSqr() > 1e-7))
            throw error(physics == Physics.BEE ? "route_bee_state_changed" : "route_allay_state_changed");
        if (physics == Physics.BEE && mob.isBaby() != beeBaby) throw error("route_bee_age_changed");
        if (physics == Physics.RABBIT) {
            Rabbit rabbit = (Rabbit) mob;
            if (rabbit.getVariant() != rabbitVariant || rabbit.isBaby() != rabbitBaby
                || rabbit.getBbWidth() != rabbitWidth || rabbit.getBbHeight() != rabbitHeight)
                throw error("route_rabbit_body_changed");
        }
        if (drownedWaterMode(physics)) {
            if (!mob.isNoAi() && !drownedTravelTick || mob.getNavigation().getPath() != null
                || mob.getNavigation() != drownedNavigation || drownedNavigation.canFloat() != drownedCanFloat
                || mob.getTarget() != drownedTarget || mob.getPose() != drownedPose
                || mob.getBbWidth() != drownedWidth || mob.getBbHeight() != drownedHeight || mob.getEyeHeight() != drownedEyeHeight)
                throw error(physics == Physics.DROWNED_SWIM ? "route_drowned_swim_state_changed" : "route_drowned_water_state_changed");
            if (physics == Physics.DROWNED_SWIM && (((Drowned) mob).searchingForLand != drownedSearching
                || mob.stuckSpeedMultiplier.lengthSqr() > 1e-7)) throw error("route_drowned_swim_state_changed");
            requireSubmerged(mob.getBoundingBox());
        }
        if (smoothSwimmer(physics)) {
            if ((!mob.isNoAi() && !smoothTravelTick) || mob.getNavigation() != smoothNavigation
                || smoothNavigation.getPath() != null || mob.getTarget() != smoothTarget || mob.getPose() != smoothPose
                || mob.getBbWidth() != smoothWidth || mob.getBbHeight() != smoothHeight || mob.getEyeHeight() != smoothEyeHeight)
                throw error(smoothReason("body_changed"));
            if (physics == Physics.AXOLOTL && (mob.isBaby() != axolotlBaby
                || smoothNavigation.canFloat() != axolotlCanFloat || ((Axolotl) mob).isPlayingDead()))
                throw error("route_axolotl_state_changed");
            if (physics == Physics.FROG && smoothNavigation.canFloat() != frogCanFloat) throw error("route_frog_state_changed");
            if ((physics == Physics.DOLPHIN || physics == Physics.AXOLOTL || physics == Physics.FROG) && mob.stuckSpeedMultiplier.lengthSqr() > 1e-7)
                throw error(smoothReason("stuck_pending"));
            requireSubmerged(mob.getBoundingBox());
        }
        if (physics == Physics.TURTLE) {
            Turtle turtle = (Turtle) mob;
            if ((!mob.isNoAi() && !turtleTravelTick) || mob.getNavigation() != turtleNavigation || turtleNavigation.getPath() != null
                || turtleNavigation.canFloat() != turtleCanFloat || mob.getTarget() != turtleTarget
                || !turtle.getHomePos().equals(turtleHome) || turtle.isGoingHome() != turtleGoingHome
                || mob.isBaby() != turtleBaby || mob.getPose() != turtlePose
                || mob.getBbWidth() != turtleWidth || mob.getBbHeight() != turtleHeight || mob.getEyeHeight() != turtleEyeHeight
                || mob.stuckSpeedMultiplier.lengthSqr() > 1e-7) throw error("route_turtle_state_changed");
            requireSubmerged(mob.getBoundingBox());
        }
        if (physics == Physics.GUARDIAN) {
            Guardian guardian = (Guardian) mob;
            if ((!mob.isNoAi() && !guardianTravelTick) || mob.getNavigation() != guardianNavigation || guardianNavigation.getPath() != null
                || guardianNavigation.canFloat() != guardianCanFloat || mob.getLookControl() != guardianLook || mob.getTarget() != guardianTarget
                || guardian.hasActiveAttackTarget() != guardianBeam || guardian.isMoving() != guardianMoving || mob.getPose() != guardianPose
                || mob.getBbWidth() != guardianWidth || mob.getBbHeight() != guardianHeight || mob.getEyeHeight() != guardianEyeHeight
                || mob.stuckSpeedMultiplier.lengthSqr() > 1e-7) throw error("route_guardian_state_changed");
            requireSubmerged(mob.getBoundingBox());
        }
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
            if (physics == Physics.DROWNED_WATER && drownedPolicyVolume != null) {
                AABB cell = new AABB(pos);
                if (!cell.intersects(drownedPolicyVolume) && !cell.intersects(drownedInitialVolume))
                    throw error("route_drowned_water_leaves_selected_cells");
            }
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

    private AABB flightVolume(AABB bounds) {
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX,
            bounds.maxY + Math.max(0, mob.getEyeHeight() - mob.getBbHeight()), bounds.maxZ);
    }

    private void requireFlightVolume(AABB bodyBounds) {
        AABB volume = flightVolume(bodyBounds);
        checkVolume(volume);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(volume.minX, volume.minY, volume.minZ),
            BlockPos.containing(volume.maxX - EPS, volume.maxY - EPS, volume.maxZ - EPS)))
            if (!level.getFluidState(pos).isEmpty()) throw error("route_flight_requires_dry_volume");
        if (!level.noCollision(mob, volume)) throw error("route_flight_volume_obstructed");
        if (hoverMode(physics) || nativeFlyingMob(physics) || physics == Physics.WITHER) {
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(volume.minX, volume.minY, volume.minZ),
                BlockPos.containing(volume.maxX - EPS, volume.maxY - EPS, volume.maxZ - EPS))) {
                BlockState state = level.getBlockState(pos);
                if ((physics == Physics.BEE || physics == Physics.WITHER) && state.is(net.minecraft.tags.BlockTags.CLIMBABLE))
                    throw error(physics == Physics.WITHER ? "route_wither_climb_pending" : "route_bee_climb_pending");
                if (!ordinaryFlightInsideBlock(state.getBlock()))
                    throw error(nativeFlyingMob(physics) ? flyingMobError("block_effect_unsupported") : physics == Physics.WITHER ? "route_wither_block_effect_unsupported" : physics == Physics.BEE ? "route_bee_block_effect_unsupported" : "route_allay_block_effect_unsupported");
            }
        }
    }

    private static boolean ordinaryFlightInsideBlock(Block block) {
        // entityInside is protected on BlockBehaviour; inspect declarations,
        // not public-method lookup, and never invoke a callback speculatively.
        for (Class<?> type = block.getClass(); type != null; type = type.getSuperclass()) {
            try {
                type.getDeclaredMethod("entityInside", BlockState.class, net.minecraft.world.level.Level.class, BlockPos.class, Entity.class);
                return type == net.minecraft.world.level.block.state.BlockBehaviour.class;
            } catch (NoSuchMethodException ignored) { }
        }
        return false;
    }

    private void checkDryVolume(AABB bounds) {
        if (flightMode(physics)) { requireFlightVolume(bounds); return; }
        if ((physics == Physics.DROWNED || groundWrapper(physics) || slimeHopper(physics) || physics == Physics.RABBIT) && hasFluid(bounds))
            throw error(groundWrapper(physics) ? (physics == Physics.PANDA ? "route_panda_requires_dry_ground" : physics == Physics.CAMEL ? "route_camel_requires_dry_ground" : "route_sniffer_requires_dry_ground")
                : physics == Physics.DROWNED ? "route_drowned_requires_dry_ground" : "route_hop_requires_dry_ground");
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
            AABB checked = clipped ? sweep : sweep.expandTowards(0, route.mob.maxUpStep(), 0);
            route.checkVolume(flightMode(route.physics) ? route.flightVolume(checked)
                : (drownedWaterMode(route.physics) || smoothSwimmer(route.physics) || route.physics == Physics.GUARDIAN || route.physics == Physics.TURTLE) ? route.fishVolume(checked) : checked);
            if (clipped) {
                Vec3 next = entity.position().add(delta);
                if (!route.inside(next)) throw error("route_leaves_body_box");
                if (!route.withinEdge(next)) throw error("route_leaves_selected_edge");
                route.checkDryVolume(sweep);
                if (fishMode(route.physics) || route.physics == Physics.DROWNED_WATER) route.requireSubmerged(sweep);
                if ((drownedWaterMode(route.physics) || smoothSwimmer(route.physics) || route.physics == Physics.GUARDIAN || route.physics == Physics.TURTLE) && !route.level.noCollision(route.mob, route.fishVolume(sweep)))
                    throw error(route.physics == Physics.TURTLE ? "route_turtle_volume_obstructed" : route.physics == Physics.GUARDIAN ? "route_guardian_volume_obstructed"
                        : smoothSwimmer(route.physics) ? route.smoothReason("volume_obstructed")
                        : route.physics == Physics.DROWNED_SWIM ? "route_drowned_swim_volume_obstructed" : "route_drowned_water_volume_obstructed");
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

    private boolean ownsPufferfishCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller
            && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
    }

    /** Before native puff refreshDimensions, which may reposition through setPos rather than move. */
    public static void beforePufferfishSizeChange(Pufferfish fish) {
        if (fish.level().isClientSide || fish.level().getServer() == null || !fish.level().getServer().isSameThread()) return;
        ScriptNavigation route = OWNED.get(fish);
        if (route == null || !route.active || route.physics != Physics.FISH || route.puffState < 0
            || fish.getPuffState() == route.puffState) return;
        RuntimeException failure = error("route_pufferfish_puff_changed");
        if (route.movementFailure == null) route.movementFailure = failure;
        try {
            // Expiry may already clean up this route. Never clear a replacement
            // body/controller/lease while allowing native metadata to proceed.
            if (fish.level() == route.level && route.owner.vehicleLeaseActive(route.lease)
                && route.ownsPufferfishCleanup()) route.stop();
        } catch (RuntimeException cleanup) {
            if (cleanup != route.movementFailure) route.movementFailure.addSuppressed(cleanup);
        } finally {
            OWNED.remove(fish, route);
            route.active = false;
        }
        // The next action tick reports the retained failure. Native puff/size
        // effects now proceed outside route ownership; no rollback or retry.
    }

    /** Age metadata may grow/reposition a Bee directly, outside Entity.move. */
    public static void beforeBeeAgeDimensions(Bee bee) {
        if (bee.getClass() != Bee.class || bee.level().isClientSide || bee.level().getServer() == null
            || !bee.level().getServer().isSameThread()) return;
        ScriptNavigation route = OWNED.get(bee);
        if (route == null || !route.active || route.physics != Physics.BEE || bee.isBaby() == route.beeBaby) return;
        RuntimeException failure = error("route_bee_age_changed");
        if (route.movementFailure == null) route.movementFailure = failure;
        try {
            if (bee.level() == route.level && route.owner.vehicleLeaseActive(route.lease) && route.ownsHoverCleanup()) route.stop();
        } catch (RuntimeException | LinkageError cleanup) {
            if (cleanup != route.movementFailure) route.movementFailure.addSuppressed(cleanup);
        } finally {
            OWNED.remove(bee, route);
            route.active = false;
        }
        // Native age, dimensions and any direct reposition proceed unchanged.
    }

    /** Native age growth can reposition directly; end only this captured Axolotl route first. */
    public static void beforeAxolotlAgeDimensions(Axolotl axolotl) {
        if (axolotl.getClass() != Axolotl.class || axolotl.level().isClientSide || axolotl.level().getServer() == null
            || !axolotl.level().getServer().isSameThread()) return;
        ScriptNavigation route = OWNED.get(axolotl);
        if (route == null || !route.active || route.physics != Physics.AXOLOTL || axolotl.isBaby() == route.axolotlBaby) return;
        RuntimeException failure = error("route_axolotl_age_changed");
        if (route.movementFailure == null) route.movementFailure = failure;
        try {
            if (axolotl.level() == route.level && route.owner.vehicleLeaseActive(route.lease) && route.ownsSmoothSwimCleanup()) route.stop();
        } catch (RuntimeException | LinkageError cleanup) {
            if (cleanup != route.movementFailure) route.movementFailure.addSuppressed(cleanup);
        } finally {
            OWNED.remove(axolotl, route);
            route.active = false;
        }
        // Native age, dimensions and any direct reposition proceed unchanged.
    }

    private boolean ownsGroundCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller
            && mob.getNavigation() == groundNavigation
            && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
    }

    private static boolean groundWrapper(Physics mode) { return mode == Physics.PANDA || mode == Physics.CAMEL || mode == Physics.SNIFFER; }

    /** End captured wrapper ownership before native state/age/pose refresh can reposition it. */
    public static void beforeGroundWrapperDimensions(Mob body) {
        if ((body.getClass() != Panda.class && body.getClass() != Camel.class && body.getClass() != Sniffer.class) || body.level().isClientSide
            || body.level().getServer() == null || !body.level().getServer().isSameThread()) return;
        ScriptNavigation route = OWNED.get(body);
        if (route == null || !route.active || !groundWrapper(route.physics)
            || body.isBaby() == route.groundBaby && body.getPose() == route.groundPose
                && (route.physics != Physics.SNIFFER || ((Sniffer) body).getState() == route.snifferState)) return;
        if (route.movementFailure == null) route.movementFailure = error(route.physics == Physics.PANDA
            ? "route_panda_age_changed" : route.physics == Physics.CAMEL ? "route_camel_dimensions_changed" : "route_sniffer_dimensions_changed");
        try {
            if (body.level() == route.level && route.owner.vehicleLeaseActive(route.lease) && route.ownsGroundCleanup()) route.stop();
        } catch (RuntimeException | LinkageError cleanup) {
            if (cleanup != route.movementFailure) route.movementFailure.addSuppressed(cleanup);
        } finally {
            OWNED.remove(body, route);
            route.active = false;
        }
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

    private static boolean fishMode(Physics mode) { return mode == Physics.FISH || mode == Physics.DROWNED_SWIM || mode == Physics.GUARDIAN || mode == Physics.TURTLE || smoothSwimmer(mode); }

    private static boolean drownedWaterMode(Physics mode) { return mode == Physics.DROWNED_WATER || mode == Physics.DROWNED_SWIM; }

    private static boolean smoothSwimmer(Physics mode) { return mode == Physics.TADPOLE || mode == Physics.DOLPHIN || mode == Physics.AXOLOTL || mode == Physics.FROG; }

    private String smoothReason(String suffix) {
        return (physics == Physics.FROG ? "route_frog_" : physics == Physics.AXOLOTL ? "route_axolotl_"
            : physics == Physics.DOLPHIN ? "route_dolphin_" : "route_tadpole_") + suffix;
    }

    private static boolean smoothSwimParameters(SmoothSwimmingMoveControl control) {
        return control.maxTurnX == 85 && control.maxTurnY == 10 && control.inWaterSpeedModifier == 0.02F
            && control.outsideWaterSpeedModifier == 0.1F && control.applyGravity;
    }

    /** The real navigation remains idle everywhere except this exact controller query. */
    public static boolean selectedSmoothSwimControl(Mob body, MoveControl control, PathNavigation navigation) {
        if (body.level().isClientSide) return false;
        ScriptNavigation route = OWNED.get(body);
        if (route == null || !route.active || !smoothSwimmer(route.physics) || !route.smoothControlTick
            || route.controller != control || route.smoothNavigation != navigation) return false;
        try {
            route.requireThread();
            if (!route.owner.vehicleLeaseActive(route.lease)) throw error("script_no_longer_controls_body");
            route.requireMode();
            return true;
        } catch (RuntimeException failure) { route.rejectMovement(failure); return false; }
    }

    private boolean ownsSmoothSwimCleanup() {
        return mob.level() == level && !mob.isRemoved() && mob.getMoveControl() == controller
            && mob.getNavigation() == smoothNavigation && (OWNED.get(mob) == null || OWNED.get(mob) == this)
            && (owner.currentScriptId() == null || lease.equals(owner.currentScriptId()));
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
        // Rabbit's .5 branch is a candidate maximum, not its current post-controller power.
        float multiplier = mob.getClass() == Rabbit.class ? 0.5F / 0.42F : 1;
        float power = (float) mob.getAttributeValue(Attributes.JUMP_STRENGTH) * multiplier * factor + mob.getJumpBoostPower();
        // MagmaCube.jumpFromGround adds this after native getJumpPower, in float precision.
        if (mob.getClass() == MagmaCube.class) power += (float) ((MagmaCube) mob).getSize() * 0.1F;
        return power;
    }

    private static double jumpRise(Mob mob) {
        if (mob.hasEffect(MobEffects.LEVITATION)) return 0;
        double power = jumpPower(mob, mob.position());
        return mob.getClass() == MagmaCube.class || mob.getClass() == Rabbit.class
            ? boundedHopRise(power, mob.getGravity()) : jumpRise(power, mob.getGravity());
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

    /** Reject, rather than truncate, reviewed MagmaCube/Rabbit hops beyond the finite envelope. */
    private static double boundedHopRise(double power, double gravity) {
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
    private static double reachEnvelope(double speed, double initialVelocity, double power, double gravity, double width, boolean sprint, boolean drowned, boolean sniffer) {
        speed *= sprint ? 1.3 : 1;
        // Native forward input equals speed. For supported friction [.6, 1], use
        // the largest ground acceleration and drag as a conservative 12-tick run-up bound.
        double acceleration = speed * Math.min(1, speed) * 1.001;
        double horizontal = initialVelocity;
        for (int i = 0; i < 12; i++) horizontal = (horizontal * 0.98 + acceleration) * 0.91;
        // Sniffer's conditional kick precedes this tick's travel acceleration.
        // Bound both branches; do not add an unconditional impulse to a run-up.
        horizontal = sniffer
            ? Math.max(horizontal * 0.98 + (sprint ? 0.2 : 0), 0.1 + (double) 0.1F) + acceleration
            : horizontal * 0.98 + acceleration + (sprint ? 0.2 : 0);
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
