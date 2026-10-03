package toomanyagents;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Random names and bodies for new agents when none is chosen. */
public final class StarterAgents {
    private StarterAgents() {}

    private static final List<String> NAMES = List.of(
        "Alder", "Ash", "Basil", "Birch", "Bramble", "Clover", "Cobble", "Cricket", "Dune", "Ember",
        "Fennel", "Fern", "Flint", "Hazel", "Heath", "Juniper", "Kestrel", "Lark", "Linden", "Loam",
        "Maple", "Marlow", "Moss", "Nettle", "Oak", "Olive", "Pebble", "Pip", "Quill", "Reed",
        "Robin", "Rowan", "Rue", "Sage", "Sorrel", "Sparrow", "Tansy", "Thistle", "Wren", "Yarrow");

    // Small, friendly mobs that read well as companions.
    private static final List<String> BODIES = List.of(
        "minecraft:villager", "minecraft:wandering_trader", "minecraft:fox", "minecraft:cat", "minecraft:wolf",
        "minecraft:pig", "minecraft:cow", "minecraft:sheep", "minecraft:chicken", "minecraft:rabbit",
        "minecraft:panda", "minecraft:llama", "minecraft:goat", "minecraft:frog", "minecraft:axolotl",
        "minecraft:allay", "minecraft:snow_golem", "minecraft:iron_golem", "minecraft:armadillo", "minecraft:turtle");

    /** A name not already in use, when one remains. */
    public static String name(Set<String> taken) {
        var free = new ArrayList<>(NAMES.stream().filter(name -> !taken.contains(name)).toList());
        var choices = free.isEmpty() ? NAMES : free;
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    public static String body() { return BODIES.get(ThreadLocalRandom.current().nextInt(BODIES.size())); }
}
