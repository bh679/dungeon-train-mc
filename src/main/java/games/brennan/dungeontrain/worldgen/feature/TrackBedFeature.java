package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.slf4j.Logger;

/**
 * Worldgen feature that paints the train's bed + rails into a chunk during
 * generation. Replaces the post-load painting path that {@code TrackChunkEvents}
 * used to drive — newly generated chunks now have tracks as part of the
 * chunk save itself, with zero ongoing tick cost.
 *
 * <p>Pillars are deliberately left for a deferred pass ({@code
 * PillarDeferredEvents}) because they read terrain heightmap from up to
 * ±40 blocks on X — neighbouring chunks aren't reliably generated yet
 * during the per-chunk Feature call.</p>
 *
 * <p>Wired by datapack: {@code data/dungeontrain/worldgen/configured_feature/track_bed.json}
 * → {@code .../placed_feature/track_bed.json} → three {@code
 * forge/biome_modifier/} JSONs (overworld / nether / end). Only the modifier
 * matching the world's chosen starting dimension ever runs because chunks
 * only generate against their dimension's biome registry.</p>
 */
public class TrackBedFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Highest chunk-Z that any train corridor can reach. Train origin is
     * always at world Z=0 ({@code TrainBootstrapEvents.onServerStarted}),
     * so the corridor spans Z=0..(width-1). Even a maxed-out
     * {@link CarriageDims#MAX_WIDTH}=32 corridor lands within chunks
     * cz ∈ {0, 1}. Used as a pure-arithmetic fast reject before any
     * SavedData lookup — keeps the per-chunk cost on the rejected ~99% of
     * overworld chunks down to a method call + two int comparisons.
     */
    private static final int MAX_CORRIDOR_CZ = (CarriageDims.MAX_WIDTH - 1) >> 4;

    public TrackBedFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        try {
            WorldGenLevel level = ctx.level();
            ChunkPos chunkPos = new ChunkPos(ctx.origin());

            // Fast Z-corridor prefilter — pure arithmetic, no SavedData.
            // Train always spawns at world Z=0 so the corridor is at most
            // {@link #MAX_CORRIDOR_CZ}+1 chunks wide on Z. Outside that
            // strip there's no possible corridor, regardless of the
            // per-world CarriageDims width.
            if (chunkPos.z < 0 || chunkPos.z > MAX_CORRIDOR_CZ) return false;
            // Train spawns at world X=0 and moves only +X (TRAIN_VELOCITY in
            // TrainBootstrapEvents has Y=Z=0; TrainChainManager spawns
            // successors AHEAD only). Skip negative-X chunks — the train
            // never reaches them, and pre-feature the legacy painter never
            // painted them either (it only ran around the live train).
            // Halves the corridor work for any world where the player
            // explores backward.
            if (chunkPos.x < 0) return false;

            // Reach the ServerLevel for SavedData lookup. WorldGenRegion
            // (the standard WorldGenLevel impl) wraps the ServerLevel under
            // generation — getLevel() returns it.
            ServerLevel serverLevel = level.getLevel();
            net.minecraft.server.MinecraftServer server = serverLevel.getServer();
            if (server == null) return false; // defensive — shouldn't happen post server init
            ServerLevel overworld = server.overworld();
            if (overworld == null) return false;

            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);

            // Defensive dimension guard — biome modifier targets exactly
            // one dimension by tag, but a 3rd-party datapack could
            // mis-attach our modifier.
            StartingDimension expected = data.startingDimension();
            if (!serverLevel.dimension().equals(expected.levelKey())) {
                return false;
            }

            CarriageDims dims = data.dims();
            TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
            TrackGenerator.placeTracksForChunk(level, serverLevel, dims, chunkPos.x, chunkPos.z, g);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] TrackBedFeature.place failed at chunk {}", ctx.origin(), t);
            return false;
        }
    }
}
