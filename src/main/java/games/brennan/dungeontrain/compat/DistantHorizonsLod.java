package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
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

    /**
     * Chunk keys waiting to be handed to DH, coalesced (re-mirroring a chunk doesn't queue it twice) and
     * in insertion order — which is roughly nearest-first, since the mirror drain that fills this is.
     * Server-thread only: filled from the mirror drain, emptied from the level tick.
     */
    private static final Set<Long> pending = new LinkedHashSet<>();

    /** How the notify behaves. Flip live with {@code /dungeontrain debug dh-lod-refresh}. */
    public enum Mode {
        /** Default: queue, and release a few chunks per tick. */
        THROTTLED,
        /** Notify inline the moment a chunk is mirrored — the white-flash behaviour, kept for A/B. */
        INSTANT,
        /** Never notify: DH keeps whatever it ingested, so in-band LODs can render upright. */
        OFF
    }

    public static volatile Mode mode = Mode.THROTTLED;

    private DistantHorizonsLod() {}

    /**
     * Record that {@code chunk}'s blocks changed. Queued rather than sent, unless {@link Mode#INSTANT}.
     * Call on the server thread.
     */
    public static void chunkChanged(ServerLevel level, ChunkAccess chunk) {
        switch (mode) {
            case OFF -> { }
            case INSTANT -> notifyChunk(level, chunk);
            case THROTTLED -> pending.add(chunk.getPos().toLong());
        }
    }

    /**
     * Hand at most {@code maxPerTick} queued chunks to DH. A chunk that has since unloaded is dropped —
     * we can't hand DH a chunk we can't read, and DH will re-read it from its own data source anyway.
     * Call on the server thread, once per level tick.
     */
    public static void drain(ServerLevel level, int maxPerTick) {
        if (pending.isEmpty() || mode != Mode.THROTTLED) return;
        int sent = 0;
        Iterator<Long> it = pending.iterator();
        while (it.hasNext() && sent < maxPerTick) {
            long key = it.next();
            it.remove();
            ChunkAccess chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                LOGGER.debug("[DungeonTrain] skipping DH LOD refresh for unloaded chunk {}", new ChunkPos(key));
                continue;
            }
            notifyChunk(level, chunk);
            sent++;
        }
    }

    /** Drop anything queued, so a chunk from one world can never be sent against the next. */
    public static void clear() {
        pending.clear();
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
            if (overwriteChunkDataAsync == null) return;

            Object worldProxy = worldProxyField.get(null);
            Object terrainRepo = terrainRepoField.get(null);
            if (worldProxy == null || terrainRepo == null) return;      // DH not initialised yet
            if (!(worldLoaded.invoke(worldProxy) instanceof Boolean loaded) || !loaded) return;

            Object levelWrapper = getSinglePlayerLevel.invoke(worldProxy);
            if (levelWrapper == null) return;

            // DH expects {ChunkAccess, ServerLevel} — see IDhApiWorldGenerator#generateChunks. DH warns
            // these are Minecraft-version dependent, hence the catch-all below.
            overwriteChunkDataAsync.invoke(terrainRepo, levelWrapper, new Object[]{chunk, level});
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
