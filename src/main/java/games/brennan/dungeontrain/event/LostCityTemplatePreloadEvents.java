package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.LostCityStructures;
import games.brennan.dungeontrain.worldgen.LostCityTemplatePreload;
import games.brennan.dungeontrain.worldgen.LostCityWwooCensus;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads Big Lost City's structure templates on a background thread once a player nears the Lost City run
 * ({@link LostCityTemplatePreload#nearLostCity}), so worldgen finds them already in
 * {@link StructureTemplateManager}'s cache instead of loading and datafixing ~19 MB of 1.20.1 NBT the moment
 * the first city generates.
 *
 * <p>The cache is a {@code ConcurrentHashMap.computeIfAbsent} that worldgen threads already share, so a
 * chunk needing a template this thread is mid-way through just waits for that one template. A dedicated
 * thread rather than {@code Util.backgroundExecutor()}: work that can meet worldgen must not queue behind it
 * (see {@code PortalChunkTerrain}). Runs once per server session; {@code /reload} empties the cache, so it
 * re-arms then.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LostCityTemplatePreloadEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN FAILURES = new LogFirstN(5);

    /** Ticks between position checks — the train covers a few blocks a second against a 3000-block window. */
    private static final int SCAN_PERIOD_TICKS = 20;

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    /** Bumped on server stop / reload so a pre-load for the old cache stops early. */
    private static final AtomicInteger EPOCH = new AtomicInteger();
    private static volatile ExecutorService executor;

    private LostCityTemplatePreloadEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (STARTED.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!Level.OVERWORLD.equals(level.dimension())) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) return;
        if (!DungeonTrainWorldData.get(level).startsWithTrain()) return;
        for (ServerPlayer player : players) {
            if (LostCityTemplatePreload.nearLostCity(cycle, player.getBlockX(), LostCityTemplatePreload.LOOKAHEAD_BLOCKS)) {
                start(level, player.getBlockX());
                return;
            }
        }
    }

    private static void start(ServerLevel level, int triggerX) {
        if (!STARTED.compareAndSet(false, true)) return;
        StructureTemplateManager templates = level.getServer().getStructureManager();
        List<ResourceLocation> ids;
        try {
            ids = templates.listTemplates()
                    .filter(id -> LostCityStructures.NAMESPACE.equals(id.getNamespace()))
                    .toList();
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Lost City template pre-load: failed to list templates", t);
            return;
        }
        if (ids.isEmpty()) return;
        int epoch = EPOCH.get();
        LOGGER.info("[DungeonTrain] Lost City template pre-load: {} templates, triggered at x={}", ids.size(), triggerX);
        executor().execute(() -> preload(templates, ids, epoch));
    }

    private static void preload(StructureTemplateManager templates, List<ResourceLocation> ids, int epoch) {
        long t0 = System.nanoTime();
        int loaded = 0;
        for (ResourceLocation id : ids) {
            if (epoch != EPOCH.get()) {
                LOGGER.info("[DungeonTrain] Lost City template pre-load: abandoned after {}/{} (server stopped or reloaded)",
                        loaded, ids.size());
                return;
            }
            try {
                templates.getOrCreate(id);
                loaded++;
            } catch (Throwable t) {
                FAILURES.error(LOGGER, "[DungeonTrain] Lost City template pre-load: failed to load " + id, t);
            }
        }
        LOGGER.info("[DungeonTrain] Lost City templates pre-loaded: {}/{} in {} ms",
                loaded, ids.size(), (System.nanoTime() - t0) / 1_000_000L);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        reset();
        // Decide this world's WWOO buildings at world load, off the worldgen threads. The census caches its
        // pick; the lazy path in LostCityStructures.allowedAt only runs it if worldgen asks first.
        ServerLevel overworld = event.getServer().overworld();
        if (overworld == null) return;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return;
        LostCityWwooCensus.buildings(overworld, data.getGenerationSeed(), cycle);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        reset();
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        // A datapack (re)load swaps the template manager's resources and empties its cache.
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) reset();
    }

    private static void reset() {
        EPOCH.incrementAndGet();
        STARTED.set(false);
    }

    private static ExecutorService executor() {
        ExecutorService e = executor;
        if (e != null) return e;
        synchronized (LostCityTemplatePreloadEvents.class) {
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "DungeonTrain-lostcity-preload");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
            }
            return executor;
        }
    }
}
