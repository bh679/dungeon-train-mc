package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Publishes the per-world {@link NetherBandContext} the terrain-noise mountain wrapper reads
 * lazily, and clears it on shutdown. Common-scope (not {@code Dist.CLIENT}) so dedicated servers
 * publish it too — unlike {@code WorldLifecycleEvents}, which is client-only.
 *
 * <p>Runs at {@link EventPriority#LOW} so {@code WorldLifecycleEvents.onServerStarted} (NORMAL,
 * client-only) has already committed pending world-creation choices into
 * {@link DungeonTrainWorldData} (train geometry, {@code startsWithTrain}). The router/DF were
 * already built during level construction; the DF no-ops until this snapshot lands — and the first
 * nether band sits thousands of blocks from spawn, so it is always published before any band chunk
 * generates. Cleared on stop so a singleplayer world-switch in the same JVM never reuses a stale
 * seed/layout.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NetherBandContextEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Nether-space rows sampled above the bed in {@code NetherTransitionFeature}
     *  ({@code NETHER_SAMPLE_Y_MAX 120 − NETHER_CENTER_Y 40}); the mountain tapers to {@code bedY + this}. */
    private static final int NETHER_TOP_ABOVE_BED = 80;

    private NetherBandContextEvents() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            ServerLevel overworld = event.getServer().overworld();
            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);

            boolean enabled = NetherBand.startX(overworld) != NetherBand.OFF;
            WorldGenCycle cycle = WorldGenCycle.fromConfig();
            int seaLevel = overworld.getSeaLevel();
            int worldCeiling = overworld.getMaxBuildHeight() - 1;
            int baseRelief = DungeonTrainCommonConfig.getNetherBaseReliefBlocks();
            int bedY = TrackGeometry.from(data.dims(), data.getTrainY()).bedY();
            int netherTop = bedY + NETHER_TOP_ABOVE_BED;

            NetherBandContext.publish(new NetherBandContext(
                    enabled, data.getGenerationSeed(), seaLevel, worldCeiling, netherTop, baseRelief, cycle));
            LOGGER.info("[DungeonTrain] Nether-band terrain context published: enabled={} seaLevel={} worldCeiling={} netherTop={} baseRelief={}",
                    enabled, seaLevel, worldCeiling, netherTop, baseRelief);
        } catch (Throwable t) {
            // Never block server start on the band snapshot — a missing context just leaves terrain vanilla.
            NetherBandContext.clear();
            LOGGER.error("[DungeonTrain] Failed to publish nether-band terrain context; mountains stay flat this session", t);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        NetherBandContext.clear();
    }
}
