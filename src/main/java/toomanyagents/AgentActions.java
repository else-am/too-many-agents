package toomanyagents;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** One body's server-thread action state. Models choose goals; native controls advance each tick. */
final class AgentActions {
    static final List<String> TYPES = List.of("walk", "look", "mine", "place", "equip", "creative_item", "use", "release", "pickup", "give", "interact", "menu", "menu_click", "menu_close");
    private static final Set<String> SCRIPT_TYPES = Set.of("route", "select_hotbar", "menu_button", "anvil_name", "select_trade", "edit_book", "attack", "swing", "place_entity", "control", "consume", "dismount", "update_sign", "fish", "vehicle_control", "wake");
    private static final Set<String> CONTROLS = Set.of("forward", "back", "left", "right", "jump", "sprint", "sneak");
    private final Set<String> heldControls = new HashSet<>();
    private net.minecraft.world.entity.vehicle.Boat controlledBoat;
    private float vehicleLeft, vehicleForward;
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
    private boolean approachTargets;
    private ScriptNavigation route;
    private boolean travelled;
    private String scriptId, lastScriptId;
    private long scriptDeadline, scriptHeartbeat;
    private CompletableFuture<JsonObject> completion;
    private ScriptStream stateStream;
    private Supplier<JsonObject> stateSnapshot;
    private long snapshotRevision;
    private long actionSequence, completedActionSequence;

    AgentActions(Mob mob, String session, Supplier<BodyBox> box, Supplier<ServerPlayer> player) {
        this.mob = mob;
        this.session = session;
        this.box = box;
        this.player = player;
        hands = new AgentHands(mob, box);
        ambient = new AmbientBehavior(mob);
    }

    boolean busy() { return action != null && "running".equals(action.get("status").getAsString()); }
    boolean scripted() { return scriptId != null; }
    boolean controlsScript(String id) { return Objects.equals(scriptId, id); }

    void claimScript(String id, int timeoutMs) {
        expireScript();
        if (scripted() || busy()) throw error("body_already_busy");
        UUID.fromString(id);
        if (timeoutMs < 1 || timeoutMs > 300_000) throw error("invalid_script_deadline");
        scriptId = id;
        lastScriptId = id;
        snapshotRevision = 0;
        scriptDeadline = System.nanoTime() + timeoutMs * 1_000_000L;
        scriptHeartbeat = System.nanoTime() + 10_000_000_000L;
        GameAccess.stopFollowingMotion(mob);
    }

    void requireScript(String id) {
        expireScript();
        if (scriptId == null || !scriptId.equals(id)) throw error("script_no_longer_controls_body");
        scriptHeartbeat = System.nanoTime() + 10_000_000_000L;
    }

    void requireUnscripted() {
        expireScript();
        if (scripted()) throw error("body_controlled_by_script");
    }

    void endScript(String id) {
        if (!Objects.equals(lastScriptId, id) || (scripted() && !controlsScript(id)))
            throw error("script_no_longer_controls_body");
        if (controlsScript(id)) releaseScript();
    }

    void releaseScript() {
        scriptId = null;
        clearControls();
        cancel("");
        if (stateStream != null) stateStream.finish();
        stateStream = null;
        stateSnapshot = null;
    }

    long nextSnapshotRevision() { return ++snapshotRevision; }
    long completedActionSequence() { return completedActionSequence; }

    CompletableFuture<JsonObject> stream(ScriptStream stream, Supplier<JsonObject> snapshot) {
        if (stateStream != null) throw error("script_state_stream_already_open");
        stateStream = stream;
        stateSnapshot = snapshot;
        stream.offer(snapshot.get());
        return stream.completion;
    }

    CompletableFuture<JsonObject> awaitAction(String id) {
        var state = status(id);
        if (!"running".equals(text(state, "status"))) return CompletableFuture.completedFuture(state);
        return completion.thenApply(JsonObject::deepCopy);
    }

    private void expireScript() {
        long now = System.nanoTime();
        if (scripted() && (now >= scriptDeadline || now >= scriptHeartbeat)) {
            if (busy()) finish("interrupted", "script_expired", null);
            releaseScript();
        }
    }

    JsonObject startScriptAction(JsonObject request) { return start(request, false); }
    JsonObject status(String id) {
        if (id == null || id.isBlank()) return action == null ? object("status", "idle") : action.deepCopy();
        var found = history.get(id);
        if (found == null) throw error("unknown_action");
        return found.deepCopy();
    }

    JsonObject start(JsonObject request) {
        requireUnscripted();
        return start(request, true);
    }

    private JsonObject start(JsonObject request, boolean approachTargets) {
        if (busy()) throw error("action_already_running_cancel_or_wait");
        String type = text(request, "type");
        if (!TYPES.contains(type) && !(SCRIPT_TYPES.contains(type) && !approachTargets)) throw error("unknown_action_type");
        if ((type.equals("route") || type.equals("walk")) && !heldControls.isEmpty()) throw error("release_manual_controls_before_navigation");
        if (mob.isSleeping() && (type.equals("route") || type.equals("walk")
            || type.equals("control") && request.has("state") && request.get("state").getAsBoolean())) throw error("wake_before_movement");
        if ((type.equals("route") || type.equals("walk")) && mob.isPassenger()) throw error("dismount_before_navigation");
        if (type.equals("control") && (!CONTROLS.contains(text(request, "control")) || !request.has("state")
            || !request.get("state").isJsonPrimitive() || !request.getAsJsonPrimitive("state").isBoolean())) throw error("invalid_control");
        if (type.equals("control") && request.get("state").getAsBoolean() && mob.isPassenger()) {
            if (!(mob.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat boat))
                throw error("vehicle_controls_not_implemented_for_body");
            if (boat.getControllingPassenger() != mob) throw error("body_not_vehicle_controller");
        }
        if (request.has("position") && request.has("entity")) throw error("choose_position_or_entity");
        if (List.of("walk", "look", "interact").contains(type) && !request.has("position") && !request.has("entity") && !(type.equals("look") && !approachTargets && request.has("yaw") && request.has("pitch"))) throw error("position_or_entity_required");
        if (List.of("mine", "place", "place_entity", "update_sign").contains(type) && !request.has("position")) throw error("position_required");
        if (List.of("give", "attack").contains(type) && !request.has("entity")) throw error("entity_required");
        args = request.deepCopy();
        if (args.has("position")) {
            var pos = BlockPos.containing(position(args));
            if (List.of("mine", "place", "interact", "place_entity", "update_sign").contains(type)) {
                var level = (ServerLevel) mob.level();
                if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) throw error("outside_build_height");
                if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) throw error("target_unloaded_or_outside_world");
                if (args.has("expectedStateId") && Block.getId(level.getBlockState(pos)) != integer(args, "expectedStateId", -1)) throw error("target_changed");
            }
        }
        if (List.of("place", "interact", "place_entity").contains(type) && args.has("position")) blockFace();
        if (type.equals("pickup") && args.has("entity") && !(entity() instanceof ItemEntity)) throw error("pickup_target_must_be_item");
        if (type.equals("route")) {
            var selected = new ScriptNavigation(mob, hands, box);
            try { selected.start(args); }
            catch (RuntimeException failure) { selected.stop(); throw failure; }
            route = selected;
        }
        kind = type;
        completion = new CompletableFuture<>();
        action = object("id", UUID.randomUUID().toString(), "type", kind, "status", "running", "phase", "starting", "terminal", false, "session", session);
        action.addProperty("sequence", ++actionSequence);
        history.put(text(action, "id"), action);
        while (history.size() > 32) history.remove(history.keySet().iterator().next());
        ticks = stillTicks = 0;
        lastPosition = mob.position();
        original = args.has("position") && List.of("mine", "place", "interact", "place_entity").contains(kind)
            ? mob.level().getBlockState(BlockPos.containing(position(args))) : null;
        mining = false;
        this.approachTargets = approachTargets;
        GameAccess.stopFollowingMotion(mob);
        return action.deepCopy();
    }

    JsonObject cancel(String id) {
        if (id != null && !id.isBlank() && (action == null || !id.equals(text(action, "id")))) return status(id);
        if (busy()) finish("interrupted", "cancelled", null);
        // A completed 'use' action can leave a bow or other held item in use.
        hands.cancelUse();
        hands.cancelMine();
        GameAccess.stopFollowingMotion(mob);
        return status("");
    }

    JsonObject stopRoute(String id) {
        var state = status(id);
        if (action != null && id.equals(text(action, "id")) && busy()) {
            if (route == null) throw error("action_is_not_route");
            route.requestStop();
        }
        return state;
    }

    void close(String reason) {
        scriptId = null;
        clearControls();
        if (stateStream != null) stateStream.fail(error(reason));
        stateStream = null;
        stateSnapshot = null;
        if (busy()) finish("interrupted", reason, null);
        if (mob.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) hands.closeAfterTransfer();
        else hands.closeHands();
    }

    void tick(boolean minecraftAccess) {
        travelled = false;
        try {
            expireScript();
            if (!minecraftAccess && scripted()) releaseScript();
            hands.tickHands();
            // Explicit pickup must collect and report its own target before it disappears.
            if (minecraftAccess && (!busy() || !kind.equals("pickup"))) hands.pickupNearby();
            if (!busy()) return;
            if (++ticks > (route == null ? 1200 : 2400)) { finish("timeout", "Action exceeded its game-time limit.", null); return; }
            switch (kind) {
                case "route" -> {
                    // The executor owns this tick's travel, including edit waits.
                    // If it fails after moving, cleanup must not move a second time.
                    travelled = true;
                    var progress = route.tick();
                    action.addProperty("phase", text(progress, "phase"));
                    action.add("progress", progress.deepCopy());
                    if ("completed".equals(text(progress, "status"))) finish("completed", "route_finished", progress);
                }
                case "walk" -> {
                    var wanted = target();
                    var confined = box.get();
                    var target = GameAccess.inside(mob, confined, wanted);
                    double distance = args.has("entity") ? 2.0 : 0.9;
                    // The direct tool's tolerance can stop in a neighboring tile.
                    // A script's goal must reach the requested tile as well.
                    boolean tileReached = approachTargets || BlockPos.containing(wanted).equals(mob.blockPosition());
                    if (tileReached && mob.position().distanceTo(wanted) <= distance) finish("completed", "arrived", null);
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
                    if (!args.has("yaw") || !args.has("pitch")) { face(target()); finish("completed", "looking", null); }
                    else if (lookAngles()) finish("completed", "looking", null);
                }
                case "mine" -> {
                    var pos = checkedBlockTarget();
                    if (!mining && !mob.level().getBlockState(pos).equals(original)) throw error("target_changed");
                    Direction face = args.has("face") ? blockFace() : null;
                    if (!hands.blockReachable(pos, face)) {
                        if (mining || !approachTargets) throw error("target_out_of_reach");
                        navigate(Vec3.atCenterOf(pos), true); return;
                    }
                    GameAccess.stopFollowingMotion(mob);
                    if (!ignoreLook()) face(Vec3.atCenterOf(pos));
                    JsonObject result = mining ? hands.tickMine() : hands.beginMine(pos, face);
                    mining = true;
                    action.addProperty("phase", "mining");
                    action.add("progress", result.deepCopy());
                    if ("completed".equals(text(result, "status"))) finish("completed", "mined", result);
                }
                case "place", "interact", "place_entity" -> {
                    if (args.has("entity")) {
                        Entity entity = entity();
                        if (!hands.canReach(entity)) {
                            if (!approachTargets) throw error("target_out_of_reach");
                            navigate(entity.getBoundingBox().getCenter(), true); return;
                        }
                        if (!ignoreLook()) face(entity.getEyePosition());
                        Vec3 hit = args.has("entityAt") ? point(args.getAsJsonObject("entityAt")) : null;
                        finish("completed", "interacted", hands.interact(entity, hit));
                    } else {
                        var pos = checkedBlockTarget();
                        if (!mob.level().getBlockState(pos).equals(original)) throw error("target_changed");
                        Direction face = blockFace();
                        if (!kind.equals("place_entity") && !hands.blockReachable(pos, face)) {
                            if (!approachTargets) throw error("target_out_of_reach");
                            navigate(Vec3.atCenterOf(pos), true); return;
                        }
                        Vec3 cursor = null;
                        if (args.has("cursorPos")) {
                            var point = args.getAsJsonObject("cursorPos");
                            cursor = new Vec3(number(point, "x"), number(point, "y"), number(point, "z"));
                        }
                        if (!ignoreLook()) face(Vec3.atLowerCornerOf(pos).add(cursor != null ? cursor
                            : new Vec3(.5 + face.getStepX() * .5, .5 + face.getStepY() * .5, .5 + face.getStepZ() * .5)));
                        InteractionHand hand = args.has("offhand") && args.get("offhand").getAsBoolean() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                        InteractionHand swingHand = args.has("swingArm") && text(args, "swingArm").equals("left") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                        boolean showHand = !args.has("showHand") || args.get("showHand").getAsBoolean();
                        BlockPos destination = args.has("expectedDestination") ? BlockPos.containing(point(args.getAsJsonObject("expectedDestination"))) : null;
                        var result = kind.equals("place_entity") ? hands.placeEntity(pos, face, cursor, hand, swingHand, showHand)
                            : hands.useBlock(pos, face, mob.isShiftKeyDown() || (args.has("secondaryUse") && args.get("secondaryUse").getAsBoolean()), cursor,
                                hand, destination, swingHand, showHand);
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
                case "equip" -> {
                    if (!approachTargets && args.has("hotbar")) hands.selectHotbar(integer(args, "hotbar", 0));
                    finish("completed", "equipped", hands.equip(integer(args, "slot", 0), args.has("equipment") ? text(args,"equipment") : "mainhand"));
                }
                case "creative_item" -> finish("completed", "item_selected", hands.creativeItem(text(args,"item"), integer(args,"count",1)));
                case "attack" -> finish("completed", "attack_attempted", hands.attackTarget(entity(), !args.has("swing") || args.get("swing").getAsBoolean()));
                case "swing" -> finish("completed", "swung", hands.swingBody(args.has("showHand") && !args.get("showHand").getAsBoolean()
                    ? InteractionHand.MAIN_HAND : args.has("offhand") && args.get("offhand").getAsBoolean() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND));
                case "use" -> finish("completed", "use_started", hands.useHeld(args.has("offhand") && args.get("offhand").getAsBoolean() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND));
                case "wake" -> finish("completed", "awake", hands.wakeBody());
                case "fish" -> {
                    if (ticks == 1) hands.beginFishing();
                    action.addProperty("phase", "waiting_for_bite");
                    if (hands.tickFishing()) finish("completed", "fishing_retrieved", null);
                }
                case "consume" -> {
                    if (ticks == 1) hands.beginConsume();
                    String state = hands.consumptionStatus();
                    action.addProperty("phase", "consuming");
                    if (!state.equals("using")) finish(state.equals("completed") ? "completed" : "failed", "consumption_" + state, null);
                }
                case "release" -> finish("completed", "released", hands.releaseHeld());
                case "dismount" -> finish("completed", "dismounted", hands.dismountBody());
                case "update_sign" -> {
                    if (!args.has("lines") || !args.get("lines").isJsonArray() || args.getAsJsonArray("lines").size() != 4)
                        throw error("invalid_sign_lines");
                    var lines = new ArrayList<String>();
                    for (var line : args.getAsJsonArray("lines")) {
                        if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString() || line.getAsString().length() > 45)
                            throw error("invalid_sign_line");
                        lines.add(line.getAsString());
                    }
                    finish("completed", "sign_updated", hands.updateSign(BlockPos.containing(position(args)),
                        !args.has("front") || args.get("front").getAsBoolean(), lines));
                }
                case "menu" -> finish("completed", "menu", hands.menuSnapshot());
                case "menu_click" -> {
                    int menuId = integer(args, "menuId", -1), slot = integer(args, "slot", -1), button = integer(args, "button", 0);
                    var click = ClickType.valueOf(text(args, "clickType").toUpperCase(Locale.ROOT));
                    finish("completed", "menu_clicked", approachTargets ? hands.clickMenu(menuId, slot, button, click)
                        : hands.clickMenu(menuId, menuGeneration(), slot, button, click));
                }
                case "menu_close" -> finish("completed", "menu_closed", approachTargets ? hands.closeMenu()
                    : hands.closeMenu(integer(args, "menuId", -1), menuGeneration()));
                case "select_hotbar" -> {
                    hands.selectHotbar(integer(args, "slot", -1));
                    finish("completed", "hotbar_selected", null);
                }
                case "vehicle_control" -> {
                    if (!(mob.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat boat))
                        throw error(mob.isPassenger() ? "vehicle_controls_not_implemented_for_body" : "body_not_mounted");
                    if (boat.getControllingPassenger() != mob) throw error("body_not_vehicle_controller");
                    float left = (float) Math.clamp(number(args, "left"), -1, 1);
                    float forward = (float) Math.clamp(number(args, "forward"), -1, 1);
                    controlledBoat = boat;
                    vehicleLeft = left;
                    vehicleForward = forward;
                    finish("completed", "vehicle_controls_updated", null);
                }
                case "control" -> {
                    String control = text(args, "control");
                    if (args.get("state").getAsBoolean()) heldControls.add(control);
                    else heldControls.remove(control);
                    if (heldControls.isEmpty()) clearControls();
                    finish("completed", "control_updated", null);
                }
                case "menu_button" -> finish("completed", "menu_button", hands.menuButton(integer(args, "menuId", -1), menuGeneration(), integer(args, "button", -1)));
                case "anvil_name" -> finish("completed", "anvil_named", hands.renameAnvil(integer(args, "menuId", -1), menuGeneration(), text(args, "name")));
                case "select_trade" -> finish("completed", "trade_selected", hands.selectTrade(integer(args, "menuId", -1), menuGeneration(), integer(args, "index", -1)));
                case "edit_book" -> {
                    if (!args.has("pages") || !args.get("pages").isJsonArray() || args.getAsJsonArray("pages").size() > 100)
                        throw error("invalid_book_pages");
                    var pages = new ArrayList<String>();
                    for (var page : args.getAsJsonArray("pages")) {
                        if (!page.isJsonPrimitive() || !page.getAsJsonPrimitive().isString()) throw error("invalid_book_page");
                        pages.add(page.getAsString());
                    }
                    String title = null;
                    if (args.has("title") && !args.get("title").isJsonNull()) {
                        if (!args.get("title").isJsonPrimitive() || !args.getAsJsonPrimitive("title").isString())
                            throw error("invalid_book_title");
                        title = args.get("title").getAsString();
                    }
                    finish("completed", "book_edited", hands.editBook(integer(args, "menuId", -1), menuGeneration(),
                        integer(args, "slot", -1), text(args, "expectedItemKey"), pages, title));
                }
            }
        } catch (RuntimeException failure) {
            if (busy()) finish("failed", failure.getMessage(), null);
            else throw failure;
        } finally {
            if (scripted() && !travelled) {
                if (mob.isSleeping()) clearControls();
                else if (!heldControls.isEmpty()) applyControls();
                tickVehicleControls();
                GameAccess.travelFollowingBody(mob, box.get());
            }
            if (stateStream != null) {
                if (stateStream.open()) {
                    try { stateStream.offer(stateSnapshot.get()); }
                    catch (RuntimeException failure) { stateStream.fail(failure); }
                }
                if (!stateStream.open()) releaseScript();
            }
        }
    }

    private void tickVehicleControls() {
        var boat = controlledBoat;
        if (boat == null) return;
        if (boat.isRemoved() || mob.getVehicle() != boat || boat.getControllingPassenger() != mob) {
            clearVehicleControls();
            return;
        }
        boat.setInput(vehicleLeft > 0, vehicleLeft < 0, vehicleForward > 0, vehicleForward < 0);
        // Vanilla performs this in the controlling player's client tick. Our
        // native rider has no client, so apply input once before the next move.
        boat.controlBoat();
        var projected = boat.getBoundingBox().expandTowards(boat.getDeltaMovement()).inflate(0.1);
        var level = (ServerLevel) mob.level();
        var limit = box.get();
        var nextFeet = mob.position().add(boat.getDeltaMovement());
        if (!level.hasChunksAt(BlockPos.containing(projected.minX, projected.minY, projected.minZ),
                BlockPos.containing(projected.maxX, projected.maxY, projected.maxZ))
            || !level.getWorldBorder().isWithinBounds(projected)
            || limit != null && (!limit.dimension().equals(level.dimension().location().toString()) || !limit.holds(nextFeet))) {
            // Stop at the execution boundary without moving or snapping position.
            boat.setDeltaMovement(0, boat.getDeltaMovement().y, 0);
            clearVehicleControls();
            if (stateStream != null) stateStream.fail(error("vehicle_execution_boundary"));
        }
    }

    private void clearVehicleControls() {
        if (controlledBoat != null && controlledBoat.getControllingPassenger() == mob) {
            controlledBoat.setInput(false, false, false, false);
            controlledBoat.setPaddleState(false, false);
        }
        controlledBoat = null;
        vehicleLeft = vehicleForward = 0;
    }

    private void applyControls() {
        if (mob.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat boat && boat.getControllingPassenger() == mob) {
            controlledBoat = boat;
            vehicleLeft = (heldControls.contains("left") ? 1 : 0) - (heldControls.contains("right") ? 1 : 0);
            vehicleForward = (heldControls.contains("forward") ? 1 : 0) - (heldControls.contains("back") ? 1 : 0);
            return;
        }
        if (mob.isPassenger()) {
            clearControls();
            if (stateStream != null) stateStream.fail(error("vehicle_control_ownership_changed"));
            return;
        }
        mob.setSprinting(heldControls.contains("sprint"));
        mob.setShiftKeyDown(heldControls.contains("sneak"));
        float speed = (float) mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (heldControls.contains("sneak")) speed *= 0.3F;
        int forward = (heldControls.contains("forward") ? 1 : 0) - (heldControls.contains("back") ? 1 : 0);
        int strafe = (heldControls.contains("left") ? 1 : 0) - (heldControls.contains("right") ? 1 : 0);
        float scale = forward != 0 && strafe != 0 ? 0.70710677F : 1;
        mob.setSpeed(speed);
        mob.setZza(forward * speed * scale);
        mob.setXxa(strafe * speed * scale);
        mob.setYya(0);
        // Normal LivingEntity.aiStep owns jump timing, fluid impulses and the
        // repeat delay even for a NoAI body. Post-tick travel still runs once.
        mob.setJumping(heldControls.contains("jump"));
    }

    private void clearControls() {
        clearVehicleControls();
        heldControls.clear();
        mob.setSprinting(false);
        mob.setShiftKeyDown(false);
        mob.setJumping(false);
        mob.setXxa(0); mob.setYya(0); mob.setZza(0);
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

    private boolean ignoreLook() { return args.has("forceLook") && args.get("forceLook").isJsonPrimitive()
        && args.get("forceLook").getAsString().equals("ignore"); }

    private static Vec3 point(JsonObject value) { return new Vec3(number(value, "x"), number(value, "y"), number(value, "z")); }

    private boolean lookAngles() {
        double yawRadians = number(args, "yaw"), pitchRadians = number(args, "pitch");
        if (Math.abs(pitchRadians) > Math.PI / 2) throw error("invalid_pitch");
        float yaw = Mth.wrapDegrees((float) (180 - Math.toDegrees(yawRadians)));
        float pitch = (float) -Math.toDegrees(pitchRadians);
        boolean force = args.has("force") && args.get("force").getAsBoolean();
        // A normal look rotates at most 30 degrees per native tick. Forced looks
        // update the same real body orientation immediately.
        float dy = Mth.wrapDegrees(yaw - mob.getYRot()), dp = pitch - mob.getXRot();
        boolean done = force || (Math.abs(dy) <= 30 && Math.abs(dp) <= 30);
        float nextYaw = done ? yaw : mob.getYRot() + Mth.clamp(dy, -30, 30);
        float nextPitch = done ? pitch : mob.getXRot() + Mth.clamp(dp, -30, 30);
        mob.setYRot(nextYaw); mob.setYHeadRot(nextYaw); mob.setYBodyRot(nextYaw); mob.setXRot(nextPitch);
        hands.syncBody();
        return done;
    }

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
            travelled = true;
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
        travelled = true;
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
        if ("fish".equals(kind)) hands.cancelFishing();
        if (route != null) {
            route.stop();
            if (result == null) {
                result = route.progress();
                result.addProperty("status", status);
            }
            route = null;
        }
        hands.cancelMine();
        GameAccess.stopFollowingMotion(mob);
        action.addProperty("terminal",true); action.addProperty("status",status); action.addProperty("detail",detail == null ? "action_failed" : detail);
        action.addProperty("ticks",ticks);
        completedActionSequence = action.get("sequence").getAsLong();
        action.add("position", Observations.position(mob.position()));
        if (result != null) action.add("result",result.deepCopy());
        completion.complete(action.deepCopy());
    }
    private long menuGeneration() {
        if (!args.has("generation") || !args.get("generation").isJsonPrimitive()
            || !args.getAsJsonPrimitive("generation").isNumber()) throw error("menu_generation_required");
        double value = args.get("generation").getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > 9_007_199_254_740_991L || value != Math.rint(value))
            throw error("invalid_menu_generation");
        return (long) value;
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
