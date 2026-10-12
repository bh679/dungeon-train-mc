package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.EndBandSampler;
import games.brennan.dungeontrain.worldgen.ForeignSphereSampler;
import games.brennan.dungeontrain.worldgen.SamplerPool;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Ties the shared {@link SamplerPool} to the server it works for, so a world opened after another in the
 * same JVM (a client going from one save to the next, an integrated server restarted) never inherits the
 * old one's jobs or threads.
 *
 * <ul>
 *   <li><b>About to start</b> — the pool accepts jobs again; its threads start with the first one, sized
 *       from the config as it is now.</li>
 *   <li><b>Stopping</b> — waiting jobs are dropped and the threads told to end. From here until the next
 *       start nothing can be queued, so a chunk event during the save can't restart the pool.</li>
 *   <li><b>Stopped</b> — a job that was already running is waited for, then both samplers are cleared once
 *       more: such a job may have published its result, or refilled the End ground cache, after the
 *       samplers' own clear at stopping. Their epoch checks remain the fallback if the wait runs out.</li>
 * </ul>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SamplerPoolEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Longest the stopped server waits for a running sample (one takes 20–150 ms). */
    private static final long QUIESCE_TIMEOUT_MILLIS = 5_000L;

    private SamplerPoolEvents() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        SamplerPool.shared().open();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SamplerPool.shared().close();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (!SamplerPool.shared().awaitQuiescent(QUIESCE_TIMEOUT_MILLIS)) {
            LOGGER.warn("[DungeonTrain] A sampler job was still running {} ms after the server stopped; its result will be discarded",
                    QUIESCE_TIMEOUT_MILLIS);
        }
        EndBandSampler.clear();
        ForeignSphereSampler.clear();
    }
}
