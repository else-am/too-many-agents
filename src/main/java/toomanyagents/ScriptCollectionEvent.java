package toomanyagents;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

/** An actual native take notification, with item data captured before extraction. */
public final class ScriptCollectionEvent extends Event {
    public final LivingEntity collector;
    public final Entity collected;
    public final ItemStack originalItem;

    public ScriptCollectionEvent(LivingEntity collector, Entity collected, ItemStack originalItem) {
        this.collector = collector;
        this.collected = collected;
        this.originalItem = originalItem;
    }
}
