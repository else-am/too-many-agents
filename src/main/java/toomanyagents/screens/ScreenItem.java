package toomanyagents.screens;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** A screen in an inventory. A broken screen's item carries its record, so placing it again restores the screen. */
public final class ScreenItem extends Item {
    ScreenItem(Properties properties) {
        super(properties);
    }

    static ItemStack stack(Screen record) {
        var stack = new ItemStack(Screens.ITEM.get());
        if (!record.id().isEmpty()) stack.set(Screens.RECORD.get(), record);
        return stack;
    }

    static Screen record(ItemStack stack) { return stack.getOrDefault(Screens.RECORD.get(), Screen.BLANK); }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var record = record(stack);
        if (record.id().isEmpty()) return;
        lines.add(Component.literal(record.name().isEmpty() ? record.id() : record.id() + " · " + record.name()).withStyle(ChatFormatting.GRAY));
        if (!record.url().isEmpty()) lines.add(Component.literal(record.url()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
