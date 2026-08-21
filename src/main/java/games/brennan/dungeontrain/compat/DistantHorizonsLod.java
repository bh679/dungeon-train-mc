package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tells <b>Distant Horizons</b> to re-read a chunk DT rewrote after generation, so its LOD copy stops
 * showing the pre-rewrite terrain.
 *
 * <p>The upside-down band's mirror is applied to already-loaded chunks — deferred a few ticks past
 * {@code ChunkEvent.Load} and written through the raw, light-skipping
 * {@link net.minecraft.world.level.chunk.LevelChunkSection#setBlockState} primitive, which fires no
 * block-change events. DH has usually already ingested the chunk by then, and nothing in those writes
 * tells it otherwise — so it keeps the un-mirrored copy and renders right-way-up slabs hanging under the
 * mirrored ceiling. {@code ChunkGeneratorDhLodMirrorMixin} fixes DH's <em>own</em> distant generation;
 * this fixes the chunks DH took from the live world.
 *
 * <p>DH is an optional dependency and deliberately not on the compile classpath, so the API is reached
 * reflectively and every failure degrades to "DH doesn't learn about this chunk" — never an exception
 * into the mirror path. Same idiom as the Iris probe in
 * {@link games.brennan.dungeontrain.client.GraphicsCapabilities}. Needs DH API ≥ 3.0.0
 * ({@code overwriteChunkDataAsync}); on anything older the lookup fails once and the class goes quiet.
 *
 * <p><b>Why this is a queue and not a direct call.</b> DH turns each notification into
 * {@code SharedApi.applyChunkUpdate} — its ordinary chunk-update path, which rebuilds the LOD render
 * buffer covering that chunk. Notifying inline, as the mirror drain applies chunks, drove a continuous
 * storm of those rebuilds right where the camera looks: each one leaves its area briefly undrawn, and at
 * band altitude the clear colour behind it is near-white, so the sky appeared to flash. Nothing reads the
 * refreshed LOD until the chunk is far enough away for DH to render it, so the notifications are
 * coalesced here and released a few per tick by {@code TrainTickEvents}, which costs nothing visually and
 * takes the storm apart.
 *
 * <p>{@code DhApi.Delayed}'s fields are null until DH finishes initialising, so the field <em>objects</em>
 * are cached but their values are read per call.
 */
public final class DistantHorizonsLod {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String MOD_ID = "distanthorizons";
    private static final String DELAYED_CLASS = "com.seibel.distanthorizons.api.DhApi$Delayed";

    // Resolved once, lazily. `resolved` guards the one-time attempt so a missing/old DH doesn't re-scan
    // the classpath on every mirrored chunk; the handles stay null when DH can't be reached.
    private static volatile boolean resolved = false;
    private static Field worldProxyField;
    private static Field terrainRepoField;
    private static Method worldLoaded;
    private static Method getSinglePlayerLevel;
    private static Method overwriteChunkDataAsync;

    /** One-shot log so the first notify (or first failure) is visible without spamming per chunk. */
    private static volatile boolean loggedOutcome = false;

    // Counters behind the [dh-lod] summary line. Every path a chunk can take is counted, so a silent
    // no-op is visible as a count that doesn't add up rather than as an absence of logs.
    private static int statEnqueued = 0;
    private static int statFlushMatched = 0;
    private static int statSent = 0;
    private static int statLastSummaryEnqueued = -1;

    /**
     * Chunk keys waiting to be handed to DH, coalesced (re-mirroring a chunk doesn't queue it twice) and
     * in insertion order — which is roughly nearest-first, since the mirror drain that fills this is.
     * Server-thread only: filled from the mirror drain, emptied from the level tick.
     */
    private static final Set<Long> pending = new LinkedHashSet<>();

    /** How the notify behaves. Flip live with {@code /dungeontrain debug dh-lod-refresh}. */
    public enum Mode {
        /**
         * Default: hold each mirrored chunk until it falls outside every player's vanilla render distance,
         * then tell DH. Inside that radius the chunk's LOD is hidden behind real geometry, so refreshing it
         * there buys nothing and only puts a rebuild in view; outside it, DH's copy is what you actually
         * see, so it has to be right. Rate-limited so a burst leaving the radius at once still trickles.
         */
        VIEW_EXIT,
        /** Notify inline the moment a chunk is mirrored — the white-flash behaviour, kept for A/B. */
        INSTANT,
        /** Never notify: DH keeps whatever it ingested, so in-band LODs can render upright. */
        OFF
    }

    public static volatile Mode mode = Mode.VIEW_EXIT;

    /** Chunks past the view radius before we bother DH — one ring of slack, so a player idling on the
     *  boundary doesn't make chunks flip back and forth across the test. */
    private static final int VIEW_EXIT_MARGIN_CHUNKS = 1;

    /** Cap on queue entries examined per tick, so a long backlog can't turn the scan into tick cost. */
    private static final int MAX_SCAN_PER_TICK = 512;

    private DistantHorizonsLod() {}

    /**
     * Record that {@code chunk}'s blocks changed. Queued rather than sent, unless {@link Mode#INSTANT}.
     * Call on the server thread.
     */
    public static void chunkChanged(ServerLevel level, ChunkAccess chunk) {
        switch (mode) {
            case OFF -> { }
            case INSTANT -> notifyChunk(level, chunk);
            case VIEW_EXIT -> {
                if (pending.add(chunk.getPos().toLong())) statEnqueued++;
            }
        }
    }

    /**
     * Hand at most {@code maxPerTick} queued chunks to DH. A chunk that has since unloaded is dropped —
     * we can't hand DH a chunk we can't read, and DH will re-read it from its own data source anyway.
     * Call on the server thread, once per level tick.
     */
    public static void drain(ServerLevel level, int maxPerTick) {
        if (pending.isEmpty() || mode != Mode.VIEW_EXIT) return;

        int viewChunks = level.getServer().getPlayerList().getViewDistance() + VIEW_EXIT_MARGIN_CHUNKS;
        long viewSqr = (long) viewChunks * viewChunks;

        int sent = 0;
        int scanned = 0;
        Iterator<Long> it = pending.iterator();
        while (it.hasNext() && sent < maxPerTick && scanned < MAX_SCAN_PER_TICK) {
            long key = it.next();
            scanned++;
            if (!beyondViewDistance(level, key, viewSqr)) continue;   // still hidden behind real geometry

            it.remove();
            ChunkAccess chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                // Unloaded between leaving the view radius and being drained; flushOnUnload normally
                // catches this first, and DH re-reads from its own data source either way.
                LOGGER.debug("[DungeonTrain] DH LOD refresh: chunk {} unloaded before its turn", new ChunkPos(key));
                continue;
            }
            notifyChunk(level, chunk);
            sent++;
        }
    }

    /**
     * True when {@code key} is outside {@code viewSqr} (in chunks, squared) of every player — i.e. no
     * player is rendering it as real geometry any more, so DH's LOD for it is now what's on screen.
     * A level with no players trivially qualifies.
     */
    private static boolean beyondViewDistance(ServerLevel level, long key, long viewSqr) {
        int cx = ChunkPos.getX(key);
        int cz = ChunkPos.getZ(key);
        for (ServerPlayer player : level.players()) {
            long dx = cx - SectionPos.blockToSectionCoord(player.getBlockX());
            long dz = cz - SectionPos.blockToSectionCoord(player.getBlockZ());
            if (dx * dx + dz * dz <= viewSqr) return false;
        }
        return true;
    }

    /**
     * Last chance to tell DH about a chunk: it is unloading, so this is both the final moment its blocks
     * are readable and the moment DH's LOD becomes the only thing representing it on screen. Without this
     * a queued chunk would simply be dropped when it unloaded — which is exactly how a rate-limited queue
     * silently let the band go back to rendering upright in the distance.
     */
    public static void flushOnUnload(ServerLevel level, ChunkAccess chunk) {
        if (pending.isEmpty()) return;
        if (pending.remove(chunk.getPos().toLong())) {
            statFlushMatched++;
            notifyChunk(level, chunk);
        }
    }

    /** Drop anything queued, so a chunk from one world can never be sent against the next. */
    public static void clear() {
        pending.clear();
    }

    /**
     * Log a {@code [dh-lod]} line when anything has moved since the last one. Counts every path a chunk
     * can take — queued, matched at unload, actually handed to DH — so a silent no-op shows up as
     * numbers that don't add up, rather than as no log at all.
     */
    public static void logSummary() {
        if (statEnqueued == statLastSummaryEnqueued) return;
        statLastSummaryEnqueued = statEnqueued;
        LOGGER.info("[dh-lod] mode={} enqueued={} flushMatched={} sent={} queued={}",
                mode, statEnqueued, statFlushMatched, statSent, pending.size());
    }

    /** Queue depth, for debug readouts. */
    public static int pendingCount() {
        return pending.size();
    }

    /**
     * Best-effort "this chunk's blocks changed, re-read it". No-op when DH is absent, still initialising,
     * too old, or the level isn't the one DH has loaded (multiplayer — {@code getSinglePlayerLevel}
     * returns null there, and a dedicated server wouldn't have DH's client LODs anyway).
     */
    private static void notifyChunk(ServerLevel level, ChunkAccess chunk) {
        try {
            resolve();
            if (overwriteChunkDataAsync == null) {
                logOnce("[DungeonTrain] DH LOD refresh idle: API not reachable", null);
                return;
            }

            Object worldProxy = worldProxyField.get(null);
            Object terrainRepo = terrainRepoField.get(null);
            if (worldProxy == null || terrainRepo == null) {
                logOnce("[DungeonTrain] DH LOD refresh idle: DH not initialised yet", null);
                return;
            }
            if (!(worldLoaded.invoke(worldProxy) instanceof Boolean loaded) || !loaded) {
                logOnce("[DungeonTrain] DH LOD refresh idle: DH reports no world loaded", null);
                return;
            }

            Object levelWrapper = getSinglePlayerLevel.invoke(worldProxy);
            if (levelWrapper == null) {
                logOnce("[DungeonTrain] DH LOD refresh idle: DH has no single-player level", null);
                return;
            }

            // DH expects {ChunkAccess, ServerLevel} — see IDhApiWorldGenerator#generateChunks. DH warns
            // these are Minecraft-version dependent, hence the catch-all below.
            overwriteChunkDataAsync.invoke(terrainRepo, levelWrapper, new Object[]{chunk, level});
            statSent++;
            logOnce("[DungeonTrain] Distant Horizons notified of mirrored chunks", null);
        } catch (Throwable t) {
            logOnce("[DungeonTrain] Distant Horizons LOD notify unavailable; in-band LODs may render upright", t);
        }
    }

    private static void resolve() {
        if (resolved) return;
        synchronized (DistantHorizonsLod.class) {
            if (resolved) return;
            try {
                if (!ModList.get().isLoaded(MOD_ID)) return;
                Class<?> delayed = Class.forName(DELAYED_CLASS, false, DistantHorizonsLod.class.getClassLoader());
                worldProxyField = delayed.getField("worldProxy");
                terrainRepoField = delayed.getField("terrainRepo");
                worldLoaded = worldProxyField.getType().getMethod("worldLoaded");
                getSinglePlayerLevel = worldProxyField.getType().getMethod("getSinglePlayerLevel");
                for (Method m : terrainRepoField.getType().getMethods()) {
                    if (m.getName().equals("overwriteChunkDataAsync")) {
                        overwriteChunkDataAsync = m;
                        break;
                    }
                }
                if (overwriteChunkDataAsync == null) {
                    LOGGER.info("[DungeonTrain] Distant Horizons is present but too old for LOD refresh (needs API 3.0.0+)");
                }
            } catch (Throwable t) {
                LOGGER.debug("[DungeonTrain] Distant Horizons API probe unavailable: {}", t.toString());
            } finally {
                resolved = true;
            }
        }
    }

    private static void logOnce(String message, Throwable t) {
        if (loggedOutcome) return;
        loggedOutcome = true;
        if (t == null) {
            LOGGER.info(message);
        } else {
            LOGGER.warn("{}: {}", message, t.toString());
        }
    }
}
