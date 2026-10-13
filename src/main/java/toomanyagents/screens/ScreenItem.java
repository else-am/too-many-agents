package toomanyagents.screens;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * A screen in an inventory. A screen taken down with its page saved carries only its URL, so placing it gets a fresh
 * name as well as a fresh id; ids belong only to placed screens.
 */
public final class ScreenItem extends Item {
    ScreenItem(Properties properties) {
        super(properties);
    }

    /** An item saving a screen's page, never its name. Same page, same stack. */
    static ItemStack stack(Screen record) {
        var stack = new ItemStack(Screens.ITEM.get());
        var saved = new Screen("", "", record.url(), "");
        if (!saved.equals(Screen.BLANK)) stack.set(Screens.RECORD.get(), saved);
        return stack;
    }

    /** What a placement from this item starts with: never an id or who opened its page. A name only comes from an item made with one. */
    static Screen record(ItemStack stack) { return saved(stack.getOrDefault(Screens.RECORD.get(), Screen.BLANK)); }

    private static Screen saved(Screen record) { return new Screen("", record.name(), record.url(), ""); }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var record = record(stack);
        String line = record.name().isEmpty() ? record.url() : record.url().isEmpty() ? record.name() : record.name() + " - " + record.url();
        if (!line.isEmpty()) lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
    }
}
