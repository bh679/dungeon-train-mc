package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    private DistantHorizonsLod() {}

    /**
     * Best-effort "this chunk's blocks changed, re-read it". No-op when DH is absent, still initialising,
     * too old, or the level isn't the one DH has loaded (multiplayer — {@code getSinglePlayerLevel}
     * returns null there, and a dedicated server wouldn't have DH's client LODs anyway).
     */
    public static void chunkChanged(ServerLevel level, ChunkAccess chunk) {
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
