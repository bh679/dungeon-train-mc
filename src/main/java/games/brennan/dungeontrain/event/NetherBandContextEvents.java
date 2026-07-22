package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.EndCoreBiomes;
import games.brennan.dungeontrain.worldgen.density.NetherBandBiomeSet;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import games.brennan.dungeontrain.worldgen.density.NetherCoreBiomes;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.BiomeSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Publishes the per-world {@link NetherBandContext} the terrain-noise mountain wrapper reads
 * lazily, and clears it on shutdown. Common-scope (not {@code Dist.CLIENT}) so dedicated servers
 * publish it too — unlike {@code WorldLifecycleEvents}, which is client-only.
 *
 * <p>Published (and re-published) on every server-side {@link LevelEvent.Load}: the overworld's
 * Load fires first inside {@code MinecraftServer.createLevels} — before spawn-region chunk
 * generation in {@code prepareLevels} — publishing with fallback Nether/End core biomes; the
 * Nether's and End's own Load events then republish with their real climate samplers captured.
 * All of this completes before the first chunk bakes, so no chunk ever generates against a null
 * context (the old {@code ServerStartedEvent}-only publish lost that race for spawn-region and
 * pregen chunks). A {@link ServerStartedEvent} refresh is kept as an idempotent last word for
 * dimensions registered late by other mods. Runs at {@link EventPriority#LOW} so
 * {@code WorldLifecycleEvents.onOverworldLoad} (HIGH, client-only) has already committed pending
 * world-creation choices into {@link DungeonTrainWorldData} (train geometry,
 * {@code startsWithTrain}) on the same event. Cleared on stop so a singleplayer world-switch in
 * the same JVM never reuses a stale seed/layout.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NetherBandContextEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Nether-space rows sampled above the bed in {@code NetherTransitionFeature}
     *  ({@code NETHER_SAMPLE_Y_MAX 120 − NETHER_CENTER_Y 40}); the mountain tapers to {@code bedY + this}. */
    private static final int NETHER_TOP_ABOVE_BED = 80;

    private NetherBandContextEvents() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLevelLoad(LevelEvent.Load event) {
        // Fires per dimension during createLevels (and for ClientLevel — gate it out).
        // Republishing on each server-side Load upgrades the snapshot as the Nether/End
        // samplers become available, all before any chunk bakes.
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        publish(level.getServer(), false);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerStarted(ServerStartedEvent event) {
        // Idempotent final refresh (deterministic inputs → value-identical republish);
        // covers dimensions registered after createLevels by other mods.
        publish(event.getServer(), true);
    }

    private static void publish(MinecraftServer server, boolean logInfo) {
        try {
            ServerLevel overworld = server.overworld();
            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);

            boolean enabled = NetherBand.startX(overworld) != NetherBand.OFF;
            WorldGenCycle cycle = WorldGenCycle.fromConfig();
            int seaLevel = overworld.getSeaLevel();
            int worldCeiling = overworld.getMaxBuildHeight() - 1;
            int baseRelief = DungeonTrainCommonConfig.getNetherBaseReliefBlocks();
            int bedY = TrackGeometry.from(data.dims(), data.getTrainY()).bedY();
            int netherTop = bedY + NETHER_TOP_ABOVE_BED;

            // Overworld biome source (identity gate) + resolved highland palette for the biome-source mixin.
            BiomeSource overworldBiomeSource = overworld.getChunkSource().getGenerator().getBiomeSource();
            NetherBandBiomeSet highlandBiomes = NetherBandBiomeSet.resolve(
                    overworld.registryAccess().lookupOrThrow(Registries.BIOME), data.getGenerationSeed());
            // Core columns sample ALL five real Nether biomes the way the Nether does (red/teal/blue/grey
            // fog + per-biome decoration & surface skin, and the vanilla Nether decoration's biome filter
            // passes). Captures the live Nether dimension's biome source + climate sampler; falls back to
            // nether_wastes if this world has no Nether.
            Holder<Biome> netherFallback = overworld.registryAccess()
                    .lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES);
            NetherCoreBiomes netherCoreBiomes = NetherCoreBiomes.resolve(server, netherFallback);

            // Same idea for the End band's core columns — samples the real End's biome source, swept
            // from the main island out into the outer noise field across successive End-band passes.
            Holder<Biome> endFallback = overworld.registryAccess()
                    .lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_END);
            EndCoreBiomes endCoreBiomes = EndCoreBiomes.resolve(server, endFallback);

            NetherBandContext.publish(new NetherBandContext(
                    enabled, data.getGenerationSeed(), seaLevel, worldCeiling, netherTop, baseRelief, cycle,
                    overworldBiomeSource, highlandBiomes, netherCoreBiomes, endCoreBiomes));
            // Intermediate per-dimension-load republishes log at debug to avoid 3+ identical
            // info lines per start; the ServerStarted refresh logs the final snapshot at info.
            if (logInfo) {
                LOGGER.info("[DungeonTrain] Nether-band terrain context published: enabled={} seaLevel={} worldCeiling={} netherTop={} baseRelief={}",
                        enabled, seaLevel, worldCeiling, netherTop, baseRelief);
            } else {
                LOGGER.debug("[DungeonTrain] Nether-band terrain context (re)published on level load: enabled={}", enabled);
            }
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
