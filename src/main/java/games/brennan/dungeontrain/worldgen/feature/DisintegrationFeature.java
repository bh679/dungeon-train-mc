package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.EndIslandGeometry;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.Set;

/**
 * Worldgen feature that grows the <b>End world-gen</b> portion of the disintegration
 * band: floating End-stone islands shaped by the <em>real</em> End noise router and
 * real chorus plants on top. Runs at {@code top_layer_modification} (it needs a
 * {@link WorldGenLevel} for chorus); the surrounding void erosion is done afterwards
 * by {@code WorldDisintegrationEvents} on chunk load (which preserves end stone +
 * chorus), so trees that spill in from neighbouring chunks are cleaned up too.
 *
 * <p>Island shape comes from {@link EndIslandGeometry} — the real {@code minecraft:end}
 * terrain density, sampled in the outer End and translated onto track level. The same
 * geometry sites {@link games.brennan.dungeontrain.worldgen.structure.BandEndCityStructure}'s
 * End cities, so the cities always stand on the islands this feature stamps. End blocks
 * are stamped with raw section writes; chorus uses the normal worldgen path.</p>
 *
 * <p>End cities are placed earlier in generation (the {@code surface_structures} step), so
 * this feature must not bury them: in the eroded core — where the chunk is otherwise empty —
 * it stamps only into open air, leaving city blocks standing and still filling the island
 * around and beneath them.</p>
 */
public class DisintegrationFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Air pocket (blocks) cleared above an island top so a chorus plant can grow there. */
    private static final int CHORUS_POCKET = 10;
    /** Max chorus attempts per chunk — vanilla {@code CountPlacement.of(UniformInt.of(0, 4))}. */
    private static final int CHORUS_COUNT_BOUND = 5;

    private static final Set<Heightmap.Types> WG_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.WORLD_SURFACE_WG);

    public DisintegrationFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        long genT0 = GenProfiler.t0();
        try {
            return placeInner(ctx);
        } finally {
            GenProfiler.add(GenProfiler.Bucket.DISINTEGRATION, genT0);
        }
    }

    private boolean placeInner(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        try {
            WorldGenLevel level = ctx.level();
            ChunkPos cp = new ChunkPos(ctx.origin());
            ServerLevel serverLevel = level.getLevel();
            MinecraftServer server = serverLevel.getServer();
            if (server == null) return false;
            ServerLevel overworld = server.overworld();
            if (overworld == null) return false;

            long startX = DisintegrationBand.startX(overworld);
            int chunkMinX = cp.getMinBlockX();
            if (chunkMinX + 15 < startX) return false; // before the first band (or disabled)

            ServerLevel end = server.getLevel(Level.END);
            if (end == null) return false;

            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
            CarriageDims dims = data.dims();
            TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
            int bedY = g.bedY();
            // Real End terrain density, translated onto track level — shared with the End-city structure.
            EndIslandGeometry.Source islandSource = EndIslandGeometry.Source.resolve(server, bedY);
            if (islandSource == null) return false;
            double[] endRamp = new double[16];
            boolean anyEnd = false;
            for (int dx = 0; dx < 16; dx++) {
                endRamp[dx] = DisintegrationBand.endIslandRampAt(overworld, chunkMinX + dx);
                if (endRamp[dx] > 0.0) anyEnd = true;
            }
            if (!anyEnd) return false;

            ChunkAccess chunk = level.getChunk(cp.x, cp.z);
            int chunkMinZ = cp.getMinBlockZ();
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight() - 1;

            int[] islandTop = new int[256];
            java.util.Arrays.fill(islandTop, Integer.MIN_VALUE);
            boolean changed = false;

            // Stamp real-End-shaped islands. EndIslandGeometry samples the End density at the noise-cell
            // corners (world-anchored, memoised per chunk) and trilinearly interpolates per block, so
            // island edges taper exactly like the real End — and so the End-city structure, which sites
            // itself off the same geometry, always lands on solid island.
            //
            // Islands are stamped across the whole band, INCLUDING the track lane, so the track feature
            // (which runs AFTER this one — see track_bed_overworld.json) can tunnel/pillar through them.
            EndIslandGeometry geometry = islandSource.open(minY, maxY);
            // In the fully-eroded core the chunk generates empty (the noise fill is short-circuited and
            // vanilla decoration is skipped — see NoiseBasedChunkGeneratorMixin / ChunkGeneratorDecorationMixin),
            // so anything already in it is an End city placed at the earlier surface_structures step.
            // Stamp around it rather than through it. Outside the core there are no cities, and real
            // terrain is still present, so the stamp keeps overwriting as before.
            boolean protectExisting = DisintegrationBand.isChunkFullyEroded(overworld, chunkMinX);

            for (int dx = 0; dx < 16; dx++) {
                double e = endRamp[dx];
                if (e <= 0.0) continue;
                int worldX = chunkMinX + dx;
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    int[] top = {Integer.MIN_VALUE};
                    boolean[] wrote = {false};
                    final int fdx = dx;
                    final int fdz = dz;
                    geometry.forEachSolidY(worldX, worldZ, e, y -> {
                        if (protectExisting && isOccupied(chunk, fdx, y, fdz)) {
                            top[0] = y; // the city's own floor is still island surface for chorus purposes
                            return;
                        }
                        setRaw(chunk, fdx, y, fdz, Blocks.END_STONE.defaultBlockState());
                        top[0] = y;
                        wrote[0] = true;
                    });
                    if (wrote[0]) changed = true;
                    if (top[0] != Integer.MIN_VALUE) islandTop[dx * 16 + dz] = top[0];
                }
            }

            if (!changed) return false;

            Heightmap.primeHeightmaps(chunk, WG_HEIGHTMAPS);

            // Grow real chorus plants — matching vanilla's distribution exactly: 0-4 attempts per chunk
            // (CountPlacement), random X/Z (InSquarePlacement), and ONLY in the end_highlands biome (the
            // sole End biome that carries CHORUS_PLANT). We query the real End biome source at the sample
            // column so chorus lands in the same places it would in the real End — the same patch of outer
            // End that BandEndCityStructure asks about before standing an End city on the island.
            net.minecraft.world.level.biome.BiomeSource endBiomes = end.getChunkSource().getGenerator().getBiomeSource();
            net.minecraft.world.level.biome.Climate.Sampler endSampler = end.getChunkSource().randomState().sampler();
            ChunkGenerator generator = ctx.chunkGenerator();
            RandomSource random = ctx.random();
            int count = random.nextInt(CHORUS_COUNT_BOUND);
            for (int i = 0; i < count; i++) {
                int dx = random.nextInt(16);
                int dz = random.nextInt(16);
                if (endRamp[dx] <= 0.0) continue;
                int top = islandTop[dx * 16 + dz];
                if (top == Integer.MIN_VALUE || top + 1 > maxY) continue;
                int sampleX = chunkMinX + dx + EndIslandGeometry.ISLAND_SAMPLE_OFFSET_X;
                int worldZ = chunkMinZ + dz;
                int endY = EndIslandGeometry.END_ISLAND_CENTER_Y + (top - bedY);
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = endBiomes.getNoiseBiome(
                        net.minecraft.core.QuartPos.fromBlock(sampleX),
                        net.minecraft.core.QuartPos.fromBlock(endY),
                        net.minecraft.core.QuartPos.fromBlock(worldZ), endSampler);
                if (!biome.is(net.minecraft.world.level.biome.Biomes.END_HIGHLANDS)) continue;
                for (int dy = 1; dy <= CHORUS_POCKET && top + dy <= maxY; dy++) {
                    setRaw(chunk, dx, top + dy, dz, Blocks.AIR.defaultBlockState());
                }
                BlockPos pos = new BlockPos(chunkMinX + dx, top + 1, worldZ);
                Feature.CHORUS_PLANT.place(NoneFeatureConfiguration.INSTANCE, level, generator, random, pos);
            }

            chunk.setUnsaved(true);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] DisintegrationFeature.place failed at chunk {}", ctx.origin(), t);
            return false;
        }
    }

    /** True if this chunk position already holds a block (an End city's, in the eroded core). */
    private static boolean isOccupied(ChunkAccess chunk, int dx, int y, int dz) {
        int sIdx = chunk.getSectionIndex(y);
        if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) return false;
        LevelChunkSection section = chunk.getSection(sIdx);
        int ly = y - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
        return !section.getBlockState(dx, ly, dz).isAir();
    }

    /** Raw block stamp into the section owning {@code y} (no level-side hooks; heightmaps re-primed after). */
    private static void setRaw(ChunkAccess chunk, int dx, int y, int dz, net.minecraft.world.level.block.state.BlockState state) {
        int sIdx = chunk.getSectionIndex(y);
        if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) return;
        LevelChunkSection section = chunk.getSection(sIdx);
        int ly = y - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
        // Drop any orphaned block entity (e.g. a structure chest) before overwriting the block,
        // otherwise the chunk logs "Invalid block entity ... got air/end_stone" on load.
        if (section.getBlockState(dx, ly, dz).hasBlockEntity()) {
            chunk.removeBlockEntity(new BlockPos(chunk.getPos().getMinBlockX() + dx, y, chunk.getPos().getMinBlockZ() + dz));
        }
        section.setBlockState(dx, ly, dz, state, false);
    }
}
