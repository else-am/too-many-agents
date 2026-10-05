package toomanyagents;

import net.minecraft.util.Mth;

/** Project colors shared by the sidebar and survey outlines. */
public final class ProjectColor {
    private ProjectColor() {}
    public static boolean valid(String color) { return color != null && color.matches("#[0-9a-fA-F]{6}"); }
    public static int rgb(String color) { return valid(color) ? Integer.parseInt(color.substring(1), 16) : 0x8CCBEE; }
    public static String of(com.google.gson.JsonObject project) {
        String color = JsonState.text(project, "color");
        if (valid(color)) return color;
        return "personal".equals(JsonState.text(project, "kind")) ? "#B0B8B5" : forId(JsonState.text(project, "id"));
    }
    public static String forId(String id) {
        return String.format("#%06X", Mth.hsvToRgb(Math.floorMod(id.hashCode(), 360) / 360.0F, 0.5F, 0.95F));
    }
}
