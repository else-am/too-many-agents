package toomanyagents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.nio.file.Files;

/** Explicit development launch only; never opens or edits a user's existing world. */
final class DevelopmentWorld {
    static final boolean ENABLED = Boolean.getBoolean("too_many_agents.devWorld");
    static final String NAME = "too-many-agents-development";
    static final String OTHER_NAME = "too-many-agents-development-other";
    private static boolean opened;

    static void tick() {
        if (!ENABLED || opened) return;
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
        opened = true;
        mc.options.pauseOnLostFocus = false;
        mc.options.renderDistance().set(6);
        mc.options.simulationDistance().set(5);
        open(NAME);
    }

    static void open(String name) {
        if (!ENABLED || (!NAME.equals(name) && !OTHER_NAME.equals(name)))
            throw new IllegalStateException("Only the two development worlds may be opened");
        var mc = Minecraft.getInstance();
        var flows = mc.createWorldOpenFlows();
        if (Files.exists(mc.gameDirectory.toPath().resolve("saves").resolve(name).resolve("level.dat"))) {
            flows.openWorld(name, () -> mc.setScreen(new TitleScreen()));
        } else {
            var rules = new GameRules();
            var settings = new LevelSettings("Too Many Agents development", GameType.CREATIVE, false,
                Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
            flows.createFreshLevel(name, settings, new WorldOptions(17L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
        }
    }
}
