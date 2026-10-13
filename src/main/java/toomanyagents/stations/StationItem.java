package toomanyagents.stations;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** A station in an inventory, carrying its settings. Equal settings stack. */
public final class StationItem extends BlockItem {
    StationItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** An item carrying a station's settings, never its id or occupant. */
    public static ItemStack stack(Station station) {
        var stack = new ItemStack(Stations.STATION_ITEM.get());
        stack.set(Stations.STATION_DATA.get(), station.settings());
        return stack;
    }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var station = stack.get(Stations.STATION_DATA.get());
        if (station == null) return;
        int width = 2 * station.radius() + 1;
        String area = width + "×" + width + ", " + station.height() + " high";
        lines.add(Component.literal(station.name().isEmpty() ? area : station.name() + " - " + area).withStyle(ChatFormatting.GRAY));
        if (!station.projectId().isEmpty()) lines.add(Component.literal(station.projectId()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
