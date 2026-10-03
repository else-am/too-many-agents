package toomanyagents;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

/** One saved color for an agent's name and indicators. */
public final class AgentColor {
    private AgentColor() {}
    public static boolean valid(String color) { return color != null && color.matches("#[0-9a-fA-F]{6}"); }
    public static int rgb(String color) { return valid(color) ? Integer.parseInt(color.substring(1), 16) : 0x8CCBEE; }
    public static MutableComponent name(String name, String color) {
        return Component.literal(name).withStyle(style -> style.withColor(rgb(color)));
    }
    public static String forId(String id) {
        return String.format("#%06X", Mth.hsvToRgb(Math.floorMod(id.hashCode(), 360) / 360.0F, 0.5F, 0.95F));
    }
    public static String random(Set<String> used) {
        var random = ThreadLocalRandom.current();
        String color;
        do {
            color = String.format("#%06X", Mth.hsvToRgb(random.nextFloat(), 0.35F + random.nextFloat() * 0.3F, 0.95F));
        } while (used.contains(color));
        return color;
    }
}
