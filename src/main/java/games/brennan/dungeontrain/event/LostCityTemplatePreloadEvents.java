package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.StructureTemplateManagerAccessor;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.LostCityPreloadState;
import games.brennan.dungeontrain.worldgen.LostCityPreloadState.Scope;
import games.brennan.dungeontrain.worldgen.LostCityStructures;
import games.brennan.dungeontrain.worldgen.LostCityTemplateDemand;
import games.brennan.dungeontrain.worldgen.LostCityTemplateIds;
import games.brennan.dungeontrain.worldgen.LostCityTemplateLoads;
import games.brennan.dungeontrain.worldgen.LostCityTemplatePreload;
import games.brennan.dungeontrain.worldgen.LostCityTemplatePreload.Mode;
import games.brennan.dungeontrain.worldgen.LostCityTemplatePreload.Need;
import games.brennan.dungeontrain.worldgen.LostCityWwooCensus;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads the Big Lost City templates a city can place ({@link LostCityTemplateIds#placeable}: 42 of the mod's 75,
 * ~7.5 MB compressed / ~90 MB raw 1.20.1 NBT) on a background thread before worldgen needs them, so it finds
 * them already in {@link StructureTemplateManager}'s cache instead of loading and datafixing them the moment a
 * city generates — and removes them again once nothing does. {@link LostCityTemplatePreload} holds the rules.
 *
 * <p>Two things start a pre-load. The scan (once a second, and at once when a player teleports): the Lost City
 * run within the lookahead of a player loads everything, the WWOO stretch within their generation reach loads
 * this world's foretaste pick. And demand ({@link LostCityTemplateDemand}): a Lost City start approved on any
 * thread while the cache is cold — a teleport that beat the scan, a Distant Horizons worker far ahead — loads
 * at once, that structure's templates first, at normal priority.</p>
 *
 * <p>The cache is a {@code ConcurrentHashMap.computeIfAbsent} that worldgen threads already share, so a
 * chunk needing a template this thread is mid-way through just waits for that one template. A dedicated
 * thread rather than {@code Util.backgroundExecutor()}: work that can meet worldgen must not queue behind it
 * (see {@code PortalChunkTerrain}).</p>
 *
 * <p>The cache holds a template until a datapack reload, so once every position has been
 * {@link Need#QUIET quiet} for {@link LostCityTemplatePreload#EVICT_AFTER_SCANS} scans in a row, with no
 * pre-load running and no recent demand, the {@code big_lost_city} entries are removed and the pre-load re-arms.
 * Removal only drops the cache entry: a worldgen thread mid-placement keeps its template, and a later lookup
 * reloads it. Server stop and {@code /reload} also re-arm it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LostCityTemplatePreloadEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN FAILURES = new LogFirstN(5);

    /** Ticks between position checks — the train covers a few blocks a second against a 3000-block window. */
    private static final int SCAN_PERIOD_TICKS = 20;

    /** {@code LAST_X}'s "never seen" value. */
    private static final int UNSEEN = Integer.MIN_VALUE;

    private static final LostCityPreloadState STATE = new LostCityPreloadState();
    /** Consecutive quiet scans; server thread only. */
    private static int quietScans;
    /**
     * Each online player's last overworld X; server thread only. A player who is dead or in another dimension
     * keeps theirs: they return to the train, which is where they left it.
     */
    private static final Object2IntOpenHashMap<UUID> LAST_X = new Object2IntOpenHashMap<>();
    private static final AtomicInteger PRELOADS = new AtomicInteger();
    private static final AtomicInteger EVICTIONS = new AtomicInteger();
    /** The running server, for a pre-load kicked from a worldgen thread. */
    private static volatile MinecraftServer server;
    private static volatile ExecutorService executor;

    static {
        LAST_X.defaultReturnValue(UNSEEN);
        LostCityTemplateDemand.setListener(LostCityTemplatePreloadEvents::onDemand);
    }

    private LostCityTemplatePreloadEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!Level.OVERWORLD.equals(level.dimension())) return;
        Mode mode = LostCityTemplatePreload.MODE;
        boolean due = level.getGameTime() % SCAN_PERIOD_TICKS == 0;
        boolean jumped = mode == Mode.REACH && trackPositions(level.players());
        if (!due && !jumped) return;
        MinecraftServer srv = level.getServer();
        int[] anchors = anchors(srv, level, mode, due);
        if (anchors.length == 0) return;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (!cycle.hasLayout()) return;
        if (!DungeonTrainWorldData.get(level).startsWithTrain()) return;

        int reach = LostCityTemplatePreload.reachBlocks(srv.getPlayerList().getViewDistance());
        Need strongest = Need.QUIET;
        int at = 0;
        for (int x : anchors) {
            Need need = LostCityTemplatePreload.needAt(cycle, x, reach, mode);
            if (need.compareTo(strongest) > 0) {
                strongest = need;
                at = x;
            }
        }
        if (strongest == Need.RUN) {
            start(srv, Scope.FULL, null, false, "a player at x=" + at + " is near the run");
        } else if (strongest == Need.FORETASTE) {
            start(srv, Scope.FORETASTE, null, false, "a player at x=" + at + " is near the WWOO stretch");
        }
        if (!due) {
            if (strongest != Need.QUIET) quietScans = 0;
            return;
        }
        boolean demandHeld = mode == Mode.REACH && LostCityTemplateDemand.holds(System.nanoTime());
        quietScans = LostCityTemplatePreload.nextQuietScans(quietScans,
                LostCityTemplatePreload.quietScan(strongest, STATE.running(), demandHeld));
        if (quietScans < LostCityTemplatePreload.EVICT_AFTER_SCANS) return;
        quietScans = 0;
        evict(srv.getStructureManager());
    }

    /** Records every overworld player's X; whether one of them is new here or moved a teleport's distance in a tick. */
    private static boolean trackPositions(List<ServerPlayer> players) {
        boolean jumped = false;
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer player = players.get(i);
            int x = player.getBlockX();
            int last = LAST_X.put(player.getUUID(), x);
            if (last == UNSEEN || LostCityTemplatePreload.jumped(last, x)) jumped = true;
        }
        return jumped;
    }

    /** The X positions that count: every online player's last overworld X — or, in legacy mode, overworld players only. */
    private static int[] anchors(MinecraftServer srv, ServerLevel level, Mode mode, boolean prune) {
        if (mode == Mode.LEGACY) {
            List<ServerPlayer> players = level.players();
            int[] xs = new int[players.size()];
            for (int i = 0; i < xs.length; i++) xs[i] = players.get(i).getBlockX();
            return xs;
        }
        if (prune) LAST_X.keySet().removeIf(id -> srv.getPlayerList().getPlayer(id) == null);
        return LAST_X.values().toIntArray();
    }

    /** A Lost City start was approved on the calling thread (any thread): with the cache cold, load now. */
    private static void onDemand(ResourceLocation structure, int chunkX) {
        if (LostCityTemplatePreload.MODE != Mode.REACH) return;
        Scope have = STATE.scope();
        if (have == Scope.FULL) return;
        MinecraftServer srv = server;
        if (srv == null) return;
        Scope want = LostCityStructures.inWwooStretch(WorldGenCycle.fromConfig(), chunkX) ? Scope.FORETASTE : Scope.FULL;
        if (want.compareTo(have) <= 0) return;
        start(srv, want, structure, true,
                structure + " started at chunk x=" + chunkX + " on " + Thread.currentThread().getName());
    }

    /** Removes every {@code big_lost_city} template from the cache and re-arms the pre-load, unless one is running. */
    private static void evict(StructureTemplateManager templates) {
        int[] removed = {0};
        try {
            boolean ran = STATE.evict(() -> {
                List<ResourceLocation> cached = cached(templates);
                cached.forEach(templates::remove);
                removed[0] = cached.size();
            });
            if (!ran) return;
        } catch (Throwable t) {
            FAILURES.error(LOGGER, "[DungeonTrain] Lost City template eviction: failed to read the template cache", t);
            return;
        }
        if (removed[0] == 0) return;
        EVICTIONS.incrementAndGet();
        LOGGER.info("[DungeonTrain] Lost City templates evicted: {} (nothing near the run or the WWOO stretch); pre-load re-armed",
                removed[0]);
    }

    private static List<ResourceLocation> cached(StructureTemplateManager templates) {
        return ((StructureTemplateManagerAccessor) templates).dungeontrain$structureRepository().keySet().stream()
                .filter(id -> LostCityStructures.NAMESPACE.equals(id.getNamespace()))
                .toList();
    }

    /** Queues a pre-load of {@code scope} unless that much has already been asked for. Safe from any thread. */
    private static void start(MinecraftServer srv, Scope scope, ResourceLocation first, boolean urgent, String why) {
        LostCityPreloadState.Ticket ticket = STATE.tryBegin(scope);
        if (ticket == null) return;
        try {
            executor().execute(() -> {
                try {
                    preload(srv, scope, first, urgent, ticket, why);
                } catch (Throwable t) {
                    FAILURES.error(LOGGER, "[DungeonTrain] Lost City template pre-load failed", t);
                } finally {
                    STATE.end(ticket);
                }
            });
        } catch (RuntimeException e) {
            STATE.end(ticket);
            LOGGER.error("[DungeonTrain] Lost City template pre-load: failed to schedule", e);
        }
    }

    private static void preload(MinecraftServer srv, Scope scope, ResourceLocation first, boolean urgent,
                                LostCityPreloadState.Ticket ticket, String why) {
        // An urgent pre-load is racing worldgen for the very templates it is about to ask for.
        Thread.currentThread().setPriority(urgent ? Thread.NORM_PRIORITY : Thread.MIN_PRIORITY);
        List<ResourceLocation> ids = ids(srv, scope);
        if (first != null) {
            ids = LostCityTemplateIds.ordered(ids, LostCityTemplateIds.placeable(srv.registryAccess(), first::equals));
        }
        if (ids.isEmpty()) return;
        StructureTemplateManager templates = srv.getStructureManager();
        PRELOADS.incrementAndGet();
        LOGGER.info("[DungeonTrain] Lost City template pre-load: {} templates ({}{}): {}",
                ids.size(), scope, urgent ? ", urgent" : "", why);
        long t0 = System.nanoTime();
        int loaded = 0;
        for (ResourceLocation id : ids) {
            if (!STATE.current(ticket)) {
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

    /** The templates of {@code scope}: every placeable one, or those of this world's WWOO buildings. */
    private static List<ResourceLocation> ids(MinecraftServer srv, Scope scope) {
        if (scope == Scope.FULL) return LostCityTemplateIds.placeable(srv.registryAccess());
        ServerLevel overworld = srv.overworld();
        if (overworld == null) return List.of();
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        Set<String> picked = LostCityWwooCensus.buildings(overworld, data.getGenerationSeed(), WorldGenCycle.fromConfig());
        return LostCityTemplateIds.placeable(srv.registryAccess(), id -> picked.contains(LostCityStructures.building(id)));
    }

    /** The cache's state and counters, one line each — {@code /dungeontrain debug lost-city-templates}. */
    public static List<String> status(MinecraftServer srv) {
        List<String> lines = new ArrayList<>();
        int cached;
        try {
            cached = cached(srv.getStructureManager()).size();
        } catch (Throwable t) {
            cached = -1;
        }
        long sinceDemand = LostCityTemplateDemand.secondsSinceLast(System.nanoTime());
        lines.add("mode=" + LostCityTemplatePreload.MODE + " scope=" + STATE.scope() + " running=" + STATE.running()
                + " cached=" + cached + " quietScans=" + quietScans
                + " reach=" + LostCityTemplatePreload.reachBlocks(srv.getPlayerList().getViewDistance()));
        lines.add("preloads=" + PRELOADS.get() + " evictions=" + EVICTIONS.get()
                + " lastStart=" + (sinceDemand < 0 ? "never" : sinceDemand + "s ago"));
        LostCityTemplateLoads.Snapshot loads = LostCityTemplateLoads.snapshot();
        StringBuilder sb = new StringBuilder("coldLookups offThread=" + loads.offThreadCount());
        for (LostCityTemplateLoads.ThreadKind kind : LostCityTemplateLoads.ThreadKind.values()) {
            sb.append(' ').append(kind).append('=').append(loads.count(kind)).append('/').append(loads.millis(kind)).append("ms");
        }
        lines.add(sb.toString());
        return lines;
    }

    /** Zeroes the counters {@link #status} reports (not the cache). */
    public static void resetCounters() {
        PRELOADS.set(0);
        EVICTIONS.set(0);
        LostCityTemplateLoads.reset();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        reset();
        resetCounters();
        server = event.getServer();
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
        server = null;
        reset();
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        // A datapack (re)load swaps the template manager's resources and empties its cache.
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) reset();
    }

    private static void reset() {
        STATE.reset();
        LostCityTemplateDemand.reset();
        quietScans = 0;
        LAST_X.clear();
    }

    private static ExecutorService executor() {
        ExecutorService e = executor;
        if (e != null) return e;
        synchronized (LostCityTemplatePreloadEvents.class) {
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, LostCityTemplateLoads.PRELOAD_THREAD_NAME);
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                });
            }
            return executor;
        }
    }
}
