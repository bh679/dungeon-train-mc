package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Starts the overworld's stronghold ring search only once DT's biome context is published.
 *
 * <p>Vanilla starts it inside the {@code ServerLevel} constructor: 128 background jobs, each asking the
 * overworld biome source for a {@code #stronghold_biased_to} biome near a ring point. That is before the
 * overworld's {@code LevelEvent.Load}, where {@code NetherBandContextEvents} publishes the legacy-band
 * biomes and the second-lap stretch biomes — so the jobs raced the publish, and the same world could
 * compute different rings on different boots (eyes of ender pointing where no stronghold generated).
 * {@code ServerLevelDeferRingGenMixin} skips that eager start for the overworld and
 * {@link #start} runs it from the publish instead, on the server thread as vanilla does.</p>
 *
 * <p>The jobs stay asynchronous. Later republishes (Nether/End Load, ServerStarted) cannot change their
 * answers: ring queries run at y=0, below the sea-level gate of the band columns, and the legacy and
 * stretch biomes depend only on the overworld, the seed and the config.</p>
 */
public final class StrongholdRingGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    private StrongholdRingGate() {}

    /**
     * Starts the overworld's ring search. Idempotent — vanilla's {@code hasGeneratedPositions} makes every
     * call after the first a no-op. On error the rings are still built lazily by the first reader.
     */
    public static void start(ServerLevel overworld) {
        try {
            overworld.getChunkSource().getGeneratorState().ensureStructuresGenerated();
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Failed to start the stronghold ring search; it will start on first use", t);
        }
    }
}
