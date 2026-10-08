package toomanyagents.mobs;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Dolphin;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Our own agent bodies. They are ordinary registered mobs, found by the body list the same way as any other mod's.
 * None has spawn rules or a spawn egg, so they appear only as agent bodies (or through /summon).
 */
public final class Mobs {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, "too_many_agents");

    public static final DeferredHolder<EntityType<?>, EntityType<Dolphin>> HACKER_DOLPHIN = TYPES.register("hacker_dolphin",
        () -> EntityType.Builder.of(Dolphin::new, MobCategory.WATER_CREATURE).sized(0.9F, 0.6F).eyeHeight(0.3F)
            .build("too_many_agents:hacker_dolphin"));

    private Mobs() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent event) ->
            event.put(HACKER_DOLPHIN.get(), Dolphin.createAttributes().build()));
        modBus.addListener((EntityRenderersEvent.RegisterLayerDefinitions event) ->
            event.registerLayerDefinition(HackerDolphinRenderer.GEAR, HackerDolphinRenderer::gear));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
            event.registerEntityRenderer(HACKER_DOLPHIN.get(), HackerDolphinRenderer::new));
    }
}
