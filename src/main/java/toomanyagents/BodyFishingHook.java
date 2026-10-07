package toomanyagents;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Native fishing mechanics with the visible body used for collision and rendering. */
public final class BodyFishingHook extends FishingHook {
    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, "too_many_agents");
    static final DeferredHolder<EntityType<?>, EntityType<BodyFishingHook>> TYPE = ENTITIES.register("fishing_bobber",
        () -> EntityType.Builder.<BodyFishingHook>of(BodyFishingHook::new, MobCategory.MISC)
            .sized(0.25F, 0.25F).clientTrackingRange(4).updateInterval(5).noSave().noSummon()
            .build("too_many_agents:fishing_bobber"));

    private Mob body;
    private Player visualOwner;

    private BodyFishingHook(EntityType<? extends FishingHook> type, Level level) { super(type, level); }

    BodyFishingHook(AgentHands hands, Mob body, int luck, int lure) {
        super(TYPE.get(), body.level(), luck, lure);
        this.body = body;
        setOwner(hands);
        var origin = body.getEyePosition().add(body.getLookAngle().scale(0.3));
        setPos(origin.x, origin.y, origin.z);
        // Use Minecraft's projectile launch and subsequent FishingHook physics.
        shootFromRotation(hands, hands.getXRot(), hands.getYRot(), 0, 1.1F, 0.5F);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        var visible = level().isClientSide ? getOwner() : body;
        return target != visible && (visible == null || !target.isPassengerOfSameVehicle(visible)) && super.canHitEntity(target);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity tracker) {
        return new ClientboundAddEntityPacket(this, tracker, body == null ? getId() : body.getId());
    }

    @Override
    public Player getPlayerOwner() {
        if (!level().isClientSide) return super.getPlayerOwner();
        if (!(getOwner() instanceof LivingEntity visible)) return null;
        return clientOwner(visible);
    }

    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private Player clientOwner(LivingEntity visible) {
        // A render-only adapter: never added to the world, player list or network.
        if (visualOwner == null) visualOwner = new net.minecraft.client.player.RemotePlayer(
            (net.minecraft.client.multiplayer.ClientLevel) level(), new GameProfile(visible.getUUID(), "[Body fishing]"));
        double eyeOffset = visible.getEyeHeight() - visualOwner.getEyeHeight();
        visualOwner.setPos(visible.getX(), visible.getY() + eyeOffset, visible.getZ());
        visualOwner.xo = visible.xo;
        visualOwner.yo = visible.yo + eyeOffset;
        visualOwner.zo = visible.zo;
        visualOwner.setYRot(visible.getYRot());
        visualOwner.setXRot(visible.getXRot());
        visualOwner.yBodyRot = visible.yBodyRot;
        visualOwner.yBodyRotO = visible.yBodyRotO;
        visualOwner.attackAnim = visible.attackAnim;
        visualOwner.oAttackAnim = visible.oAttackAnim;
        visualOwner.setShiftKeyDown(visible.isShiftKeyDown());
        visualOwner.setMainArm(visible.getMainArm());
        visualOwner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, visible.getMainHandItem());
        visualOwner.fishing = this;
        return visualOwner;
    }
}
