package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelGenerator;
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
 * Worldgen feature that paints the train's bed + rails AND the pillars
 * supporting them into a chunk during generation. Newly generated chunks
 * thus have the full corridor (track, clearance, pillars) as part of the
 * chunk save itself, with zero ongoing tick cost.
 *
 * <p>Pillars are placed first (before bed/rails) so the ground probe sees
 * raw terrain instead of the bed/rail rows. Each chunk plants the pillars
 * whose center X lies inside its bounds; pillar slices overflow up to
 * ⌊thickness/2⌋ blocks into the immediate neighbour (within the standard
 * 3×3 decoration window). Stairs are deferred to a later iteration —
 * minimum-spacing rules require ±40-block X data that exceeds single-chunk
 * scope.</p>
 *
 * <p>Wired by datapack: {@code data/dungeontrain/worldgen/configured_feature/track_bed.json}
 * → {@code .../placed_feature/track_bed.json} → three {@code
 * neoforge/biome_modifier/} JSONs (overworld / nether / end). Only the
 * modifier matching the world's chosen starting dimension ever runs because
 * chunks only generate against their dimension's biome registry.</p>
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
            // No X prefilter — the corridor extends in both directions on
            // X. The train spawn is centered (carriages from -halfBack to
            // +halfFront indices), and the player can look back along the
            // tracks to see them disappearing into the distance.

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
            // Pillars first — the ground probe reads raw terrain at probeZ,
            // which becomes opaque (bed/rail) as soon as placeTracksForChunk
            // runs. Order matters.
            TrackGenerator.placePillarsAtWorldgen(level, serverLevel, dims, chunkPos.x, chunkPos.z, g);
            TrackGenerator.placeTracksForChunk(level, serverLevel, dims, chunkPos.x, chunkPos.z, g);
            // Tunnel space — single-phase per-column NBT placement (or
            // LegacyTunnelPaint fallback when NBTs missing). Underground
            // qualification reads at ceilingY+5, well above the bed/rails,
            // so order vs. tracks doesn't matter.
            TunnelGenerator.placeTunnelSpaceAtWorldgen(level, serverLevel, chunkPos.x, chunkPos.z, g);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] TrackBedFeature.place failed at chunk {}", ctx.origin(), t);
            return false;
        }
    }
}
