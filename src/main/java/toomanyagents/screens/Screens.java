package toomanyagents.screens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Screen blocks, and the client's views of the complete screens around the player. */
public final class Screens {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("too_many_agents");
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("too_many_agents");
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "too_many_agents");

    public static final DeferredBlock<ScreenBlock> SCREEN = BLOCKS.register("screen", () -> new ScreenBlock(BlockBehaviour.Properties.of()
        .mapColor(MapColor.COLOR_BLACK).strength(1.5F).sound(SoundType.COPPER)));
    public static final DeferredItem<BlockItem> SCREEN_ITEM = ITEMS.registerSimpleBlockItem(SCREEN, new Item.Properties());
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScreenBlockEntity>> BLOCK_ENTITY = BLOCK_ENTITIES.register("screen",
        () -> BlockEntityType.Builder.of(ScreenBlockEntity::new, SCREEN.get()).build(null));

    /** Screens farther than this keep their last frame. */
    static final double LIVE_DISTANCE = 32;

    private static final Map<BlockPos, ScreenView> views = new HashMap<>();
    private static ClientLevel viewsLevel;

    private Screens() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(SCREEN_ITEM);
        });
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
            event.registerBlockEntityRenderer(BLOCK_ENTITY.get(), ScreenRenderer::new));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        // Bare hands use a complete screen as one surface, so it gets no block outline.
        NeoForge.EVENT_BUS.addListener((RenderHighlightEvent.Block event) -> {
            var player = Minecraft.getInstance().player;
            if (player == null || !player.getMainHandItem().isEmpty()) return;
            var pos = event.getTarget().getBlockPos();
            if (views.values().stream().anyMatch(view -> view.contains(pos))) event.setCanceled(true);
        });
        ScreenInput.register();
    }

    /** The view drawn for a complete screen, created the first time it is seen. */
    static ScreenView view(ScreenBlockEntity screen) {
        var level = Minecraft.getInstance().level;
        if (level != viewsLevel) closeAll(level);
        var view = views.get(screen.getBlockPos());
        if (view != null && !view.matches(screen)) {
            view.close();
            view = null;
        }
        if (view == null) {
            view = new ScreenView(screen);
            views.put(screen.getBlockPos(), view);
        }
        Chromium.start();
        view.sync(screen.url(), Trust.allowedHere());
        view.lastSeen = System.nanoTime();
        return view;
    }

    /** Stops Chromium on the render thread as the game closes. */
    public static void shutdown() {
        closeAll(null);
        Chromium.shutdown();
    }

    static List<ScreenView> views() { return new ArrayList<>(views.values()); }

    /** Persists a screen's page in the world. Singleplayer: the integrated server owns the blocks. */
    static void saveUrl(BlockPos anchor, String url) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.level == null) return;
        var dimension = mc.level.dimension();
        server.execute(() -> {
            var level = server.getLevel(dimension);
            if (level != null) ScreenBlock.setUrl(level, anchor, url);
        });
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.level != viewsLevel) closeAll(mc.level);
        if (mc.level == null || mc.player == null) return;
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        double keep = (mc.options.getEffectiveRenderDistance() + 1) * 16;
        long now = System.nanoTime();
        for (var iterator = views.values().iterator(); iterator.hasNext(); ) {
            var view = iterator.next();
            double distance = view.center().distanceTo(camera);
            boolean stillThere = mc.level.getBlockEntity(view.anchor) instanceof ScreenBlockEntity screen && view.matches(screen);
            if (!stillThere || distance > keep) {
                ScreenInput.forget(view);
                view.close();
                iterator.remove();
                continue;
            }
            view.setLive(distance <= LIVE_DISTANCE && now - view.lastSeen < 500_000_000L);
        }
    }

    private static void closeAll(ClientLevel level) {
        views.values().forEach(view -> {
            ScreenInput.forget(view);
            view.close();
        });
        views.clear();
        viewsLevel = level;
    }
}
