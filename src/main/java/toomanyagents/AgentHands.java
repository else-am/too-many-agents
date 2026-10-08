package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.network.TextFilter;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player.BedSleepingProblem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Native player interactions for one visible body. Never added to the world or player list. */
public final class AgentHands extends FakePlayer {
    java.util.function.BiConsumer<Component, Boolean> messageSink;
    int forwardedTakes;
    Consumer<JsonObject> presentationSink;
    Consumer<net.minecraft.network.protocol.Packet<?>> soundSink;
    Consumer<net.minecraft.network.protocol.Packet<?>> blockEventSink;
    private JsonObject tablist = JsonState.object("header", "", "footer", "");
    private final Mob body;
    private final Supplier<BodyBox> bodyBox;
    private final EnumMap<EquipmentSlot, ItemStack> previousEquipment = new EnumMap<>(EquipmentSlot.class);
    private BlockPos miningPos;
    private BlockState miningState;
    private Direction miningFace;
    private float miningProgress;
    private int lastMiningTick = -1;
    private boolean miningNeedsStop;
    private int lastHandsTick = -1;
    private long nextMeleeAttackTick;
    private net.minecraft.world.entity.projectile.FishingHook scriptFishing;
    private net.minecraft.world.level.block.entity.SignBlockEntity editedSign;
    private long signEditorExpires, signEditorSequence;
    private InteractionHand nativeUseHand;
    private ItemStack creativeUseStack = ItemStack.EMPTY;
    private String nativeUseOutcome = "idle";
    private int nextMenuId;
    private BlockPos menuOrigin;
    private Entity menuEntity;
    private AbstractContainerMenu observedMenu;
    private long menuGeneration;
    private Component menuTitle;
    private int[] menuProperties = new int[0];
    private boolean merchantMetadataKnown;
    private int selectedTrade;
    private boolean closed;

    Mob visibleBody() { return body; }

    AgentHands(Mob body) { this(body, () -> null); }

    AgentHands(Mob body, Supplier<BodyBox> bodyBox) {
        super((ServerLevel) body.level(), new GameProfile(UUID.nameUUIDFromBytes(
            ("too_many_agents-hands:" + body.getUUID()).getBytes(StandardCharsets.UTF_8)), "[TooManyAgents]"));
        this.body = body;
        this.bodyBox = bodyBox;
        requireThread();
        syncBody();
        restore();
        observeMenu(getInventory().getDisplayName());
        save();
    }

    /** Observe only supported packets addressed to this body's no-op connection. */
    public void observePresentation(net.minecraft.network.protocol.Packet<?> packet) {
        if (!(packet instanceof net.minecraft.network.protocol.game.ClientboundTabListPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundClearTitlesPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEventPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket)) return;
        requireThread();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEventPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket) {
            if (blockEventSink != null) blockEventSink.accept(packet);
            return;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundSoundEntityPacket) {
            if (soundSink != null) soundSink.accept(packet);
            return;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundTabListPacket value) {
            var next = new JsonObject();
            next.add("header", componentJson(value.header()));
            next.add("footer", componentJson(value.footer()));
            if (next.toString().length() > 65536) throw error("tablist_too_large");
            tablist = next;
            return;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket value) {
            displayClientMessage(value.text(), true);
            return;
        }
        if (presentationSink == null) return;
        JsonObject event;
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket value) {
            event = JsonState.object("kind", "title", "type", "title");
            event.add("message", componentJson(value.text()));
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket value) {
            event = JsonState.object("kind", "title", "type", "subtitle");
            event.add("message", componentJson(value.text()));
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket value) {
            event = JsonState.object("kind", "title_times", "fadeIn", value.getFadeIn(),
                "stay", value.getStay(), "fadeOut", value.getFadeOut());
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundClearTitlesPacket) {
            event = JsonState.object("kind", "title_clear");
        } else return;
        presentationSink.accept(event);
    }

    private com.google.gson.JsonElement componentJson(Component component) {
        return ComponentSerialization.CODEC.encodeStart(
            level().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), component).getOrThrow();
    }

    @Override public void displayClientMessage(Component message, boolean actionBar) {
        if (messageSink != null) messageSink.accept(message, actionBar);
    }

    @Override public void sendSystemMessage(Component message, boolean overlay) {
        if (messageSink != null) messageSink.accept(message, overlay);
    }

    @Override public MinecraftServer getServer() { return serverLevel().getServer(); }

    // Dominant arm belongs to the visible body, not the Player-shaped proxy.
    @Override public net.minecraft.world.entity.HumanoidArm getMainArm() {
        return body == null ? super.getMainArm() : body.getMainArm();
    }

    // Native held-item use sees the real rider without adding a proxy passenger.
    @Override public Entity getVehicle() {
        return body == null ? super.getVehicle() : body.getVehicle();
    }

    @Override public Entity getControlledVehicle() {
        return body == null ? super.getControlledVehicle() : body.getControlledVehicle();
    }

    @Override public boolean isEyeInFluid(TagKey<Fluid> fluid) {
        return body == null ? super.isEyeInFluid(fluid) : body.isEyeInFluid(fluid);
    }

    void syncBody() {
        requireThread();
        if (closed || body.isRemoved() || !body.isAlive()) throw error("body_missing_or_unloaded");
        if (body.level() != level()) throw error("body_dimension_changed");
        reconcileNativeUse();
        // Vanilla reach/mining/projectiles use player eyes. Match the actual NPC viewpoint.
        setPos(body.getX(), body.getEyeY() - getEyeHeight(), body.getZ());
        setYRot(body.getViewYRot(1));
        setYHeadRot(body.getViewYRot(1));
        setYBodyRot(body.getYRot());
        setXRot(body.getXRot());
        setOnGround(body.onGround());
        setShiftKeyDown(body.isShiftKeyDown());
        var mode = BodySettings.mode(body.getPersistentData().getString("too_many_agents_mode")).creative
            ? GameType.CREATIVE : GameType.SURVIVAL;
        if (gameMode.getGameModeForPlayer() != mode) gameMode.changeGameModeForPlayer(mode);
        getAbilities().flying = false;
        syncEquipment();
    }

    /** Advance use/cooldowns, not movement, hunger, damage, player AI, or mining. */
    void tickHands() {
        syncBody();
        int tick = getServer().getTickCount();
        if (tick == lastHandsTick) return;
        lastHandsTick = tick;
        tickCount++;
        if (body.isSleeping() && level().isDay()) wakeBody();
        if (takeXpDelay > 0) takeXpDelay--;
        getCooldowns().tick();
        tickEffects();
        EnchantmentHelper.tickEffects(serverLevel(), this);
        if (isUsingItem()) {
            var held = getItemInHand(getUsedItemHand());
            if (CommonHooks.canContinueUsing(useItem, held)) useItem = held;
            if (held == useItem) updateUsingItem(useItem);
            else stopUsingItem();
        }
        if (containerMenu != inventoryMenu && !validMenu()) closeContainer();
        containerMenu.broadcastChanges();
        save();
    }

    JsonObject beginMine(BlockPos pos) { return beginMine(pos, null); }

    JsonObject beginMine(BlockPos pos, Direction face) {
        syncBody();
        cancelMine();
        var hit = checkedHit(pos, face);
        var state = serverLevel().getBlockState(pos);
        if (state.isAir()) throw error("mine_target_is_air");
        if (!isCreative() && state.getDestroySpeed(level(), pos) < 0) throw error("block_is_unbreakable");
        if (blockActionRestricted(level(), pos, gameMode.getGameModeForPlayer())) throw error("block_action_restricted");
        var event = CommonHooks.onLeftClickBlock(this, pos, hit.getDirection(), ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        if (event.isCanceled() || event.getUseItem().isFalse()) throw error("mining_denied_by_event");
        miningPos = pos.immutable();
        miningState = state;
        miningFace = hit.getDirection();
        miningProgress = 0;
        miningNeedsStop = false;
        lastMiningTick = getServer().getTickCount();
        if (!isCreative()) {
            EnchantmentHelper.onHitBlock(serverLevel(), getMainHandItem(), this, this, EquipmentSlot.MAINHAND,
                hit.getLocation(), state, slot -> onEquippedItemBroken(slot, EquipmentSlot.MAINHAND));
            if (!event.getUseBlock().isFalse()) state.attack(level(), pos, this);
        }
        body.swing(InteractionHand.MAIN_HAND);
        if (isCreative()) return finishMine();
        return advanceMine();
    }

    JsonObject tickMine() {
        syncBody();
        if (miningPos == null) throw error("no_active_mining");
        try {
            checkedHit(miningPos, miningFace);
            if (serverLevel().getBlockState(miningPos) != miningState) throw error("mining_target_changed");
            int tick = getServer().getTickCount();
            if (tick == lastMiningTick) return miningResult("mining");
            lastMiningTick = tick;
            body.swing(InteractionHand.MAIN_HAND);
            return advanceMine();
        } catch (RuntimeException failure) {
            cancelMine();
            throw failure;
        }
    }

    private JsonObject advanceMine() {
        if (serverLevel().getBlockState(miningPos) != miningState) {
            cancelMine();
            throw error("mining_target_changed");
        }
        float step = miningState.getDestroyProgress(this, level(), miningPos);
        // Zero-hardness blocks return positive infinity: vanilla treats that as an instant break.
        if (Float.isNaN(step) || step <= 0) {
            cancelMine();
            throw error("block_cannot_be_mined_with_current_tool");
        }
        miningProgress += step;
        if (miningProgress >= 1) return finishMine();
        miningNeedsStop = true;
        serverLevel().destroyBlockProgress(body.getId(), miningPos, Math.min(9, (int) (miningProgress * 10)));
        save();
        return miningResult("mining");
    }

    private JsonObject finishMine() {
        checkedHit(miningPos, miningFace);
        var pos = miningPos;
        var before = miningState;
        // destroyBlock implements NeoForge BreakEvent, harvest checks, durability, drops and enchantments.
        boolean accepted;
        try {
            if (miningNeedsStop) {
                var stopped = CommonHooks.onLeftClickBlock(this, pos, miningFace, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
                if (stopped.isCanceled() || stopped.getUseItem().isFalse()) throw error("mining_denied_by_event");
            }
            accepted = gameMode.destroyBlock(pos);
        }
        finally {
            serverLevel().destroyBlockProgress(body.getId(), pos, -1);
            miningPos = null;
            miningState = null;
            save();
        }
        if (!accepted || serverLevel().getBlockState(pos) == before) throw error("block_break_rejected");
        var result = status("completed");
        result.add("position", Observations.position(Vec3.atLowerCornerOf(pos)));
        result.addProperty("progress", 1);
        result.addProperty("face", miningFace.get3DDataValue());
        return result;
    }

    void cancelMine() {
        requireThread();
        if (miningPos == null) return;
        CommonHooks.onLeftClickBlock(this, miningPos, miningFace, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        serverLevel().destroyBlockProgress(body.getId(), miningPos, -1);
        miningPos = null;
        miningState = null;
        miningProgress = 0;
    }

    boolean blockReachable(BlockPos pos) { return blockReachable(pos, null); }

    boolean blockReachable(BlockPos pos, Direction face) {
        syncBody();
        try { checkedHit(pos, face); return true; }
        catch (IllegalStateException failure) { return false; }
    }

    boolean canReach(Entity target) {
        syncBody();
        return target != body && target.isAlive() && target.level() == level()
            && canInteractWithEntity(target, 0) && clearLine(target.getBoundingBox().getCenter());
    }

    JsonObject useBlock(BlockPos pos, Direction face, boolean secondaryUse) {
        return useBlock(pos, face, secondaryUse, null);
    }

    JsonObject useBlock(BlockPos pos, Direction face, boolean secondaryUse, Vec3 cursorPos) {
        return useBlock(pos, face, secondaryUse, cursorPos, InteractionHand.MAIN_HAND, null, InteractionHand.MAIN_HAND, true);
    }

    JsonObject useBlock(BlockPos pos, Direction face, boolean secondaryUse, Vec3 cursorPos,
                       InteractionHand hand, BlockPos expectedDestination, InteractionHand swingHand, boolean showHand) {
        syncBody();
        requireIdleHands();
        var hit = checkedHit(pos, face);
        if (cursorPos != null) {
            if (!Double.isFinite(cursorPos.x) || !Double.isFinite(cursorPos.y) || !Double.isFinite(cursorPos.z)
                || cursorPos.x < 0 || cursorPos.x > 1 || cursorPos.y < 0 || cursorPos.y > 1
                || cursorPos.z < 0 || cursorPos.z > 1) throw error("invalid_block_cursor_position");
            // Preserve the supplied block-local click point after native reach,
            // loaded-line and visible-face checks, including partial block shapes.
            hit = new BlockHitResult(Vec3.atLowerCornerOf(pos).add(cursorPos), hit.getDirection(), pos, false);
        }
        if (!getItemInHand(hand).isItemEnabled(level().enabledFeatures())) throw error("item_is_disabled");
        if (getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem) {
            var placement = new BlockPlaceContext(this, hand, getItemInHand(hand), hit);
            checkBlockAccess(placement.getClickedPos());
            if (expectedDestination != null && !placement.getClickedPos().equals(expectedDestination))
                throw error("placement_destination_changed");
        }
        var oldMenu = containerMenu;
        var oldOrigin = menuOrigin;
        var oldEntity = menuEntity;
        menuOrigin = pos.immutable();
        menuEntity = null;
        setShiftKeyDown(secondaryUse);
        InteractionResult result;
        try { result = gameMode.useItemOn(this, level(), getItemInHand(hand), hand, hit); }
        finally {
            setShiftKeyDown(body.isShiftKeyDown());
            if (containerMenu == oldMenu) { menuOrigin = oldOrigin; menuEntity = oldEntity; }
            save();
        }
        if (showHand && result.shouldSwing()) body.swing(swingHand);
        var response = interaction(result);
        response.add("menu", menuSnapshot());
        return response;
    }

    JsonObject beginElytraFlight() {
        syncBody();
        if (body.isFallFlying()) throw error("already_elytra_flying");
        if (body.onGround() || body.isInWater() || body.isPassenger() || body.isSleeping()
            || body.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)) throw error("cannot_start_elytra_flight");
        try {
            if (body.getClass().getMethod("travel", Vec3.class).getDeclaringClass() != LivingEntity.class)
                throw error("elytra_body_physics_not_supported");
        } catch (NoSuchMethodException unavailable) { throw error("elytra_body_physics_not_supported"); }
        if (!body.getItemBySlot(EquipmentSlot.CHEST).canElytraFly(body)) throw error("usable_elytra_required");
        body.setSharedFlag(7, true);
        return status("elytra_started");
    }

    JsonObject useHeld() { return useHeld(InteractionHand.MAIN_HAND); }

    JsonObject useHeld(InteractionHand hand) {
        syncBody();
        requireIdleHands();
        var held = getItemInHand(hand);
        if (held.isEmpty()) throw error("hand_is_empty");
        if (!held.isItemEnabled(level().enabledFeatures())) throw error("item_is_disabled");
        var animation = held.getUseAnimation();
        InteractionResult result;
        if (animation == UseAnim.EAT || animation == UseAnim.DRINK || animation == UseAnim.BLOCK) {
            if (getCooldowns().isOnCooldown(held.getItem())) return interaction(InteractionResult.PASS);
            var denied = CommonHooks.onItemRightClick(this, hand);
            if (denied != null) return interaction(denied);
            nativeUseHand = hand;
            nativeUseOutcome = "using";
            creativeUseStack = getAbilities().instabuild ? held.copy() : ItemStack.EMPTY;
            body.startUsingItem(hand);
            if (!body.isUsingItem()) { reconcileNativeUse(); throw error("native_item_use_rejected"); }
            result = InteractionResult.CONSUME;
        } else if (held.is(Items.FIREWORK_ROCKET) && body.isFallFlying()) result = boostWithFirework(held, hand);
        else if (held.is(Items.FISHING_ROD) && fishing == null) result = castFishingRod(held, hand);
        else result = held.getItem() instanceof BoatItem boat ? useBoat(boat, hand, null)
            : gameMode.useItem(this, level(), held, hand);
        if (result.shouldSwing()) body.swing(hand);
        save();
        var response = interaction(result);
        response.addProperty("usingItem", isUsingItem() || body.isUsingItem());
        return response;
    }

    private InteractionResult boostWithFirework(ItemStack rocketItem, InteractionHand hand) {
        if (getCooldowns().isOnCooldown(rocketItem.getItem())) return InteractionResult.PASS;
        var denied = CommonHooks.onItemRightClick(this, hand);
        if (denied != null) return denied;
        var rocket = new net.minecraft.world.entity.projectile.FireworkRocketEntity(level(), rocketItem, body);
        if (!serverLevel().addFreshEntity(rocket)) {
            rocket.discard();
            throw error("firework_spawn_rejected");
        }
        var item = rocketItem.getItem();
        rocketItem.consume(1, this);
        awardStat(net.minecraft.stats.Stats.ITEM_USED.get(item));
        return InteractionResult.CONSUME;
    }

    private InteractionResult castFishingRod(ItemStack rod, InteractionHand hand) {
        if (getCooldowns().isOnCooldown(rod.getItem())) return InteractionResult.PASS;
        var denied = CommonHooks.onItemRightClick(this, hand);
        if (denied != null) return denied;
        int lure = (int) (EnchantmentHelper.getFishingTimeReduction(serverLevel(), rod, this) * 20);
        int luck = EnchantmentHelper.getFishingLuckBonus(serverLevel(), rod, this);
        var hook = new BodyFishingHook(this, body, luck, lure);
        if (!serverLevel().addFreshEntity(hook)) { hook.discard(); throw error("fishing_spawn_rejected"); }
        level().playSound(null, body.getX(), body.getY(), body.getZ(), net.minecraft.sounds.SoundEvents.FISHING_BOBBER_THROW,
            net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 1.0F);
        body.gameEvent(GameEvent.ITEM_INTERACT_START);
        return InteractionResult.CONSUME;
    }

    @Override
    public Either<BedSleepingProblem, Unit> startSleepInBed(BlockPos pos) {
        syncBody();
        requireIdleHands();
        var state = level().getBlockState(pos);
        var problem = sleepProblem(pos, state);
        var result = problem == null
            ? Either.<BedSleepingProblem, Unit>right(Unit.INSTANCE)
            : Either.<BedSleepingProblem, Unit>left(problem);
        result = EventHooks.canPlayerStartSleeping(this, pos, result);
        if (result.left().isPresent()) return result;
        // A mod's sleep event cannot bypass the body ownership/movement boundary.
        requireSleepBounds(new Vec3(pos.getX() + 0.5, pos.getY() + 0.6875, pos.getZ() + 0.5));
        body.startSleeping(pos);
        return result;
    }

    private BedSleepingProblem sleepProblem(BlockPos pos, BlockState state) {
        if (body.isSleeping() || !body.isAlive() || !state.isBed(level(), pos, body)) return BedSleepingProblem.OTHER_PROBLEM;
        if (!level().dimensionType().natural()) return BedSleepingProblem.NOT_POSSIBLE_HERE;
        var direction = state.getBedDirection(level(), pos);
        var foot = pos.relative(direction.getOpposite());
        boolean inRange = false;
        for (var part : List.of(pos, foot)) {
            var delta = body.position().subtract(Vec3.atBottomCenterOf(part));
            if (Math.abs(delta.x) <= 3 && Math.abs(delta.y) <= 2 && Math.abs(delta.z) <= 3) inRange = true;
        }
        if (!inRange) return BedSleepingProblem.TOO_FAR_AWAY;
        if (!freeAt(pos.above()) || !freeAt(foot.above())) return BedSleepingProblem.OBSTRUCTED;
        if (level().isDay()) return BedSleepingProblem.NOT_POSSIBLE_NOW;
        if (!isCreative() && !level().getEntitiesOfClass(Monster.class,
                new AABB(Vec3.atBottomCenterOf(pos), Vec3.atBottomCenterOf(pos)).inflate(8, 5, 8),
                monster -> monster != body && monster.isPreventingPlayerRest(this)).isEmpty())
            return BedSleepingProblem.NOT_SAFE;
        return null;
    }

    private void requireSleepBounds(Vec3 position) {
        var bounds = bodyBox.get();
        if (!serverLevel().hasChunkAt(BlockPos.containing(position))
            || !serverLevel().getWorldBorder().isWithinBounds(BlockPos.containing(position))
            || bounds != null && (!bounds.dimension().equals(level().dimension().location().toString()) || !bounds.holds(position)))
            throw error("sleep_position_outside_body_boundary");
    }

    JsonObject wakeBody() {
        syncBody();
        if (!body.isSleeping()) throw error("already_awake");
        var pos = body.getSleepingPos().orElseThrow();
        var state = level().getBlockState(pos);
        if (state.isBed(level(), pos, body)) {
            var stand = BedBlock.findStandUpPosition(body.getType(), level(), pos,
                state.getBedDirection(level(), pos), body.getYRot()).orElse(Vec3.atBottomCenterOf(pos.above()).add(0, 0.1, 0));
            requireSleepBounds(stand);
        }
        body.stopSleeping();
        return status("awake");
    }

    void beginFishing() {
        syncBody();
        requireIdleHands();
        if (!(getMainHandItem().getItem() instanceof net.minecraft.world.item.FishingRodItem))
            throw error("fishing_rod_required");
        if (fishing != null && !fishing.isRemoved()) throw error("fishing_already_active");
        useHeld(InteractionHand.MAIN_HAND);
        if (fishing == null || fishing.isRemoved() || fishing.getPlayerOwner() != this)
            throw error("native_fishing_not_started");
        scriptFishing = fishing;
    }

    boolean tickFishing() {
        syncBody();
        if (scriptFishing == null || fishing != scriptFishing || scriptFishing.isRemoved())
            throw error("fishing_cancelled");
        if (!(getMainHandItem().getItem() instanceof net.minecraft.world.item.FishingRodItem))
            throw error("fishing_rod_changed");
        if (!scriptFishing.biting) return false;
        var hook = scriptFishing;
        useHeld(InteractionHand.MAIN_HAND);
        if (!hook.isRemoved()) throw error("native_fishing_not_retrieved");
        scriptFishing = null;
        return true;
    }

    void cancelFishing() {
        if (scriptFishing != null && !scriptFishing.isRemoved()) scriptFishing.discard();
        scriptFishing = null;
    }

    void beginConsume() {
        syncBody();
        var animation = getMainHandItem().getUseAnimation();
        if (animation != UseAnim.EAT && animation != UseAnim.DRINK) throw error("held_item_not_consumable");
        nativeUseOutcome = "idle";
        useHeld(InteractionHand.MAIN_HAND);
        if (!body.isUsingItem() && !nativeUseOutcome.equals("completed")) throw error("native_consumption_not_started");
    }

    String consumptionStatus() { reconcileNativeUse(); return nativeUseOutcome; }

    void nativeUseFinished(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        requireThread();
        if (nativeUseHand != event.getHand()) return;
        var original = event.getItem();
        // Vanilla applies these effects to mobs but reserves consumption/remainders
        // for players. Use its inventory helper after the body received the effects.
        net.minecraft.world.item.Item remainder = original.is(Items.POTION) || original.is(Items.HONEY_BOTTLE)
            ? Items.GLASS_BOTTLE : original.is(Items.MILK_BUCKET) ? Items.BUCKET : null;
        if (remainder != null && !getAbilities().instabuild) {
            event.setResultStack(net.minecraft.world.item.ItemUtils.createFilledResult(
                original.copy(), this, new ItemStack(remainder), false));
        }
        nativeUseOutcome = "completed";
    }

    private void reconcileNativeUse() {
        if (nativeUseHand == null) return;
        var stack = body.getItemInHand(nativeUseHand);
        if (!body.isUsingItem() && nativeUseOutcome.equals("completed") && !creativeUseStack.isEmpty()) {
            stack = creativeUseStack.copy();
            body.setItemInHand(nativeUseHand, stack);
        }
        setItemInHand(nativeUseHand, stack.copy());
        if (!body.isUsingItem()) {
            if (!nativeUseOutcome.equals("completed")) nativeUseOutcome = "interrupted";
            nativeUseHand = null;
            creativeUseStack = ItemStack.EMPTY;
        }
    }

    void cancelUse() {
        cancelFishing();
        if (fishing != null && !fishing.isRemoved()) fishing.discard();
        editedSign = null;
        requireThread();
        if (nativeUseHand != null) body.stopUsingItem();
        reconcileNativeUse();
        stopUsingItem();
    }

    JsonObject swingBody(InteractionHand hand) {
        syncBody();
        body.swing(hand);
        return status("swung");
    }

    JsonObject attackTarget(Entity target, boolean swing) {
        syncBody();
        requireIdleHands();
        if (!canReach(target) || (target instanceof LivingEntity living && !body.isWithinMeleeAttackRange(living)))
            throw error("entity_out_of_reach_or_obstructed");
        if (!target.isAttackable()) throw error("entity_not_attackable");
        if (body.getAttribute(Attributes.ATTACK_DAMAGE) == null) throw error("body_melee_attack_unavailable");
        // Use the actual body as attacker: retaliation, enchantments, health and
        // species attack effects must not belong to the invisible hands player.
        long now = level().getGameTime();
        boolean damaged = false;
        String outcome = "cooldown";
        if (now >= nextMeleeAttackTick) {
            nextMeleeAttackTick = now + 20; // Native ordinary MeleeAttackGoal interval.
            if (CommonHooks.onPlayerAttackTarget(this, target)) {
                try { damaged = body.doHurtTarget(target); }
                finally { save(); }
                outcome = damaged ? "hit" : "no_damage";
            } else outcome = "denied";
        }
        if (swing) body.swing(InteractionHand.MAIN_HAND);
        var result = status(outcome);
        result.addProperty("damaged", damaged);
        result.addProperty("target", target.getStringUUID());
        return result;
    }

    JsonObject releaseHeld() {
        syncBody();
        if (nativeUseHand != null) { body.releaseUsingItem(); reconcileNativeUse(); }
        else releaseUsingItem();
        save();
        return status("released");
    }

    JsonObject placeEntity(BlockPos pos, Direction face, Vec3 cursor, InteractionHand hand,
                           InteractionHand swingHand, boolean showHand) {
        syncBody();
        requireIdleHands();
        var item = getItemInHand(hand).getItem();
        if (!(item instanceof BoatItem || item instanceof ArmorStandItem || item instanceof EndCrystalItem || item instanceof SpawnEggItem))
            throw error("item_does_not_place_supported_entity");
        checkBlockAccess(pos);
        if (!canInteractWithBlock(pos, 0)) throw error("block_out_of_reach");
        var bounds = new AABB(pos).inflate(8);
        var before = new HashSet<UUID>();
        for (var entity : serverLevel().getEntities((Entity) null, bounds)) before.add(entity.getUUID());
        if (before.size() > 1024) throw error("entity_placement_observation_limit");
        // Boats use their own native fluid ray trace; the other items use the
        // native block interaction. Both run wholly within this server tick.
        JsonObject result;
        if (item instanceof BoatItem boat) {
            try { result = interaction(useBoat(boat, hand, pos)); }
            finally { save(); }
        } else result = useBlock(pos, face, body.isShiftKeyDown(), cursor, hand, null, swingHand, showHand);
        var spawned = new JsonArray();
        for (var entity : serverLevel().getEntities((Entity) null, bounds)) {
            if (!before.contains(entity.getUUID())) spawned.add(entity.getStringUUID());
        }
        result.add("spawnedEntities", spawned);
        return result;
    }

    private InteractionResult useBoat(BoatItem item, InteractionHand hand, BlockPos expected) {
        var stack = getItemInHand(hand);
        if (!stack.isItemEnabled(level().enabledFeatures())) throw error("item_is_disabled");
        if (getCooldowns().isOnCooldown(item)) return InteractionResult.PASS;
        var denied = CommonHooks.onItemRightClick(this, hand);
        if (denied != null) return denied;
        var eye = getEyePosition();
        var hit = level().clip(new ClipContext(eye, eye.add(getViewVector(1).scale(blockInteractionRange())),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, body));
        if (hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
        checkBlockAccess(hit.getBlockPos());
        if (expected != null && !expected.equals(hit.getBlockPos())) throw error("placement_target_changed");
        // BoatItem's player ray sees our visible body at the same eye position.
        // Exclude that body, preserving all other native occlusion/collision checks.
        for (var entity : level().getEntities(body, body.getBoundingBox().expandTowards(getViewVector(1).scale(5)).inflate(1))) {
            if (!entity.isSpectator() && entity.isPickable() && entity.getBoundingBox().inflate(entity.getPickRadius()).contains(eye))
                return InteractionResult.PASS;
        }
        var boat = item.getBoat(level(), hit, stack, this);
        boat.setVariant(item.type);
        boat.setYRot(getYRot());
        if (!level().noCollision(boat, boat.getBoundingBox())) return InteractionResult.FAIL;
        if (!serverLevel().addFreshEntity(boat)) return InteractionResult.FAIL;
        level().gameEvent(body, GameEvent.ENTITY_PLACE, hit.getLocation());
        stack.consume(1, this);
        return InteractionResult.CONSUME;
    }

    JsonObject interact(Entity target) { return interact(target, null); }

    JsonObject interact(Entity target, Vec3 localHit) {
        syncBody();
        requireIdleHands();
        if (!canReach(target)) throw error("entity_out_of_reach_or_obstructed");
        var oldMenu = containerMenu;
        var oldOrigin = menuOrigin;
        var oldEntity = menuEntity;
        menuOrigin = null;
        menuEntity = target;
        InteractionResult result;
        try {
            if (localHit == null) result = interactOn(target, InteractionHand.MAIN_HAND);
            else {
                if (!Double.isFinite(localHit.x) || !Double.isFinite(localHit.y) || !Double.isFinite(localHit.z))
                    throw error("invalid_entity_hit");
                var hit = target.position().add(localHit);
                if (!target.getBoundingBox().inflate(0.001).contains(hit) || !clearLine(hit))
                    throw error("entity_hit_outside_target_or_obstructed");
                result = CommonHooks.onInteractEntityAt(this, target, localHit, InteractionHand.MAIN_HAND);
                if (result == null) result = target.interactAt(this, localHit, InteractionHand.MAIN_HAND);
            }
        }
        finally {
            if (containerMenu == oldMenu) { menuOrigin = oldOrigin; menuEntity = oldEntity; }
            save();
        }
        if (result.shouldSwing()) body.swing(InteractionHand.MAIN_HAND);
        var response = interaction(result);
        response.add("menu", menuSnapshot());
        return response;
    }

    @Override
    public boolean startRiding(Entity vehicle, boolean force) {
        // FakePlayer refuses riding; the native interaction mounts our visible body.
        return body != null && body.startRiding(vehicle, force);
    }

    @Override
    public void openTextEdit(net.minecraft.world.level.block.entity.SignBlockEntity sign, boolean front) {
        // Sign ticks cannot find an unlisted FakePlayer; retain the native grant locally.
        editedSign = sign;
        signEditorSequence++;
        signEditorExpires = level().getGameTime() + 1200;
    }

    JsonObject updateSign(BlockPos position, boolean front, List<String> lines) {
        syncBody();
        requireIdleHands();
        checkBlockAccess(position);
        if (!canInteractWithBlock(position, 0)) throw error("sign_out_of_reach");
        if (!(level().getBlockEntity(position) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign))
            throw error("target_not_sign");
        var editor = sign.getPlayerWhoMayEdit();
        if (sign.isWaxed() || editedSign != sign || level().getGameTime() > signEditorExpires
            || editor != null && !getUUID().equals(editor)) throw error("sign_not_editable");
        if (getTextFilter() != net.minecraft.server.network.TextFilter.DUMMY) throw error("sign_text_filter_unavailable");
        sign.setAllowedPlayerEditor(getUUID());
        editedSign = null;
        sign.updateSignText(this, front, lines.stream().map(net.minecraft.server.network.FilteredText::passThrough).toList());
        for (int i = 0; i < 4; i++)
            if (!sign.getText(front).getMessage(i, false).getString().equals(lines.get(i))) throw error("sign_update_failed");
        return status("sign_updated");
    }

    JsonObject dismountBody() {
        syncBody();
        if (!body.isPassenger()) throw error("body_not_mounted");
        body.stopRiding();
        return status("dismounted");
    }

    void selectHotbar(int slot) {
        syncBody();
        if (slot < 0 || slot > 8) throw error("invalid_hotbar_slot");
        if (getInventory().selected != slot && (getUsedItemHand() == InteractionHand.MAIN_HAND || nativeUseHand == InteractionHand.MAIN_HAND)) cancelUse();
        getInventory().selected = slot;
        save();
    }

    JsonObject equip(int inventorySlot, String equipmentSlot) {
        syncBody();
        requireIdleHands();
        if (inventorySlot < 0 || inventorySlot > 40) throw error("invalid_inventory_slot");
        if (containerMenu != inventoryMenu) throw error("close_container_before_equipping");
        if (!inventoryMenu.getCarried().isEmpty()) throw error("put_down_menu_cursor_before_equipping");
        int target = switch (equipmentSlot) {
            case "mainhand" -> getInventory().selected;
            case "offhand" -> 40;
            case "feet" -> 36;
            case "legs" -> 37;
            case "chest" -> 38;
            case "head" -> 39;
            default -> throw error("invalid_equipment_slot");
        };
        if (inventorySlot != target) {
            int sourceMenu = inventoryMenuSlot(inventorySlot);
            int targetMenu = inventoryMenuSlot(target);
            var source = inventoryMenu.getSlot(sourceMenu);
            var destination = inventoryMenu.getSlot(targetMenu);
            if (!source.mayPickup(this) || !destination.mayPickup(this)
                || !destination.mayPlace(source.getItem()) || !source.mayPlace(destination.getItem())) {
                throw error("equipment_swap_not_allowed");
            }
            // SWAP uses native armor/offhand slot restrictions and preserves the displaced item.
            if (target < 9 || target == 40) inventoryMenu.clicked(sourceMenu, target, ClickType.SWAP, this);
            else {
                inventoryMenu.clicked(sourceMenu, 0, ClickType.PICKUP, this);
                inventoryMenu.clicked(targetMenu, 0, ClickType.PICKUP, this);
                inventoryMenu.clicked(sourceMenu, 0, ClickType.PICKUP, this);
            }
        }
        save();
        return snapshot();
    }

    JsonObject creativeSlot(int menuId, long generation, int slot, String wire) {
        checkMenuAction(menuId, generation);
        if (!isCreative()) throw error("creative_mode_required");
        if (slot < 1 || slot > 45) throw error("invalid_creative_inventory_slot");
        var items = new ScriptItems(serverLevel());
        var stack = items.read(wire);
        if (!stack.isEmpty()) {
            if (!stack.isItemEnabled(level().enabledFeatures())) throw error("item_is_disabled");
            if (stack.getCount() < 1 || stack.getCount() > stack.getMaxStackSize()) throw error("invalid_item_count");
            // Match native creative block-entity copying, without reading beyond the body's box.
            var data = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.EMPTY);
            if (data.contains("x") && data.contains("y") && data.contains("z")) {
                var pos = net.minecraft.world.level.block.entity.BlockEntity.getPosFromTag(data.getUnsafe());
                var bounds = bodyBox.get();
                if (bounds != null && !bounds.contains(pos)) throw error("creative_block_data_outside_box");
                if (level().isLoaded(pos)) {
                    var entity = level().getBlockEntity(pos);
                    if (entity != null) entity.saveToItem(stack, registryAccess());
                }
            }
            if (ItemStack.CODEC.encodeStart(registryAccess().createSerializationContext(NbtOps.INSTANCE), stack).error().isPresent())
                throw error("invalid_creative_item_components");
            items.wire(stack); // Validate final native serialization before touching the slot.
        }
        inventoryMenu.getSlot(slot).setByPlayer(stack);
        getInventory().setChanged();
        inventoryMenu.broadcastChanges();
        if (containerMenu != inventoryMenu) containerMenu.broadcastChanges();
        save();
        return snapshot();
    }

    JsonObject creativeItem(String item, int count) {
        syncBody();
        requireIdleHands();
        if (!isCreative()) throw error("creative_mode_required");
        var id = ResourceLocation.tryParse(item);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw error("unknown_item");
        var stack = new ItemStack(BuiltInRegistries.ITEM.get(id), count);
        if (count < 1 || count > stack.getMaxStackSize() || stack.isEmpty()) throw error("invalid_item_count");
        if (!stack.isItemEnabled(level().enabledFeatures())) throw error("item_is_disabled");
        if (!getMainHandItem().isEmpty()) {
            int free = getInventory().getFreeSlot();
            if (free < 0) throw error("inventory_full_cannot_preserve_held_item");
            getInventory().setItem(free, getMainHandItem());
        }
        setItemInHand(InteractionHand.MAIN_HAND, stack);
        save();
        return snapshot();
    }

    @Override public void take(Entity item, int count) {
        requireThread();
        // The client knows the visible body, not this inventory's fake player.
        forwardedTakes++;
        try { body.take(item, count); }
        finally { forwardedTakes--; }
        containerMenu.broadcastChanges();
    }

    void pickupNearby() {
        syncBody();
        var bounds = body.getBoundingBox().inflate(1.0, 0.5, 1.0);
        boolean collected = false;
        for (var item : serverLevel().getEntitiesOfClass(ItemEntity.class, bounds)) {
            int before = item.getItem().getCount();
            item.playerTouch(this);
            collected |= item.isRemoved() || item.getItem().getCount() < before;
        }
        // The visible Mob has no player XP pickup. Native touch owns the delay,
        // Mending, experience events and orb consumption for its hands.
        if (takeXpDelay == 0) for (var orb : serverLevel().getEntitiesOfClass(ExperienceOrb.class, bounds)) {
            orb.playerTouch(this);
            if (takeXpDelay > 0) { collected = true; break; }
        }
        if (collected) save();
    }

    JsonObject pickup(double radius) {
        syncBody();
        if (!Double.isFinite(radius) || radius <= 0 || radius > 2) throw error("pickup_radius_must_be_within_2");
        int count = 0;
        for (var item : serverLevel().getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(radius))) {
            if (body.getBoundingBox().distanceToSqr(item.position()) > radius * radius || !clearLine(item.position())) continue;
            int before = item.getItem().getCount();
            item.playerTouch(this);
            count += item.isRemoved() ? before : Math.max(0, before - item.getItem().getCount());
        }
        save();
        var result = status("completed");
        result.addProperty("pickedUp", count);
        return result;
    }

    JsonObject give(ServerPlayer target, String item, int count) {
        syncBody();
        requireIdleHands();
        if (!canReach(target)) throw error("recipient_out_of_reach_or_obstructed");
        if (target.isSpectator()) throw error("recipient_is_spectator");
        var id = ResourceLocation.tryParse(item);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw error("unknown_item");
        if (count < 1 || count > 2304) throw error("invalid_item_count");
        var requested = BuiltInRegistries.ITEM.get(id);
        int available = 0;
        for (int slot = 0; slot < 36; slot++) if (getInventory().getItem(slot).is(requested)) available += getInventory().getItem(slot).getCount();
        if (available < count) throw error("insufficient_items");
        int remaining = count;
        int pickedUp = 0;
        int tossed = 0;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            var existing = getInventory().getItem(slot);
            if (!existing.is(requested)) continue;
            var stack = existing.split(Math.min(remaining, existing.getCount()));
            int amount = stack.getCount();
            // Native toss cancellation can deliberately consume a stack; never manufacture a refund.
            var dropped = drop(stack, true);
            if (dropped == null) { save(); throw error("item_toss_rejected_outcome_requires_observation"); }
            dropped.setTarget(target.getUUID());
            dropped.setNoPickUpDelay();
            dropped.setPos(target.position());
            dropped.setDeltaMovement(Vec3.ZERO);
            dropped.playerTouch(target);
            pickedUp += dropped.isRemoved() ? amount : Math.max(0, amount - dropped.getItem().getCount());
            tossed += amount;
            remaining -= amount;
        }
        save();
        var result = status("completed");
        result.addProperty("given", pickedUp);
        result.addProperty("dropped", tossed - pickedUp);
        return result;
    }

    @Override public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> ignoredClientData) {
        requireThread();
        if (provider == null) return OptionalInt.empty();
        // useBlock/interact already recorded the new provider's reach origin.
        var origin = menuOrigin;
        var entity = menuEntity;
        if (containerMenu != inventoryMenu) closeContainer();
        nextMenuId = nextMenuId % 100 + 1;
        var menu = provider.createMenu(nextMenuId, getInventory(), this);
        if (menu == null) return OptionalInt.empty();
        containerMenu = menu;
        menuOrigin = origin;
        menuEntity = entity;
        observeMenu(provider.getDisplayName());
        NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(this, menu));
        return OptionalInt.of(nextMenuId);
    }

    @Override public void openHorseInventory(AbstractHorse horse, Container inventory) {
        requireThread();
        requireIdleHands();
        if (isSpectator() || !canReach(horse)) throw error("horse_inventory_not_accessible");
        // AbstractHorse already applied native tame/age/interaction conditions.
        // FakePlayer's implementation is a no-op; construct the same real menu
        // as ServerPlayer, without a network screen or another player entity.
        if (containerMenu != inventoryMenu) closeContainer();
        nextMenuId = nextMenuId % 100 + 1;
        containerMenu = new HorseInventoryMenu(nextMenuId, getInventory(), inventory, horse, horse.getInventoryColumns());
        menuOrigin = null;
        menuEntity = horse;
        observeMenu(horse.getDisplayName());
        NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(this, containerMenu));
    }

    private String menuType() {
        if (containerMenu == inventoryMenu) return "minecraft:inventory";
        // HorseInventoryMenu deliberately has no MenuType registry entry.
        if (containerMenu instanceof HorseInventoryMenu) return "HorseWindow";
        return BuiltInRegistries.MENU.getKey(containerMenu.getType()).toString();
    }

    @Override public void doCloseContainer() {
        requireThread();
        super.doCloseContainer();
        menuOrigin = null;
        menuEntity = null;
        // Closing inventory is also a boundary: removed() returns its cursor/grid.
        observeMenu(getInventory().getDisplayName());
    }

    @Override public void sendMerchantOffers(int id, MerchantOffers offers, int level, int xp,
                                             boolean showProgressBar, boolean canRestock) {
        requireThread();
        if (containerMenu.containerId != id || !(containerMenu instanceof MerchantMenu merchant)) return;
        ensureObservedMenu();
        // Vanilla only sends these fields to the client; this FakePlayer has none.
        merchant.setMerchantLevel(level);
        merchant.setShowProgressBar(showProgressBar);
        merchant.setCanRestock(canRestock);
        merchantMetadataKnown = true;
    }

    private void observeMenu(Component title) {
        requireThread();
        observedMenu = containerMenu;
        menuGeneration = Math.incrementExact(menuGeneration);
        menuTitle = title == null ? null : title.copy();
        merchantMetadataKnown = false;
        selectedTrade = 0; // MerchantContainer's initial selection hint.
        menuProperties = new int[0];
        // Only menus belonging to this FakePlayer reach here. Initial data includes zeros.
        containerMenu.setSynchronizer(new ContainerSynchronizer() {
            @Override public void sendInitialData(AbstractContainerMenu menu, NonNullList<ItemStack> slots,
                                                  ItemStack carried, int[] data) {
                requireThread();
                if (menu == observedMenu) menuProperties = data.clone();
            }
            @Override public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) {}
            @Override public void sendCarriedChange(AbstractContainerMenu menu, ItemStack stack) {}
            @Override public void sendDataChange(AbstractContainerMenu menu, int index, int value) {
                requireThread();
                if (menu == observedMenu) menuProperties[index] = value;
            }
        });
    }

    private void ensureObservedMenu() {
        requireThread();
        if (observedMenu != containerMenu)
            observeMenu(containerMenu == inventoryMenu ? getInventory().getDisplayName() : null);
    }

    private void checkMenu(int expectedMenuId, long expectedGeneration) {
        syncBody();
        ensureObservedMenu();
        if (containerMenu.containerId != expectedMenuId || menuGeneration != expectedGeneration)
            throw error("menu_changed");
    }

    private void checkMenuAction(int expectedMenuId, long expectedGeneration) {
        checkMenu(expectedMenuId, expectedGeneration);
        requireIdleHands();
        if (isSpectator()) throw error("menu_action_not_permitted");
        if (!validMenu()) { closeContainer(); save(); throw error("menu_no_longer_valid"); }
    }

    JsonObject clickMenu(int expectedMenuId, int slot, int button, ClickType click) {
        ensureObservedMenu();
        return clickMenu(expectedMenuId, menuGeneration, slot, button, click);
    }

    JsonObject clickMenu(int expectedMenuId, long expectedGeneration, int slot, int button, ClickType click) {
        checkMenuAction(expectedMenuId, expectedGeneration);
        if (click == null) throw error("unsupported_menu_click");
        boolean outside = slot == -999 && click == ClickType.PICKUP && (button == 0 || button == 1);
        if (!outside && (slot < 0 || slot >= containerMenu.slots.size())) throw error("invalid_menu_slot");
        boolean valid = switch (click) {
            case PICKUP, QUICK_MOVE, THROW -> button == 0 || button == 1;
            case SWAP -> button >= 0 && button < 9 || button == 40;
            case CLONE -> isCreative() && button == 2;
            default -> false;
        };
        if (!valid) throw error("unsupported_menu_click");
        containerMenu.clicked(slot, button, click, this);
        containerMenu.broadcastChanges();
        save();
        return menuSnapshot();
    }

    JsonObject closeMenu() {
        ensureObservedMenu();
        return closeMenu(containerMenu.containerId, menuGeneration);
    }

    JsonObject closeMenu(int expectedMenuId, long expectedGeneration) {
        checkMenu(expectedMenuId, expectedGeneration);
        closeContainer();
        containerMenu.broadcastChanges();
        save();
        return menuSnapshot();
    }

    JsonObject menuButton(int expectedMenuId, long expectedGeneration, int button) {
        checkMenuAction(expectedMenuId, expectedGeneration);
        if (button < 0 || containerMenu instanceof StonecutterMenu stonecutter && button >= stonecutter.getNumRecipes())
            throw error("invalid_menu_button");
        boolean accepted = containerMenu.clickMenuButton(this, button);
        containerMenu.broadcastChanges();
        save();
        if (!accepted) throw error("menu_button_rejected");
        return menuSnapshot();
    }

    JsonObject renameAnvil(int expectedMenuId, long expectedGeneration, String name) {
        checkMenuAction(expectedMenuId, expectedGeneration);
        if (!(containerMenu instanceof AnvilMenu anvil)) throw error("not_an_anvil_menu");
        if (name == null || name.length() > 32767 || StringUtil.filterText(name).length() > 50)
            throw error("invalid_anvil_name");
        // false also means an unchanged valid name, which is a successful no-op.
        boolean changed = anvil.setItemName(name);
        containerMenu.broadcastChanges();
        save();
        var result = menuSnapshot();
        result.addProperty("nameChanged", changed);
        return result;
    }

    JsonObject selectTrade(int expectedMenuId, long expectedGeneration, int index) {
        checkMenuAction(expectedMenuId, expectedGeneration);
        if (!(containerMenu instanceof MerchantMenu merchant)) throw error("not_a_merchant_menu");
        if (index < 0 || index >= merchant.getOffers().size()) throw error("invalid_trade_index");
        merchant.setSelectionHint(index);
        selectedTrade = index;
        merchant.tryMoveItems(index);
        containerMenu.broadcastChanges();
        save();
        return menuSnapshot();
    }

    JsonObject setCommandBlock(BlockPos pos, int expectedStateId, String command, int mode, boolean trackOutput,
                               boolean conditional, boolean automatic, boolean operatorPermission) {
        syncBody();
        requireIdleHands();
        if (!BodySettings.mode(body.getPersistentData().getString("too_many_agents_mode")).commands
            || !isCreative() || !operatorPermission) throw error("command_block_edit_not_permitted");
        if (!getServer().isCommandBlockEnabled()) throw error("command_blocks_disabled");
        if (command == null || command.length() > 32767 || mode < 0 || mode > 2) throw error("invalid_command_block_options");
        checkedHit(pos, null);
        if (expectedStateId < 0 || net.minecraft.world.level.block.Block.getId(level().getBlockState(pos)) != expectedStateId)
            throw error("target_changed");
        if (!(level().getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.CommandBlockEntity entity))
            throw error("not_a_command_block");
        var oldMode = entity.getMode();
        var state = level().getBlockState(pos);
        var block = switch (mode) {
            case 0 -> net.minecraft.world.level.block.Blocks.CHAIN_COMMAND_BLOCK;
            case 1 -> net.minecraft.world.level.block.Blocks.REPEATING_COMMAND_BLOCK;
            default -> net.minecraft.world.level.block.Blocks.COMMAND_BLOCK;
        };
        var updated = block.defaultBlockState()
            .setValue(net.minecraft.world.level.block.CommandBlock.FACING, state.getValue(net.minecraft.world.level.block.CommandBlock.FACING))
            .setValue(net.minecraft.world.level.block.CommandBlock.CONDITIONAL, conditional);
        if (updated != state) {
            if (!level().setBlock(pos, updated, 2)) throw error("command_block_state_update_rejected");
            // Preserve the native command block data across its block-type change.
            entity.setBlockState(updated);
            level().getChunkAt(pos).setBlockEntity(entity);
        }
        var executor = entity.getCommandBlock();
        executor.setCommand(command);
        executor.setTrackOutput(trackOutput);
        if (!trackOutput) executor.setLastOutput(null);
        entity.setAutomatic(automatic);
        if (oldMode != entity.getMode()) entity.onModeSwitch();
        executor.onUpdated();
        return status("updated");
    }

    /** Null title writes; a nonnull title signs with this native player's identity. */
    JsonObject editBook(int expectedMenuId, long expectedGeneration, int inventorySlot,
                        String expectedItemKey, List<String> pages, String title) {
        checkMenuAction(expectedMenuId, expectedGeneration);
        if ((inventorySlot < 0 || inventorySlot > 8) && inventorySlot != 40)
            throw error("invalid_book_slot");
        if (inventorySlot != 40 && getInventory().selected != inventorySlot)
            throw error("book_slot_not_selected");
        if (expectedItemKey == null || !expectedItemKey.matches("[0-9a-f]{64}"))
            throw error("invalid_book_item_key");
        if (pages == null || pages.size() > WritableBookContent.MAX_PAGES)
            throw error("invalid_book_pages");
        int pageLimit = title == null ? WritableBookContent.PAGE_EDIT_LENGTH : 8192;
        for (var page : pages) {
            if (page == null || page.length() > pageLimit) throw error("invalid_book_page_length");
        }
        if (title != null && title.length() > WrittenBookContent.TITLE_MAX_LENGTH)
            throw error("invalid_book_title_length");

        var book = getInventory().getItem(inventorySlot);
        if (!book.is(Items.WRITABLE_BOOK)) throw error("not_a_writable_book");
        if (!book.isItemEnabled(level().enabledFeatures())) throw error("item_is_disabled");
        // Singleplayer uses this exact pass-through filter. Do not bypass a custom
        // filter or start an asynchronous edit that escapes the action barrier.
        if (getTextFilter() != TextFilter.DUMMY) throw error("book_text_filter_requires_async_support");
        var items = new ScriptItems(serverLevel());
        String actualKey;
        try {
            actualKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(items.wire(book).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("book_item_hash_unavailable", unavailable);
        }
        if (!actualKey.equals(expectedItemKey)) throw error("book_item_changed");

        // Prepare and validate on a copy: packet limits are looser than native
        // component/save codecs. No rejection below may leave a half-edited book.
        var updated = title == null ? book.copy() : book.transmuteCopy(Items.WRITTEN_BOOK);
        if (title == null) {
            updated.set(DataComponents.WRITABLE_BOOK_CONTENT,
                new WritableBookContent(pages.stream().map(Filterable::passThrough).toList()));
        } else {
            updated.remove(DataComponents.WRITABLE_BOOK_CONTENT);
            var content = pages.stream().map(page -> Filterable.<Component>passThrough(Component.literal(page))).toList();
            updated.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough(title), getName().getString(), 0, content, true));
        }
        try {
            // Use the save codec directly; the convenience save() wrapper logs
            // complete component data on failure, which could include book text.
            ItemStack.CODEC.encodeStart(registryAccess().createSerializationContext(NbtOps.INSTANCE), updated)
                .getOrThrow(message -> error("invalid_book_components"));
            items.wire(updated);
        } catch (RuntimeException invalid) {
            throw error("invalid_book_components");
        }
        if (title == null) book.set(DataComponents.WRITABLE_BOOK_CONTENT, updated.get(DataComponents.WRITABLE_BOOK_CONTENT));
        else getInventory().setItem(inventorySlot, updated);
        getInventory().setChanged();
        inventoryMenu.broadcastChanges();
        if (containerMenu != inventoryMenu) containerMenu.broadcastChanges();
        save();
        return menuSnapshot();
    }

    JsonObject menuSnapshot() {
        requireThread();
        ensureObservedMenu();
        containerMenu.broadcastChanges();
        var result = new JsonObject();
        result.addProperty("id", containerMenu.containerId);
        result.addProperty("generation", menuGeneration);
        if (menuTitle == null) {
            result.add("title", JsonNull.INSTANCE);
            result.add("titleNbt", JsonNull.INSTANCE);
        } else {
            result.addProperty("title", menuTitle.getString());
            var ops = registryAccess().createSerializationContext(NbtOps.INSTANCE);
            var tag = ComponentSerialization.CODEC.encodeStart(ops, menuTitle)
                .getOrThrow(message -> error("menu_title_encode_failed: " + message));
            result.add("titleNbt", ScriptNbt.typed(tag));
        }
        var properties = new JsonArray();
        for (int value : menuProperties) properties.add(value);
        result.add("properties", properties);
        result.addProperty("type", menuType());
        result.addProperty("valid", validMenu());
        result.add("carried", item(containerMenu.getCarried()));
        var carried = containerMenu.getCarried();
        var slots = new JsonArray();
        for (int index = 0; index < containerMenu.slots.size(); index++) {
            var slot = containerMenu.getSlot(index);
            var entry = item(slot.getItem());
            entry.addProperty("slot", index);
            if (slot.container == getInventory()) {
                entry.addProperty("inventorySlot", slot.getContainerSlot());
                entry.addProperty("inventoryWindowSlot", inventoryMenuSlot(slot.getContainerSlot()));
                entry.addProperty("role", "inventory");
            } else if (slot instanceof net.minecraft.world.inventory.ResultSlot) {
                entry.addProperty("role", "crafting_result");
            } else if (slot.container instanceof net.minecraft.world.inventory.CraftingContainer crafting) {
                entry.addProperty("role", "crafting_input");
                entry.addProperty("craftingIndex", slot.getContainerSlot());
                entry.addProperty("craftingWidth", crafting.getWidth());
                entry.addProperty("craftingHeight", crafting.getHeight());
            } else entry.addProperty("role", "container");
            entry.addProperty("mayPickup", slot.mayPickup(this));
            entry.addProperty("mayPlaceCarried", !carried.isEmpty() && slot.mayPlace(carried));
            entry.addProperty("maxStackSize", carried.isEmpty() ? slot.getMaxStackSize() : slot.getMaxStackSize(carried));
            entry.addProperty("componentMerge", !carried.isEmpty() && !slot.getItem().isEmpty()
                && ItemStack.isSameItemSameComponents(slot.getItem(), carried));
            slots.add(entry);
        }
        result.add("slots", slots);
        return result;
    }

    JsonObject snapshot() {
        return snapshot(false);
    }

    private JsonObject snapshot(boolean terminalDeath) {
        if (terminalDeath) {
            // Observe cleanup results without synchronizing or reviving a dead body.
            requireThread();
            if (!closed || !body.isDeadOrDying() || body.level() != level())
                throw error("invalid_terminal_body_snapshot");
        } else syncBody();
        ensureObservedMenu();
        var result = new JsonObject();
        result.addProperty("mode", BodySettings.mode(body.getPersistentData().getString("too_many_agents_mode")).id);
        result.addProperty("selected", getInventory().selected);
        result.addProperty("usingItem", isUsingItem() || body.isUsingItem());
        var experience = new JsonObject();
        experience.addProperty("level", experienceLevel);
        experience.addProperty("progress", experienceProgress);
        experience.addProperty("total", totalExperience);
        experience.addProperty("seed", getEnchantmentSeed());
        result.add("experience", experience);
        var inventory = new JsonArray();
        result.addProperty("inventorySize", getInventory().getContainerSize());
        for (int slot = 0; slot < getInventory().getContainerSize(); slot++) {
            if (getInventory().getItem(slot).isEmpty()) continue;
            var entry = item(getInventory().getItem(slot));
            entry.addProperty("slot", slot);
            inventory.add(entry);
        }
        result.add("inventory", inventory);
        var equipment = new JsonObject();
        for (var slot : EquipmentSlot.values()) {
            if (slot != EquipmentSlot.BODY && !getItemBySlot(slot).isEmpty()) equipment.add(slot.getName(), item(getItemBySlot(slot)));
        }
        result.add("equipment", equipment);
        if (miningPos != null) result.add("mining", miningResult("mining"));
        var menu = new JsonObject();
        menu.addProperty("id", containerMenu.containerId);
        menu.addProperty("generation", menuGeneration);
        menu.addProperty("type", menuType());
        menu.addProperty("valid", validMenu());
        menu.addProperty("slotCount", containerMenu.slots.size());
        menu.add("carried", item(containerMenu.getCarried()));
        result.add("menu", menu);
        return result;
    }

    JsonObject scriptSnapshot(ScriptItems items) {
        return scriptSnapshot(items, false);
    }

    JsonObject scriptSnapshot(ScriptItems items, boolean terminalDeath) {
        var result = snapshot(terminalDeath);
        result.addProperty("blockInteractionRange", blockInteractionRange());
        result.add("tablist", tablist.deepCopy());
        if (editedSign != null && !editedSign.isRemoved() && editedSign.getLevel() == level()
            && level().getGameTime() <= signEditorExpires) {
            var position = editedSign.getBlockPos();
            result.add("signEditor", JsonState.object("sequence", signEditorSequence, "position",
                JsonState.object("x", position.getX(), "y", position.getY(), "z", position.getZ())));
        }
        for (var entry : result.getAsJsonArray("inventory")) {
            var item = entry.getAsJsonObject();
            item.addProperty("wire", items.wire(getInventory().getItem(item.get("slot").getAsInt())));
        }
        var equipment = result.getAsJsonObject("equipment");
        for (var slot : EquipmentSlot.values()) {
            if (equipment.has(slot.getName()))
                equipment.getAsJsonObject(slot.getName()).addProperty("wire", items.wire(getItemBySlot(slot)));
        }
        var menu = menuSnapshot();
        menu.getAsJsonObject("carried").addProperty("wire", items.wire(containerMenu.getCarried()));
        for (var entry : menu.getAsJsonArray("slots")) {
            var item = entry.getAsJsonObject();
            item.addProperty("wire", items.wire(containerMenu.getSlot(item.get("slot").getAsInt()).getItem()));
        }
        if (containerMenu instanceof MerchantMenu merchant) menu.add("merchant", merchantSnapshot(merchant, items));
        result.add("menu", menu);
        return result;
    }

    private JsonObject merchantSnapshot(MerchantMenu merchant, ScriptItems items) {
        var result = new JsonObject();
        result.addProperty("xp", merchant.getTraderXp());
        result.addProperty("futureXp", merchant.getFutureTraderXp());
        // These fields have no server-side value until sendMerchantOffers supplies them.
        if (merchantMetadataKnown) {
            result.addProperty("level", merchant.getTraderLevel());
            result.addProperty("canRestock", merchant.canRestock());
            result.addProperty("showProgressBar", merchant.showProgressBar());
        }
        result.addProperty("selectedTrade", selectedTrade);
        var offers = new JsonArray();
        for (var offer : merchant.getOffers()) {
            var entry = new JsonObject();
            entry.add("baseCostA", scriptItem(offer.getBaseCostA(), items));
            entry.add("costA", scriptItem(offer.getCostA(), items));
            entry.add("costB", scriptItem(offer.getCostB(), items));
            entry.add("result", scriptItem(offer.getResult(), items));
            entry.addProperty("uses", offer.getUses());
            entry.addProperty("maxUses", offer.getMaxUses());
            entry.addProperty("demand", offer.getDemand());
            entry.addProperty("specialPrice", offer.getSpecialPriceDiff());
            entry.addProperty("priceMultiplier", offer.getPriceMultiplier());
            entry.addProperty("xp", offer.getXp());
            entry.addProperty("outOfStock", offer.isOutOfStock());
            entry.addProperty("rewardExp", offer.shouldRewardExp());
            offers.add(entry);
        }
        result.add("offers", offers);
        return result;
    }

    private static JsonObject scriptItem(ItemStack stack, ScriptItems items) {
        var result = item(stack);
        result.addProperty("wire", items.wire(stack));
        return result;
    }

    void save() {
        requireThread();
        syncEquipment();
        var saved = new CompoundTag();
        saved.put("inventory", getInventory().save(new ListTag()));
        saved.put("enderItems", getEnderChestInventory().createTag(registryAccess()));
        saved.putInt("selected", getInventory().selected);
        saved.putLong("menuGeneration", menuGeneration);
        saved.putFloat("XpP", experienceProgress);
        saved.putInt("XpLevel", experienceLevel);
        saved.putInt("XpTotal", totalExperience);
        saved.putInt("XpSeed", getEnchantmentSeed());
        if (!containerMenu.getCarried().isEmpty()) saved.put("cursor", containerMenu.getCarried().save(registryAccess()));
        var crafting = new ListTag();
        for (int slot = 1; slot <= 4; slot++) {
            var stack = inventoryMenu.getSlot(slot).getItem();
            if (stack.isEmpty()) continue;
            var entry = new CompoundTag();
            entry.putInt("slot", slot);
            entry.put("item", stack.save(registryAccess()));
            crafting.add(entry);
        }
        saved.put("crafting", crafting);
        if (containerMenu instanceof CraftingMenu) {
            var recovery = new ListTag();
            for (int slot = 1; slot <= 9; slot++) {
                var stack = containerMenu.getSlot(slot).getItem();
                if (!stack.isEmpty()) recovery.add(stack.save(registryAccess()));
            }
            saved.put("externalCrafting", recovery);
        }
        body.getPersistentData().put("too_many_agents_hands", saved);
    }

    /** Vanilla already copied these private stacks into the destination body. Do not return or drop them twice. */
    void closeAfterTransfer() {
        requireThread();
        if (closed) return;
        containerMenu.setCarried(ItemStack.EMPTY);
        inventoryMenu.setCarried(ItemStack.EMPTY);
        for (int slot = 1; slot <= 4; slot++) inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        if (containerMenu instanceof CraftingMenu)
            for (int slot = 1; slot <= 9; slot++) containerMenu.getSlot(slot).set(ItemStack.EMPTY);
        closeHands();
    }

    void closeHands() {
        requireThread();
        if (closed) return;
        cancelMine();
        cancelUse();
        if (containerMenu != inventoryMenu) closeContainer();
        inventoryMenu.removed(this);
        save();
        closed = true;
    }

    private void restore() {
        var saved = body.getPersistentData().getCompound("too_many_agents_hands");
        getInventory().load(saved.getList("inventory", 10));
        getEnderChestInventory().fromTag(saved.getList("enderItems", 10), registryAccess());
        getInventory().selected = Math.clamp(saved.getInt("selected"), 0, 8);
        menuGeneration = saved.getLong("menuGeneration");
        experienceProgress = saved.getFloat("XpP");
        experienceLevel = saved.getInt("XpLevel");
        totalExperience = saved.getInt("XpTotal");
        enchantmentSeed = saved.contains("XpSeed", 3) ? saved.getInt("XpSeed") : getRandom().nextInt();
        if (saved.contains("cursor", 10)) inventoryMenu.setCarried(ItemStack.parseOptional(registryAccess(), saved.getCompound("cursor")));
        for (var tag : saved.getList("crafting", 10)) {
            var entry = (CompoundTag) tag;
            int slot = entry.getInt("slot");
            if (slot >= 1 && slot <= 4) inventoryMenu.getSlot(slot).set(ItemStack.parseOptional(registryAccess(), entry.getCompound("item")));
        }
        for (var tag : saved.getList("externalCrafting", 10)) {
            var stack = ItemStack.parseOptional(registryAccess(), (CompoundTag) tag);
            getInventory().placeItemBackInInventory(stack);
        }
    }

    private void syncEquipment() {
        for (var slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.BODY) continue;
            var current = getItemBySlot(slot);
            var old = previousEquipment.getOrDefault(slot, ItemStack.EMPTY);
            if (!ItemStack.matches(old, current)) {
                // FakePlayer skips LivingEntity.tick's equipment attribute updates; perform that native sequence here.
                old.forEachModifier(slot, (attribute, modifier) -> {
                    var instance = getAttributes().getInstance(attribute);
                    if (instance != null) instance.removeModifier(modifier);
                });
                EnchantmentHelper.stopLocationBasedEffects(old, this, slot);
                current.forEachModifier(slot, (attribute, modifier) -> {
                    var instance = getAttributes().getInstance(attribute);
                    if (instance != null) {
                        instance.removeModifier(modifier.id());
                        instance.addTransientModifier(modifier);
                    }
                });
                EnchantmentHelper.runLocationChangedEffects(serverLevel(), current, this, slot);
                NeoForge.EVENT_BUS.post(new LivingEquipmentChangeEvent(this, slot, old, current));
                previousEquipment.put(slot, current.copy());
            }
            if (!ItemStack.matches(body.getItemBySlot(slot), current)) body.setItemSlot(slot, current.copy());
            body.setDropChance(slot, 0);
        }
    }

    private boolean validMenu() {
        if (!containerMenu.stillValid(this)) return false;
        if (containerMenu == inventoryMenu) return true;
        if (menuOrigin != null) return serverLevel().hasChunkAt(menuOrigin) && canInteractWithBlock(menuOrigin, 0)
            && serverLevel().mayInteract(this, menuOrigin);
        return menuEntity == null || canReach(menuEntity);
    }

    private BlockHitResult checkedHit(BlockPos pos, Direction face) {
        checkBlockAccess(pos);
        if (!canInteractWithBlock(pos, 0)) throw error("block_out_of_reach");
        var eye = getEyePosition();
        var candidates = face == null ? Direction.values() : new Direction[]{face};
        var shape = level().getBlockState(pos).getShape(level(), pos, net.minecraft.world.phys.shapes.CollisionContext.of(body));
        // Aim just inside the real outline: chests, slabs and stairs do not fill their block cube.
        for (var box : shape.toAabbs()) {
            for (var candidate : candidates) {
                var center = box.getCenter();
                var local = switch (candidate) {
                    case WEST -> new Vec3(box.minX + 0.0001, center.y, center.z);
                    case EAST -> new Vec3(box.maxX - 0.0001, center.y, center.z);
                    case DOWN -> new Vec3(center.x, box.minY + 0.0001, center.z);
                    case UP -> new Vec3(center.x, box.maxY - 0.0001, center.z);
                    case NORTH -> new Vec3(center.x, center.y, box.minZ + 0.0001);
                    case SOUTH -> new Vec3(center.x, center.y, box.maxZ - 0.0001);
                };
                var point = Vec3.atLowerCornerOf(pos).add(local);
                if (!loadedLine(eye, point)) continue;
                var hit = level().clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, body));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                    && (face == null || hit.getDirection() == face)) return hit;
            }
        }
        throw error("block_face_obstructed");
    }

    private void checkBlockAccess(BlockPos pos) {
        if (pos.getY() < level().getMinBuildHeight() || pos.getY() >= level().getMaxBuildHeight()) throw error("outside_build_height");
        if (!serverLevel().hasChunkAt(pos)) throw error("target_chunk_unloaded");
        if (!level().getWorldBorder().isWithinBounds(pos)) throw error("target_outside_world_border");
        if (!serverLevel().mayInteract(this, pos)) throw error("world_interaction_denied");
    }

    private boolean clearLine(Vec3 point) {
        var from = getEyePosition();
        return loadedLine(from, point) && level().clip(new ClipContext(from, point,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body)).getType() == HitResult.Type.MISS;
    }

    private boolean loadedLine(Vec3 from, Vec3 to) {
        var box = new AABB(from, to);
        return serverLevel().hasChunksAt(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ));
    }

    private void requireIdleHands() {
        if (miningPos != null) throw error("hands_busy_mining");
        if (isUsingItem() || body.isUsingItem()) throw error("hands_busy_using_item");
    }

    private void requireThread() {
        if (!serverLevel().getServer().isSameThread()) throw error("hands_require_server_thread");
    }

    private JsonObject miningResult(String state) {
        var result = status(state);
        result.addProperty("progress", Math.min(1, miningProgress));
        result.addProperty("face", miningFace.get3DDataValue());
        result.add("position", Observations.position(Vec3.atLowerCornerOf(miningPos)));
        result.addProperty("block", BuiltInRegistries.BLOCK.getKey(miningState.getBlock()).toString());
        return result;
    }

    private static int inventoryMenuSlot(int inventory) {
        if (inventory < 9) return inventory + 36;
        if (inventory < 36) return inventory;
        if (inventory == 40) return 45;
        return 44 - inventory;
    }

    private static JsonObject item(ItemStack stack) {
        var result = new JsonObject();
        result.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        result.addProperty("count", stack.getCount());
        result.addProperty("maxStackSize", stack.getMaxStackSize());
        if (!stack.isEmpty()) {
            result.addProperty("name", stack.getHoverName().getString());
            result.addProperty("damage", stack.getDamageValue());
            result.addProperty("maxDamage", stack.getMaxDamage());
        }
        return result;
    }

    private static JsonObject interaction(InteractionResult outcome) {
        var result = status(outcome.consumesAction() ? "consumed" : outcome == InteractionResult.FAIL ? "failed" : "passed");
        result.addProperty("interaction", outcome.name().toLowerCase(java.util.Locale.ROOT));
        return result;
    }

    private static JsonObject status(String state) {
        var result = new JsonObject();
        result.addProperty("status", state);
        return result;
    }

    private static IllegalStateException error(String message) { return new IllegalStateException(message); }
}
