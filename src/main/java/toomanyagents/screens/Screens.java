package toomanyagents.screens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Screen entities and items, and the client's views of the screens around the player. */
public final class Screens {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("too_many_agents");
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, "too_many_agents");
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "too_many_agents");

    public static final DeferredItem<ScreenItem> ITEM = ITEMS.register("screen", () -> new ScreenItem(new Item.Properties()));
    public static final DeferredHolder<EntityType<?>, EntityType<ScreenEntity>> ENTITY = ENTITIES.register("screen",
        () -> EntityType.Builder.<ScreenEntity>of(ScreenEntity::new, MobCategory.MISC).sized(0.5F, 0.5F)
            .clientTrackingRange(10).updateInterval(Integer.MAX_VALUE).build("too_many_agents:screen"));
    static final DeferredHolder<DataComponentType<?>, DataComponentType<Screen>> RECORD = COMPONENTS.registerComponentType("screen",
        builder -> builder.persistent(Screen.CODEC).networkSynchronized(Screen.STREAM_CODEC));

    /** Screens farther than this keep their last frame. */
    static final double LIVE_DISTANCE = 32;
    /** A record change that hasn't reached the server by then is dropped. */
    private static final long EXPIRY_NANOS = 2_000_000_000L;

    private static final Map<UUID, ScreenView> views = new HashMap<>();
    private static ClientLevel viewsLevel;

    private Screens() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(ITEM);
        });
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerEntityRenderer(ENTITY.get(), ScreenRenderer::new));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        ScreenInput.register(modBus);
        ScreenPlacement.register();
        // Leases run out on the server's clock.
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            if (event.getServer().getTickCount() % 10 == 0) ScreenIndex.get(event.getServer()).expire(event.getServer());
        });
    }

    /** The view drawn for a screen, created the first time it is seen. */
    static ScreenView view(ScreenEntity screen) {
        var level = Minecraft.getInstance().level;
        if (level != viewsLevel) closeAll(level);
        var view = views.get(screen.getUUID());
        if (view != null && !view.matches(screen)) {
            ScreenInput.forget(view);
            view.close();
            view = null;
        }
        if (view == null) {
            view = new ScreenView(screen);
            views.put(screen.getUUID(), view);
        }
        // The same screen comes back as a new entity when it is tracked again.
        view.screen = screen;
        boolean trusted = Trust.allowedHere();
        view.sync(screen.record().url(), trusted);
        // An idle screen needs no browser, so it never starts Chromium.
        if (trusted && !view.url.isBlank()) Chromium.start();
        view.lastSeen = System.nanoTime();
        return view;
    }

    /** Stops Chromium on the render thread as the game closes. */
    public static void shutdown() {
        closeAll(null);
        Chromium.shutdown();
    }

    static List<ScreenView> views() { return new ArrayList<>(views.values()); }

    /** The view already made for a screen, if any. */
    static ScreenView existing(UUID screen) { return views.get(screen); }

    /** Persists a screen's page. A player navigating takes the page over from whichever thread opened it. */
    static void saveUrl(ScreenView view, String url, boolean byPlayer) {
        edit(view, record -> new Screen(record.id(), record.name(), url, byPlayer ? "" : record.openedBy()));
    }

    /** Changes a screen's record. Singleplayer: the integrated server owns the entity, and the change syncs back. */
    static void edit(ScreenView view, UnaryOperator<Screen> change) {
        var mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.level == null) return;
        var dimension = mc.level.dimension();
        UUID id = view.screen.getUUID();
        UUID playerId = mc.player == null ? null : mc.player.getUUID();
        long deadline = System.nanoTime() + EXPIRY_NANOS;
        server.execute(() -> {
            if (System.nanoTime() > deadline || !server.isRunning()) return;
            var level = server.getLevel(dimension);
            if (level == null || !(level.getEntity(id) instanceof ScreenEntity screen)) return;
            var record = screen.record();
            var updated = change.apply(record);
            try {
                if (!updated.equals(record)) screen.setRecord(updated);
            } catch (ScreenTools.ScreenError error) {
                var player = playerId == null ? null : server.getPlayerList().getPlayer(playerId);
                if (player != null) player.displayClientMessage(net.minecraft.network.chat.Component.literal(error.getMessage()), false);
            }
        });
    }

    /**
     * Takes a screen down as an item, with its page (not its name) or blank when reset. The server checks the player can
     * still reach it, and a screen already taken can't be taken again.
     */
    static void pickUp(ScreenView view, boolean reset) {
        var mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.level == null || mc.player == null) return;
        var dimension = mc.level.dimension();
        UUID id = view.screen.getUUID(), playerId = mc.player.getUUID();
        long deadline = System.nanoTime() + EXPIRY_NANOS;
        server.execute(() -> {
            if (System.nanoTime() > deadline || !server.isRunning()) return;
            var level = server.getLevel(dimension);
            var player = server.getPlayerList().getPlayer(playerId);
            if (level == null || player == null || player.level() != level || !player.isAlive() || player.isSpectator()) return;
            if (!(level.getEntity(id) instanceof ScreenEntity screen) || screen.isRemoved()) return;
            double reach = ScreenInput.REACH + 1;
            if (screen.getBoundingBox().distanceToSqr(player.getEyePosition()) > reach * reach) return;
            screen.pickUp(reset);
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
            boolean stillThere = !view.screen.isRemoved() && view.matches(view.screen);
            if (!stillThere || distance > keep) {
                ScreenInput.forget(view);
                view.close();
                iterator.remove();
                continue;
            }
            // Pages change on the server too; follow them even while the screen isn't drawn.
            view.sync(view.screen.record().url(), Trust.allowedHere());
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
