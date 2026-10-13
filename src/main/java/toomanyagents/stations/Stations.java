package toomanyagents.stations;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import toomanyagents.ProjectColor;

/** Station and project blocks, their items, and the rule that an occupied station can't be broken. */
public final class Stations {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("too_many_agents");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("too_many_agents");
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "too_many_agents");
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "too_many_agents");

    public static final DeferredBlock<StationBlock> STATION = BLOCKS.register("station", () -> new StationBlock(BlockBehaviour.Properties.of()
        .mapColor(MapColor.WOOD).strength(2.5F, 3_600_000F).sound(SoundType.WOOD).pushReaction(PushReaction.BLOCK)));
    public static final DeferredBlock<ProjectBlock> PROJECT = BLOCKS.register("project_block", () -> new ProjectBlock(BlockBehaviour.Properties.of()
        .mapColor(MapColor.SNOW).strength(1.5F).sound(SoundType.WOOD)));
    public static final DeferredItem<StationItem> STATION_ITEM = ITEMS.register("station", () -> new StationItem(STATION.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> PROJECT_ITEM = ITEMS.registerSimpleBlockItem(PROJECT);
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StationBlockEntity>> STATION_ENTITY = BLOCK_ENTITIES.register("station",
        () -> BlockEntityType.Builder.of(StationBlockEntity::new, STATION.get()).build(null));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProjectBlockEntity>> PROJECT_ENTITY = BLOCK_ENTITIES.register("project_block",
        () -> BlockEntityType.Builder.of(ProjectBlockEntity::new, PROJECT.get()).build(null));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Station>> STATION_DATA = COMPONENTS.registerComponentType("station",
        builder -> builder.persistent(Station.CODEC).networkSynchronized(Station.STREAM_CODEC));

    private Stations() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() != CreativeModeTabs.FUNCTIONAL_BLOCKS) return;
            event.accept(STATION_ITEM);
            event.accept(PROJECT_ITEM);
        });
        modBus.addListener((RegisterColorHandlersEvent.Block event) -> event.register((state, level, pos, tint) ->
            level != null && pos != null && level.getBlockEntity(pos) instanceof ProjectBlockEntity block && !block.color().isEmpty()
                ? ProjectColor.rgb(block.color()) : 0xFFFFFF, PROJECT.get()));
        // Players and agents alike break blocks through this event, in every game mode.
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent event) -> {
            if (!StationBlock.occupied(event.getLevel(), event.getPos())) return;
            event.setCanceled(true);
            // A creative client already predicted the break; resend the block and its occupant.
            if (event.getLevel() instanceof Level level) level.sendBlockUpdated(event.getPos(), event.getState(), event.getState(), Block.UPDATE_CLIENTS);
            event.getPlayer().displayClientMessage(Component.literal("Archive this station's agent before breaking it."), true);
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel level) level.getServer().execute(() -> StationIndex.get(level.getServer()).chunkLoaded(level, event.getChunk().getPos()));
        });
    }
}
