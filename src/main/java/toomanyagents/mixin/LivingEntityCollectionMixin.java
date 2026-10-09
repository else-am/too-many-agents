package toomanyagents.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import toomanyagents.ScriptCollectionEvent;

@Mixin(LivingEntity.class)
abstract class LivingEntityCollectionMixin {
    @Unique private int tooManyAgents$pickupId = -1;
    @Unique private long tooManyAgents$pickupTick;
    @Unique private ItemStack tooManyAgents$pickupStack = ItemStack.EMPTY;

    @Inject(method = "onItemPickup", at = @At("HEAD"))
    private void rememberItem(ItemEntity item, CallbackInfo callback) {
        var collector = (LivingEntity) (Object) this;
        if (collector.level().isClientSide || collector instanceof Player) return;
        // A Fox empties its stack before take; keep one preparation record per mob.
        tooManyAgents$pickupId = item.getId();
        tooManyAgents$pickupTick = collector.level().getGameTime();
        tooManyAgents$pickupStack = item.getItem().copy();
    }

    @Inject(method = "take", at = @At("RETURN"))
    private void observeTake(Entity item, int count, CallbackInfo callback) {
        var collector = (LivingEntity) (Object) this;
        if (collector.level().isClientSide) return;
        ItemStack original = null;
        if (item instanceof ItemEntity dropped) {
            original = tooManyAgents$pickupId == item.getId()
                && tooManyAgents$pickupTick == collector.level().getGameTime()
                ? tooManyAgents$pickupStack : dropped.getItem().copy();
        }
        tooManyAgents$pickupId = -1;
        tooManyAgents$pickupStack = ItemStack.EMPTY;
        // Match the native packet condition, including Fox's zero-count take.
        if (item.isRemoved() || !(item instanceof ItemEntity || item instanceof AbstractArrow || item instanceof ExperienceOrb)) return;
        // Player item pickups have a post-insertion event with the original stack.
        if (item instanceof ItemEntity && collector instanceof Player) return;
        NeoForge.EVENT_BUS.post(new ScriptCollectionEvent(collector, item, original));
    }
}
