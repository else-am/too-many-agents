package toomanyagents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Server-thread input bridge. Native vehicle ticks, passengers and momentum stay native. */
public final class ScriptVehicleControls {
    private static final Map<Entity, ScriptVehicleControls> OWNED = new IdentityHashMap<>();
    private final AgentActions owner;
    private final Mob body;
    private final AgentHands hands;
    private final Entity vehicle;
    private final ServerLevel world;
    private final Supplier<BodyBox> box;
    private final String lease;
    private float left, forward;
    private boolean jump, previousJump, sprint;
    private int chargeTicks;
    private float charge;

    ScriptVehicleControls(AgentActions owner, Entity vehicle, Supplier<BodyBox> box, String lease) {
        this.owner = owner; this.body = owner.mob; this.hands = owner.hands;
        this.vehicle = vehicle; this.box = box; this.lease = lease;
        this.world = (ServerLevel) body.level();
        if (!owner.vehicleLeaseActive(lease)) throw error("script_no_longer_controls_body");
        requireEligible(body, hands, vehicle);
        if (OWNED.containsKey(vehicle)) throw error("vehicle_already_controlled");
        if (vehicle instanceof Mob mount) {
            mount.getNavigation().stop();
            mount.setJumping(false);
            mount.updateControlFlags();
        }
        OWNED.put(vehicle, this);
    }

    static void requireEligible(Mob body, AgentHands hands, Entity vehicle) {
        if (vehicle == null) throw error("body_not_mounted");
        if (vehicle.isRemoved() || !vehicle.isAlive() || vehicle.level() != body.level()
            || body.getVehicle() != vehicle || vehicle.getFirstPassenger() != body || !body.canControlVehicle())
            throw error("body_not_vehicle_controller");
        var controller = vehicle.getControllingPassenger();
        if (controller != null && controller != body) throw error("body_not_vehicle_controller");
        if (vehicle instanceof Llama) throw error("vehicle_not_natively_steerable");
        if (vehicle instanceof AbstractHorse horse) {
            if (!horse.isTamed() || horse.isBaby()) throw error("vehicle_not_tamed_adult");
            if (!horse.isSaddled()) throw error("vehicle_requires_saddle");
        } else if (vehicle instanceof Pig pig) {
            if (!pig.isSaddled()) throw error("vehicle_requires_saddle");
            if (!hands.isHolding(Items.CARROT_ON_A_STICK)) throw error("vehicle_requires_carrot_on_a_stick");
        } else if (vehicle instanceof Strider strider) {
            if (!strider.isSaddled()) throw error("vehicle_requires_saddle");
            if (!hands.isHolding(Items.WARPED_FUNGUS_ON_A_STICK)) throw error("vehicle_requires_warped_fungus_on_a_stick");
        } else if (!(vehicle instanceof AbstractMinecart cart && cart.canBeRidden())) {
            throw error("vehicle_controls_not_implemented_for_body");
        }
    }

    boolean matches(Entity current) { return current == vehicle; }

    void input(float left, float forward, boolean jump, boolean sprint) {
        this.left = left; this.forward = forward; this.jump = jump; this.sprint = sprint;
    }

    void poll() { current(vehicle); }

    void close() {
        if (!OWNED.remove(vehicle, this)) return;
        left = forward = 0; jump = previousJump = sprint = false; chargeTicks = 0; charge = 0;
        // Never clear a replacement rider's input or destroy the vehicle's coast.
        if (body.getVehicle() == vehicle && vehicle.getFirstPassenger() == body
            && (vehicle.getControllingPassenger() == null || vehicle.getControllingPassenger() == body)) {
            if (vehicle instanceof Mob mount) {
                mount.setXxa(0); mount.setYya(0); mount.setSpeed(0); mount.setZza(0); mount.setJumping(false);
            }
            if (vehicle instanceof AbstractHorse horse) horse.playerJumpPendingScale = 0;
            if (vehicle instanceof PlayerRideableJumping jumping) jumping.handleStopJump();
        }
        hands.xxa = 0; hands.yya = 0; hands.zza = 0; hands.setJumping(false); hands.setSprinting(false);
        hands.setDeltaMovement(Vec3.ZERO);
    }

    private static ScriptVehicleControls current(Entity vehicle) {
        if (vehicle.level().isClientSide) return null;
        ScriptVehicleControls controls = OWNED.get(vehicle);
        if (controls == null) return null;
        try {
            if (!controls.owner.vehicleLeaseActive(controls.lease)) { controls.close(); return null; }
            if (vehicle.level() != controls.world || controls.body.level() != controls.world)
                throw error("vehicle_control_world_changed");
            controls.hands.syncBody();
            requireEligible(controls.body, controls.hands, vehicle);
            return controls;
        } catch (RuntimeException failure) {
            controls.fail(failure);
            return null;
        }
    }

    // Cancel this rejected call only. Once ownership is removed, later native
    // movement is unguarded; a replacement rider must never inherit our fence.
    private static boolean sameRider(Entity vehicle, ScriptVehicleControls previous) {
        return previous != null && previous.body.level() == vehicle.level()
            && previous.body.getVehicle() == vehicle && vehicle.getFirstPassenger() == previous.body
            && (vehicle.getControllingPassenger() == null || vehicle.getControllingPassenger() == previous.body);
    }

    private void fail(RuntimeException failure) {
        // A physics callback must never throw through the world's entity tick.
        try { close(); }
        catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        try { owner.failVehicleControls(failure); }
        catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    }

    /** Replaces only the ordinary travel call in this owned mount's aiStep. */
    public static boolean travel(LivingEntity mount, Vec3 originalInput) {
        ScriptVehicleControls previous = mount.level().isClientSide ? null : OWNED.get(mount);
        ScriptVehicleControls controls = current(mount);
        if (controls == null) return sameRider(mount, previous);
        boolean noAi = mount instanceof Mob mob && mob.isNoAi();
        try {
            controls.prepareRider();
            controls.updateJump();
            if (mount instanceof Mob mob) mob.setNoAi(false);
            // All horse/camel/pig/strider input, speed, jump and fluid overrides
            // remain in the native ridden chain. AgentHands is not a passenger.
            mount.travelRidden(controls.hands, originalInput);
        } catch (RuntimeException failure) { controls.fail(failure); }
        finally { if (mount instanceof Mob mob) mob.setNoAi(noAi); }
        return true;
    }

    private void prepareRider() {
        hands.xxa = left; hands.yya = 0; hands.zza = forward;
        hands.setJumping(false); hands.setSprinting(sprint);
    }

    private void updateJump() {
        if (!(vehicle instanceof PlayerRideableJumping jumping)) return;
        // LocalPlayer.aiStep's hold/release charge, including its ten-tick reset.
        if (!jumping.canJump() || jumping.getJumpCooldown() != 0) {
            charge = 0; previousJump = jump; return;
        }
        if (chargeTicks < 0 && ++chargeTicks == 0) charge = 0;
        if (previousJump && !jump) {
            chargeTicks = -10;
            int strength = Mth.floor(charge * 100);
            jumping.onPlayerJump(strength);
            if (strength > 0) jumping.handleStartJump(strength);
        } else if (!previousJump && jump) {
            chargeTicks = 0; charge = 0;
        } else if (previousJump) {
            chargeTicks++;
            charge = chargeTicks < 10 ? chargeTicks * 0.1F : 0.8F + 2.0F / (chargeTicks - 9) * 0.1F;
        }
        previousJump = jump;
    }

    /** Input view only: the minecart's actual passenger list is never replaced. */
    public static Entity minecartInput(AbstractMinecart cart, Entity passenger) {
        ScriptVehicleControls controls = current(cart);
        if (controls == null) return passenger;
        try {
            controls.prepareRider();
            Mob body = controls.body;
            AgentHands input = controls.hands;
            BlockPos support = body.getBlockPosBelowThatAffectsMyMovement();
            if (!body.level().hasChunkAt(support)) throw error("vehicle_execution_boundary");
            float friction = body.level().getBlockState(support).getFriction(body.level(), support, body);
            float oldSpeed = body.getSpeed(), oldForward = body.zza;
            float acceleration;
            try {
                body.setSpeed((float) body.getAttributeValue(Attributes.MOVEMENT_SPEED));
                acceleration = body.getFrictionInfluencedSpeed(friction);
            } finally { body.setSpeed(oldSpeed); body.setZza(oldForward); }
            if (!Float.isFinite(acceleration) || !Float.isFinite(friction) || friction <= 0)
                throw error("vehicle_invalid_native_input");
            input.setDeltaMovement(Vec3.ZERO);
            // Native input rotation/normalization and rider friction; the rail
            // code alone decides whether/how much this motion pushes the cart.
            input.moveRelative(acceleration, new Vec3(controls.left * 0.98F, 0, controls.forward * 0.98F));
            float drag = body.onGround() ? friction * 0.91F : 0.91F;
            if (!body.shouldDiscardFriction()) input.setDeltaMovement(input.getDeltaMovement().scale(drag));
            return input;
        } catch (RuntimeException failure) { controls.fail(failure); return passenger; }
    }

    public static boolean allowMove(Entity vehicle, Vec3 delta) {
        ScriptVehicleControls previous = vehicle.level().isClientSide ? null : OWNED.get(vehicle);
        ScriptVehicleControls controls = current(vehicle);
        if (controls == null) return !sameRider(vehicle, previous);
        try { controls.checkBounds(delta, vehicle.getBoundingBox().expandTowards(delta)); return true; }
        catch (RuntimeException failure) { controls.fail(failure); return false; }
    }

    public static boolean allowRailTick(AbstractMinecart cart) {
        ScriptVehicleControls previous = cart.level().isClientSide ? null : OWNED.get(cart);
        ScriptVehicleControls controls = current(cart);
        if (controls == null) return !sameRider(cart, previous);
        try {
            // Native moveAlongTrack aligns within a rail cell and its adjacent
            // slope before/after Entity.move. Survey those adjustments too.
            double reach = Math.max(cart.getMaxSpeedWithRail(), cart.getDeltaMovement().horizontalDistance()) + 1;
            if (!Double.isFinite(reach) || reach > 8) throw error("vehicle_rail_sweep_limit");
            AABB sweep = cart.getBoundingBox().inflate(reach, 2, reach);
            for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) for (int y : new int[]{-1, 1})
                controls.checkBounds(new Vec3(x * reach, y * 2, z * reach), sweep);
            return true;
        } catch (RuntimeException failure) { controls.fail(failure); return false; }
    }

    private void checkBounds(Vec3 delta, AABB sweep) {
        ServerLevel level = (ServerLevel) vehicle.level();
        BodyBox limit = box.get();
        if (!Double.isFinite(delta.x) || !Double.isFinite(delta.y) || !Double.isFinite(delta.z)
            || sweep.getSize() > 32 || sweep.minY < level.getMinBuildHeight() || sweep.maxY > level.getMaxBuildHeight()
            || !level.hasChunksAt(BlockPos.containing(sweep.minX, sweep.minY, sweep.minZ),
                BlockPos.containing(sweep.maxX, sweep.maxY, sweep.maxZ))
            || !level.getWorldBorder().isWithinBounds(sweep)
            || limit != null && (!limit.dimension().equals(level.dimension().location().toString())
                || !limit.holds(body.position()) || !limit.holds(body.position().add(delta))))
            throw error("vehicle_execution_boundary");
    }

    private static IllegalStateException error(String message) { return new IllegalStateException(message); }
}
