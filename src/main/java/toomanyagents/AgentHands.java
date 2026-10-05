package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

/** Native player interactions for one visible body. Never added to the world or player list. */
final class AgentHands extends FakePlayer {
    private final Mob body;
    private final EnumMap<EquipmentSlot, ItemStack> previousEquipment = new EnumMap<>(EquipmentSlot.class);
    private BlockPos miningPos;
    private BlockState miningState;
    private Direction miningFace;
    private float miningProgress;
    private int lastMiningTick = -1;
    private boolean miningNeedsStop;
    private int lastHandsTick = -1;
    private int nextMenuId;
    private BlockPos menuOrigin;
    private Entity menuEntity;
    private boolean closed;

    AgentHands(Mob body) {
        super((ServerLevel) body.level(), new GameProfile(UUID.nameUUIDFromBytes(
            ("too_many_agents-hands:" + body.getUUID()).getBytes(StandardCharsets.UTF_8)), "[TooManyAgents]"));
        this.body = body;
        requireThread();
        syncBody();
        restore();
        save();
    }

    @Override public MinecraftServer getServer() { return serverLevel().getServer(); }

    @Override public boolean isEyeInFluid(TagKey<Fluid> fluid) {
        return body == null ? super.isEyeInFluid(fluid) : body.isEyeInFluid(fluid);
    }

    void syncBody() {
        requireThread();
        if (closed || body.isRemoved() || !body.isAlive()) throw error("body_missing_or_unloaded");
        if (body.level() != level()) throw error("body_dimension_changed");
        // Vanilla reach/mining/projectiles use player eyes. Match the actual NPC viewpoint.
        setPos(body.getX(), body.getEyeY() - getEyeHeight(), body.getZ());
        setYRot(body.getViewYRot(1));
        setXRot(body.getXRot());
        setOnGround(body.onGround());
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

    JsonObject beginMine(BlockPos pos) {
        syncBody();
        cancelMine();
        var hit = checkedHit(pos, null);
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
            checkedHit(miningPos, null);
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
        checkedHit(miningPos, null);
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
        syncBody();
        requireIdleHands();
        var hit = checkedHit(pos, face);
        if (!getMainHandItem().isItemEnabled(level().enabledFeatures())) throw error("item_is_disabled");
        if (getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem) {
            var placement = new BlockPlaceContext(this, InteractionHand.MAIN_HAND, getMainHandItem(), hit);
            checkBlockAccess(placement.getClickedPos());
        }
        var oldMenu = containerMenu;
        var oldOrigin = menuOrigin;
        var oldEntity = menuEntity;
        menuOrigin = pos.immutable();
        menuEntity = null;
        setShiftKeyDown(secondaryUse);
        InteractionResult result;
        try { result = gameMode.useItemOn(this, level(), getMainHandItem(), InteractionHand.MAIN_HAND, hit); }
        finally {
            setShiftKeyDown(false);
            if (containerMenu == oldMenu) { menuOrigin = oldOrigin; menuEntity = oldEntity; }
            save();
        }
        if (result.shouldSwing()) body.swing(InteractionHand.MAIN_HAND);
        var response = interaction(result);
        response.add("menu", menuSnapshot());
        return response;
    }

    JsonObject useHeld() {
        syncBody();
        requireIdleHands();
        if (getMainHandItem().isEmpty()) throw error("mainhand_is_empty");
        if (!getMainHandItem().isItemEnabled(level().enabledFeatures())) throw error("item_is_disabled");
        var result = gameMode.useItem(this, level(), getMainHandItem(), InteractionHand.MAIN_HAND);
        if (result.shouldSwing()) body.swing(InteractionHand.MAIN_HAND);
        save();
        var response = interaction(result);
        response.addProperty("usingItem", isUsingItem());
        return response;
    }

    JsonObject releaseHeld() {
        syncBody();
        releaseUsingItem();
        save();
        return status("released");
    }

    JsonObject interact(Entity target) {
        syncBody();
        requireIdleHands();
        if (!canReach(target)) throw error("entity_out_of_reach_or_obstructed");
        var oldMenu = containerMenu;
        var oldOrigin = menuOrigin;
        var oldEntity = menuEntity;
        menuOrigin = null;
        menuEntity = target;
        InteractionResult result;
        try { result = interactOn(target, InteractionHand.MAIN_HAND); }
        finally {
            if (containerMenu == oldMenu) { menuOrigin = oldOrigin; menuEntity = oldEntity; }
            save();
        }
        if (result.shouldSwing()) body.swing(InteractionHand.MAIN_HAND);
        var response = interaction(result);
        response.add("menu", menuSnapshot());
        return response;
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
        body.take(item, count);
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
        if (containerMenu != inventoryMenu) closeContainer();
        nextMenuId = nextMenuId % 100 + 1;
        var menu = provider.createMenu(nextMenuId, getInventory(), this);
        if (menu == null) return OptionalInt.empty();
        containerMenu = menu;
        NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(this, menu));
        return OptionalInt.of(nextMenuId);
    }

    JsonObject clickMenu(int expectedMenuId, int slot, int button, ClickType click) {
        syncBody();
        requireIdleHands();
        if (containerMenu.containerId != expectedMenuId) throw error("menu_changed");
        if (!validMenu()) { closeContainer(); save(); throw error("menu_no_longer_valid"); }
        if (slot < 0 || slot >= containerMenu.slots.size()) throw error("invalid_menu_slot");
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
        syncBody();
        closeContainer();
        menuOrigin = null;
        menuEntity = null;
        save();
        return menuSnapshot();
    }

    JsonObject menuSnapshot() {
        requireThread();
        var result = new JsonObject();
        result.addProperty("id", containerMenu.containerId);
        result.addProperty("type", containerMenu == inventoryMenu ? "minecraft:inventory"
            : BuiltInRegistries.MENU.getKey(containerMenu.getType()).toString());
        result.addProperty("valid", validMenu());
        result.add("carried", item(containerMenu.getCarried()));
        var slots = new JsonArray();
        for (int index = 0; index < containerMenu.slots.size(); index++) {
            var slot = containerMenu.getSlot(index);
            var entry = item(slot.getItem());
            entry.addProperty("slot", index);
            if (slot.container == getInventory()) {
                entry.addProperty("inventorySlot", slot.getContainerSlot());
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
            slots.add(entry);
        }
        result.add("slots", slots);
        return result;
    }

    JsonObject snapshot() {
        syncBody();
        var result = new JsonObject();
        result.addProperty("mode", BodySettings.mode(body.getPersistentData().getString("too_many_agents_mode")).id);
        result.addProperty("selected", getInventory().selected);
        result.addProperty("usingItem", isUsingItem());
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
        menu.addProperty("type", containerMenu == inventoryMenu ? "minecraft:inventory"
            : BuiltInRegistries.MENU.getKey(containerMenu.getType()).toString());
        menu.addProperty("valid", validMenu());
        menu.addProperty("slotCount", containerMenu.slots.size());
        menu.add("carried", item(containerMenu.getCarried()));
        result.add("menu", menu);
        return result;
    }

    void save() {
        requireThread();
        syncEquipment();
        var saved = new CompoundTag();
        saved.put("inventory", getInventory().save(new ListTag()));
        saved.putInt("selected", getInventory().selected);
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

    void closeHands() {
        requireThread();
        if (closed) return;
        cancelMine();
        stopUsingItem();
        if (containerMenu != inventoryMenu) closeContainer();
        inventoryMenu.removed(this);
        save();
        closed = true;
    }

    private void restore() {
        var saved = body.getPersistentData().getCompound("too_many_agents_hands");
        getInventory().load(saved.getList("inventory", 10));
        getInventory().selected = Math.clamp(saved.getInt("selected"), 0, 8);
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
        if (isUsingItem()) throw error("hands_busy_using_item");
    }

    private void requireThread() {
        if (!serverLevel().getServer().isSameThread()) throw error("hands_require_server_thread");
    }

    private JsonObject miningResult(String state) {
        var result = status(state);
        result.addProperty("progress", Math.min(1, miningProgress));
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
