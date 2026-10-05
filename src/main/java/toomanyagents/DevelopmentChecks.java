package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/** An opt-in native experiment in the disposable development world, independent of Codex. */
final class DevelopmentChecks {
    private static final org.slf4j.Logger LOGGING = com.mojang.logging.LogUtils.getLogger();
    private static final BlockPos BASE = new BlockPos(24, -60, 24);
    private static final BlockPos LOG = BASE.offset(0, 1, 3);
    private static final BlockPos CHEST = BASE.offset(3, 0, 0);
    private static final BlockPos TABLE = BASE.offset(3, 0, 2);
    private static final BlockPos WHEAT = BASE.offset(-2, 0, 2);
    private static final String FIXTURE = "too_many_agents_development_fixture";
    private static volatile JsonObject published = idle();
    private static DevelopmentChecks active;

    private final ServerLevel level;
    private final ServerPlayer human;
    private final GameType originalHumanMode;
    private final JsonArray checks = new JsonArray();
    private final int started;
    private Mob body;
    private AgentHands hands;
    private int stage;
    private int stageStarted;
    private int miningStarted;
    private int miningTicks;
    private int minedAt;
    private ListTag inventoryBeforeReload;
    private CompoundTag bodyForReload;
    private String currentCheck = "fixture";
    private String failure;

    private DevelopmentChecks(ServerLevel level, ServerPlayer human) {
        this.level = level;
        this.human = human;
        this.originalHumanMode = human.gameMode.getGameModeForPlayer();
        this.started = level.getServer().getTickCount();
        this.stageStarted = started;
    }

    static JsonObject start(ServerLevel level, ServerPlayer human) {
        requireDevelopment(level);
        if (active != null && active.level.getServer() != level.getServer()) active = null;
        if (active != null) throw new IllegalStateException("development_checks_already_running");
        var run = new DevelopmentChecks(level, human);
        active = run;
        try {
            run.fixture();
            run.publish("running");
        } catch (RuntimeException | LinkageError failure) {
            run.fail(failure);
        }
        return snapshot();
    }

    static void tick(MinecraftServer server) {
        var run = active;
        if (run == null || run.level.getServer() != server) return;
        try {
            requireDevelopment(run.level);
            if (server.getTickCount() - run.started > 600) throw new IllegalStateException("native_experiment_timed_out_after_600_ticks");
            if (run.hands != null) run.hands.tickHands();
            run.step();
            if (active != null) run.publish("running");
        } catch (RuntimeException | LinkageError failure) {
            run.fail(failure);
        }
    }

    /** Safe for HTTP: only copies a fully serialized snapshot, never reads a game object. */
    static JsonObject snapshot() { return published.deepCopy(); }

    private void fixture() {
        currentCheck = "fixture";
        human.setGameMode(GameType.CREATIVE);
        level.getChunk(BASE.getX() >> 4, BASE.getZ() >> 4);
        level.getChunk((BASE.getX() + 12) >> 4, (BASE.getZ() + 12) >> 4);
        for (var entity : level.getEntities(null, new AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(BASE.offset(-6, -2, -6)), net.minecraft.world.phys.Vec3.atLowerCornerOf(BASE.offset(15, 8, 15))))) {
            if (entity.getPersistentData().getBoolean(FIXTURE)) entity.discard();
        }
        for (var pos : BlockPos.betweenClosed(BASE.offset(-4, -1, -4), BASE.offset(12, 4, 8))) {
            level.setBlockAndUpdate(pos, pos.getY() == BASE.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            new AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(BASE.offset(-6, -2, -6)),
                net.minecraft.world.phys.Vec3.atLowerCornerOf(BASE.offset(15, 8, 15))))) item.discard();
        level.setBlockAndUpdate(LOG, Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
        level.setBlockAndUpdate(WHEAT.below(), Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(WHEAT, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        body = EntityType.VILLAGER.create(level);
        require(body != null, "fixture_body_creation_failed");
        body.setNoAi(true);
        body.setCanPickUpLoot(false);
        body.setNoGravity(true);
        body.setInvulnerable(true);
        body.setPersistenceRequired();
        body.getPersistentData().putBoolean(FIXTURE, true);
        body.getPersistentData().putString("too_many_agents_mode", "survival");
        positionBody(BASE);
        require(level.addFreshEntity(body), "fixture_spawn_rejected");
        hands = new AgentHands(body);
        // Fixture seeding only; every tested mutation below goes through the native hands API.
        hands.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));
        hands.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 8));
        hands.getInventory().setItem(3, new ItemStack(Items.OAK_STAIRS, 2));
        hands.save();
        passed("Fixture in isolated development world; human Creative, hands Survival, commands off.");
        next("survival_timed_mining");
    }

    private void step() {
        switch (stage) {
            case 1 -> {
                require(!hands.isCreative() && human.isCreative(), "survival_mode_inherited_from_human");
                var far = BASE.offset(10, 1, 0);
                level.setBlockAndUpdate(far, Blocks.OAK_LOG.defaultBlockState());
                try {
                    hands.beginMine(far);
                    throw new IllegalStateException("out_of_reach_mining_was_accepted");
                } catch (IllegalStateException expected) {
                    require(expected.getMessage().equals("block_out_of_reach"), "unexpected_reach_failure: " + expected.getMessage());
                }
                miningStarted = level.getServer().getTickCount();
                var progress = hands.beginMine(LOG);
                require(progress.get("status").getAsString().equals("mining"), "survival_log_did_not_require_time");
                next("survival_timed_mining");
            }
            case 2 -> {
                var progress = hands.tickMine();
                if (!progress.get("status").getAsString().equals("completed")) return;
                miningTicks = level.getServer().getTickCount() - miningStarted + 1;
                require(miningTicks > 1, "survival_log_broke_instantly");
                require(level.getBlockState(LOG).isAir(), "log_not_removed");
                require(hands.getMainHandItem().getDamageValue() == 1, "axe_durability_not_consumed_once");
                minedAt = level.getServer().getTickCount();
                passed("Survival oak log required " + miningTicks + " ticks; native break consumed one iron-axe durability; out-of-reach break rejected.");
                positionBody(LOG.below());
                next("native_drop_pickup");
            }
            case 3 -> {
                if (level.getServer().getTickCount() - minedAt < 20) return;
                hands.pickup(2);
                require(count(Items.OAK_LOG) == 1, "native_log_drop_not_picked_up");
                passed("Native ItemEntity pickup produced exactly one oak log after the pickup delay.");
                positionBody(BASE);
                next("survival_stair_placement");
            }
            case 4 -> {
                hands.equip(findInventory(Items.OAK_STAIRS), "mainhand");
                body.setYRot(90); // West, intentionally independent of the clicked top face.
                body.setYHeadRot(90);
                var support = BASE.offset(0, -1, 1);
                hands.useBlock(support, Direction.UP, false);
                var placed = level.getBlockState(support.above());
                require(placed.is(Blocks.OAK_STAIRS), "stairs_not_placed");
                require(placed.getValue(StairBlock.FACING) == Direction.WEST && placed.getValue(StairBlock.HALF) == Half.BOTTOM,
                    "stairs_orientation_did_not_use_native_placement_context");
                require(count(Items.OAK_STAIRS) == 1, "survival_placement_did_not_consume_one_stair");
                require(ItemStack.matches(body.getMainHandItem(), hands.getMainHandItem()), "body_held_item_not_synchronized");
                passed("Native top-face placement produced bottom-half west-facing stairs, consumed one item, and synchronized visible equipment.");
                hands.equip(findInventory(Items.IRON_AXE), "mainhand");
                next("chest_roundtrip");
            }
            case 5 -> {
                hands.useBlock(CHEST, Direction.WEST, false);
                require(hands.containerMenu != hands.inventoryMenu, "chest_menu_did_not_open");
                int menuId = hands.containerMenu.containerId;
                int cobbleSlot = findInventoryMenuSlot(Items.COBBLESTONE);
                hands.clickMenu(menuId, cobbleSlot, 0, ClickType.QUICK_MOVE);
                var chest = (ChestBlockEntity) level.getBlockEntity(CHEST);
                require(chest != null && countContainer(chest, Items.COBBLESTONE) == 8 && count(Items.COBBLESTONE) == 0,
                    "chest_deposit_failed");
                int chestSlot = -1;
                for (int index = 0; index < hands.containerMenu.slots.size(); index++) {
                    var slot = hands.containerMenu.getSlot(index);
                    if (slot.container != hands.getInventory() && slot.getItem().is(Items.COBBLESTONE)) { chestSlot = index; break; }
                }
                require(chestSlot >= 0, "deposited_chest_stack_not_found");
                hands.clickMenu(menuId, chestSlot, 0, ClickType.QUICK_MOVE);
                require(countContainer(chest, Items.COBBLESTONE) == 0 && count(Items.COBBLESTONE) == 8, "chest_withdrawal_failed");
                hands.closeMenu();
                require(hands.containerMenu == hands.inventoryMenu, "chest_menu_did_not_close");
                passed("Chest opened through block use; native QUICK_MOVE deposited and withdrew eight cobblestone; close restored inventory menu.");
                next("inventory_crafting");
            }
            case 6 -> {
                putOne(Items.OAK_LOG, 1);
                require(hands.inventoryMenu.getSlot(0).getItem().is(Items.OAK_PLANKS), "planks_recipe_not_resolved");
                hands.clickMenu(0, 0, 0, ClickType.QUICK_MOVE);
                require(count(Items.OAK_LOG) == 0 && count(Items.OAK_PLANKS) == 4, "planks_recipe_did_not_consume_log");
                putOne(Items.OAK_PLANKS, 1);
                putOne(Items.OAK_PLANKS, 3);
                require(hands.inventoryMenu.getSlot(0).getItem().is(Items.STICK), "sticks_recipe_not_resolved");
                hands.clickMenu(0, 0, 0, ClickType.QUICK_MOVE);
                require(count(Items.OAK_PLANKS) == 2 && count(Items.STICK) == 4, "sticks_recipe_did_not_consume_two_planks");
                passed("Native inventory crafting converted the mined log to four planks, then two planks to four sticks through result-slot clicks.");
                next("table_crafting");
            }
            case 7 -> {
                hands.useBlock(TABLE, Direction.WEST, false);
                require(hands.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu, "crafting_table_menu_did_not_open");
                putOne(Items.OAK_PLANKS, 2);
                putOne(Items.OAK_PLANKS, 5);
                putOne(Items.STICK, 8);
                require(hands.containerMenu.getSlot(0).getItem().is(Items.WOODEN_SWORD), "table_sword_recipe_not_resolved");
                hands.clickMenu(hands.containerMenu.containerId, 0, 0, ClickType.QUICK_MOVE);
                require(count(Items.WOODEN_SWORD) == 1 && count(Items.OAK_PLANKS) == 0 && count(Items.STICK) == 3,
                    "table_crafting_did_not_consume_ingredients");
                hands.closeMenu();
                passed("Native crafting-table 3×3 slots produced a wooden sword and consumed two planks plus one stick.");
                next("inventory_body_nbt_reload");
            }
            case 8 -> {
                hands.closeHands();
                inventoryBeforeReload = hands.getInventory().save(new ListTag());
                bodyForReload = new CompoundTag();
                require(body.save(bodyForReload), "fixture_body_nbt_save_failed");
                body.discard();
                hands = null;
                next("inventory_body_nbt_reload");
            }
            case 9 -> {
                var restored = EntityType.loadEntityRecursive(bodyForReload, level, entity -> entity);
                require(restored instanceof Mob, "fixture_body_nbt_load_failed");
                body = (Mob) restored;
                // Villager NBT loading enables mob pickup; real agents disable it in restoreBody too.
                body.setCanPickUpLoot(false);
                require(level.addFreshEntity(body), "restored_fixture_spawn_rejected");
                hands = new AgentHands(body);
                require(inventoryBeforeReload.equals(hands.getInventory().save(new ListTag())), "inventory_changed_after_body_nbt_reload");
                require(!hands.isCreative() && !BodySettings.mode(body.getPersistentData().getString("too_many_agents_mode")).commands, "settings_changed_after_reload");
                require(ItemStack.matches(body.getMainHandItem(), hands.getMainHandItem()), "held_item_changed_after_reload");
                passed("Saved and recreated the body through Minecraft entity NBT, then recreated FakePlayer hands; inventory, durability, held equipment, Survival and commands-off survived.");
                next("creative_independent_mode");
            }
            case 10 -> {
                positionBody(BASE);
                human.setGameMode(GameType.SURVIVAL);
                body.getPersistentData().putString("too_many_agents_mode", "creative");
                hands.syncBody();
                require(hands.isCreative() && !human.isCreative(), "creative_mode_not_independent");
                hands.creativeItem("minecraft:oak_planks", 16);
                var support = BASE.offset(-1, -1, -1);
                hands.useBlock(support, Direction.UP, false);
                require(level.getBlockState(support.above()).is(Blocks.OAK_PLANKS), "creative_placement_failed");
                require(hands.getMainHandItem().getCount() == 16, "creative_placement_consumed_material");
                level.setBlockAndUpdate(LOG, Blocks.BEDROCK.defaultBlockState());
                var mine = hands.beginMine(LOG);
                require(mine.get("status").getAsString().equals("completed") && level.getBlockState(LOG).isAir(), "creative_bedrock_break_not_instant");
                passed("With human Survival and agent Creative, native placement retained all 16 planks and bedrock broke immediately.");
                next("menu_distance_validity");
            }
            case 11 -> {
                hands.useBlock(CHEST, Direction.WEST, false);
                int id = hands.containerMenu.containerId;
                require(hands.containerMenu != hands.inventoryMenu, "chest_menu_did_not_reopen");
                positionBody(BASE.offset(-12, 0, 0));
                try {
                    hands.clickMenu(id, 0, 0, ClickType.QUICK_MOVE);
                    throw new IllegalStateException("distant_chest_click_was_accepted");
                } catch (IllegalStateException expected) {
                    require(expected.getMessage().equals("menu_no_longer_valid"), "unexpected_invalid_menu_failure: " + expected.getMessage());
                }
                require(hands.containerMenu == hands.inventoryMenu, "invalid_menu_was_not_closed");
                positionBody(BASE);
                passed("Walking fixture out of range invalidated the chest menu, rejected the click, and ran native close handling.");
                next("survival_empty_hand_wheat");
            }
            case 12 -> {
                body.getPersistentData().putString("too_many_agents_mode", "survival");
                hands.syncBody();
                int emptySlot = hands.getInventory().getFreeSlot();
                require(emptySlot >= 0, "fixture_has_no_empty_inventory_slot");
                hands.equip(emptySlot, "mainhand");
                require(!hands.isCreative() && hands.getMainHandItem().isEmpty(), "wheat_harvest_requires_survival_empty_hand");
                var crop = level.getBlockState(WHEAT);
                require(crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) == 7, "ripe_wheat_fixture_missing");
                var mine = hands.beginMine(WHEAT);
                require(mine.get("status").getAsString().equals("completed") && level.getBlockState(WHEAT).isAir(),
                    "ripe_wheat_did_not_break_instantly_with_empty_hand");
                require(level.getBlockState(WHEAT.below()).is(Blocks.FARMLAND), "harvesting_wheat_changed_farmland");
                minedAt = level.getServer().getTickCount();
                positionBody(WHEAT);
                next("survival_empty_hand_wheat");
            }
            case 13 -> {
                if (level.getServer().getTickCount() - minedAt < 20) return;
                hands.pickup(2);
                require(count(Items.WHEAT) == 1, "native_ripe_wheat_drop_not_picked_up");
                passed("Survival empty-hand harvest instantly removed age-7 wheat, preserved farmland, and picked up one native wheat drop plus "
                    + count(Items.WHEAT_SEEDS) + " seeds after the pickup delay.");
                finish();
            }
            default -> throw new IllegalStateException("unknown_development_check_stage: " + stage);
        }
    }

    private void putOne(Item item, int destination) {
        int source = findInventoryMenuSlot(item);
        int id = hands.containerMenu.containerId;
        require(hands.containerMenu.getCarried().isEmpty(), "craft_cursor_not_empty");
        hands.clickMenu(id, source, 0, ClickType.PICKUP);
        hands.clickMenu(id, destination, 1, ClickType.PICKUP);
        if (!hands.containerMenu.getCarried().isEmpty()) hands.clickMenu(id, source, 0, ClickType.PICKUP);
        require(hands.containerMenu.getCarried().isEmpty(), "craft_cursor_not_returned");
    }

    private int findInventory(Item item) {
        for (int slot = 0; slot < 36; slot++) if (hands.getInventory().getItem(slot).is(item)) return slot;
        throw new IllegalStateException("fixture_item_missing: " + item);
    }

    private int findInventoryMenuSlot(Item item) {
        for (int index = 0; index < hands.containerMenu.slots.size(); index++) {
            var slot = hands.containerMenu.getSlot(index);
            if (slot.container == hands.getInventory() && slot.getItem().is(item)) return index;
        }
        throw new IllegalStateException("fixture_menu_item_missing: " + item);
    }

    private int count(Item item) { return countContainer(hands.getInventory(), item); }

    private static int countContainer(net.minecraft.world.Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private void positionBody(BlockPos pos) {
        body.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        body.setYHeadRot(0);
        body.setOnGround(true);
    }

    private void next(String check) {
        stage++;
        currentCheck = check;
        stageStarted = level.getServer().getTickCount();
    }

    private void passed(String detail) {
        var result = new JsonObject();
        result.addProperty("check", currentCheck);
        result.addProperty("status", "passed");
        result.addProperty("detail", detail);
        result.addProperty("tick", level.getServer().getTickCount());
        result.addProperty("elapsedTicks", level.getServer().getTickCount() - stageStarted);
        checks.add(result);
    }

    private void finish() {
        if (hands != null) hands.closeHands();
        human.setGameMode(originalHumanMode);
        publish("passed");
        active = null;
    }

    private void fail(Throwable problem) {
        LOGGING.error("Native development check failed: {}", currentCheck, problem);
        failure = problem.getClass().getSimpleName() + ": " + problem.getMessage();
        var result = new JsonObject();
        result.addProperty("check", currentCheck);
        result.addProperty("status", "failed");
        result.addProperty("detail", failure);
        result.addProperty("tick", level.getServer().getTickCount());
        checks.add(result);
        try { if (hands != null) hands.closeHands(); } catch (RuntimeException ignored) { }
        try { human.setGameMode(originalHumanMode); } catch (RuntimeException ignored) { }
        publish("failed");
        active = null;
    }

    private void publish(String state) {
        var result = new JsonObject();
        result.addProperty("status", state);
        result.addProperty("currentCheck", currentCheck);
        result.addProperty("startedTick", started);
        result.addProperty("tick", level.getServer().getTickCount());
        result.addProperty("elapsedTicks", level.getServer().getTickCount() - started);
        result.addProperty("miningTicks", miningTicks);
        result.add("checks", checks.deepCopy());
        if (failure != null) result.addProperty("error", failure);
        if (body != null) result.addProperty("bodyUuid", body.getStringUUID());
        result.add("fixture", Observations.position(net.minecraft.world.phys.Vec3.atLowerCornerOf(BASE)));
        published = result;
    }

    private static void requireDevelopment(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("development_checks_require_server_thread");
        if (!DevelopmentWorld.ENABLED || !level.dimension().equals(Level.OVERWORLD)
            || !level.getServer().getWorldPath(LevelResource.ROOT).normalize().getFileName().toString().equals(DevelopmentWorld.NAME)) {
            throw new IllegalStateException("development_checks_require_isolated_development_world");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static JsonObject idle() {
        var result = new JsonObject();
        result.addProperty("status", "idle");
        return result;
    }
}
