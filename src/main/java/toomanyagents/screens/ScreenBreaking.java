package toomanyagents.screens;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Breaking a screen takes holding attack with a tool, like mining a block: progress builds each tick with swings, hit
 * sounds and cracks. Speed is vanilla's own mining speed against stone, so a pickaxe is fast, efficiency, Haste and
 * water all count, and other tools break it about as fast as a hand breaks planks. Creative breaks it at once. At 100%
 * the client sends an ordinary attack, which the server trusts. Render thread only.
 */
final class ScreenBreaking {
    /** Mining speed is measured against this block; a pickaxe is its correct tool. */
    private static final BlockState REFERENCE = Blocks.STONE.defaultBlockState();
    /** Planks' hardness: speed 1 (any other tool) takes 60 ticks, like breaking planks by hand. */
    private static final float HARDNESS = 2;

    private static ScreenEntity target;
    private static ClientLevel level;
    private static int slot;
    private static ItemStack tool = ItemStack.EMPTY;
    private static float progress;
    private static int ticks;

    private ScreenBreaking() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(ScreenBreaking::attack);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }

    /** A click on a screen never attacks it directly; progress decides when it breaks. */
    private static void attack(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        if (!event.isAttack() || mc.player == null || !(aimed() instanceof ScreenEntity screen)) return;
        event.setCanceled(true);
        boolean tool = ScreenEntity.isTool(mc.player.getMainHandItem());
        event.setSwingHand(tool);
        if (tool && mc.player.getAbilities().instabuild) breakNow(mc.player, screen);
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        var aimed = aimed();
        if (player == null || mc.screen != null || !mc.options.keyAttack.isDown() || !(aimed instanceof ScreenEntity screen)
            || !ScreenEntity.isTool(player.getMainHandItem()) || player.getAbilities().instabuild) {
            reset();
            return;
        }
        // A new target, or a different tool in hand, starts over.
        if (screen != target || mc.level != level || player.getInventory().selected != slot || !ItemStack.isSameItemSameComponents(player.getMainHandItem(), tool)) {
            reset();
            target = screen;
            level = mc.level;
            slot = player.getInventory().selected;
            tool = player.getMainHandItem().copy();
        }
        progress += speed(player);
        player.swing(InteractionHand.MAIN_HAND);
        if (ticks++ % 4 == 0) {
            var at = mc.hitResult.getLocation();
            mc.getSoundManager().play(new SimpleSoundInstance(SoundEvents.COPPER_HIT, SoundSource.BLOCKS, 0.25F, 0.5F, RandomSource.create(), at.x, at.y, at.z));
        }
        if (progress >= 1) breakNow(player, screen);
    }

    /** Progress per tick, from vanilla's mining speed. */
    static float speed(Player player) { return player.getDigSpeed(REFERENCE, null) / HARDNESS / 30; }

    private static void breakNow(Player player, ScreenEntity screen) {
        Minecraft.getInstance().gameMode.attack(player, screen);
        player.swing(InteractionHand.MAIN_HAND);
        reset();
    }

    private static void reset() {
        target = null;
        level = null;
        tool = ItemStack.EMPTY;
        progress = 0;
        ticks = 0;
    }

    /** The screen the crosshair picks, which vanilla already stops at walls and nearer entities. */
    private static Object aimed() {
        var hit = Minecraft.getInstance().hitResult;
        return hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
    }

    /** The destroy stage to draw over a screen, 0–9, or -1 when it isn't being broken. */
    static int stage(ScreenEntity screen) {
        return screen == target && progress > 0 ? Math.min(9, (int) (progress * 10)) : -1;
    }

    /** Status for development checks. */
    static JsonObject state() {
        var result = new JsonObject();
        result.addProperty("target", target == null ? "" : target.record().id());
        result.addProperty("progress", progress);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            result.addProperty("tool", ScreenEntity.isTool(player.getMainHandItem()));
            result.addProperty("perTick", speed(player));
        }
        return result;
    }
}
