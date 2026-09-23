package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.FallingBlockAnchor;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.SphereField;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import games.brennan.dungeontrain.worldgen.SunlitChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Carves the {@link SpheresBand}: keeps only the terrain inside each floating sphere — lifted to the
 * sphere's display height — and erases everything else to void. Vanilla terrain generates normally
 * for every chunk a sphere touches (chunks over open void are already all-air from
 * {@code NoiseBasedChunkGeneratorMixin}); this pass then rewrites each column from a pristine
 * snapshot, the {@code UpsideDownMirror} gather-write idiom:
 *
 * <ul>
 *   <li>Inside a sphere the block at {@code y} becomes the natural block from {@code y − dy} of the
 *       same column ({@link SphereField.Sphere#sourceY}). Blocks carrying a block entity are dropped
 *       (their data can't move with them), liquids become still sources, and fallable blocks
 *       (sand/gravel) become their stable equivalent so nothing rains out of a sphere's underside.</li>
 *   <li>Outside every sphere the block is erased — outright in the core, and noise-dithered against
 *       the depth-weighted End erosion probability across the entry fade, so the terrain crumbles
 *       from below as the spheres begin.</li>
 *   <li>Only the track itself is preserved — the bed row and the rail row across the track's own
 *       Z-lane, exactly what {@code WorldDisintegrationEvents} keeps in the End fade. The tunnel
 *       shell a mountain earned, the pillars under the bed and any terrain in the lane are ordinary
 *       blocks: they dissolve with the ground they belonged to, so no stone-brick tube outlives its
 *       mountain. The train's airspace ({@code airMinZ..airMaxZ} × the rows between bed and tunnel
 *       ceiling) is carved through any sphere that crosses the track, so the bed floats over open
 *       void and a crossing sphere gets a clean slot cut through it.</li>
 * </ul>
 *
 * <p>Runs on {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()} — once at
 * generation, never on reload (player builds survive), after all decoration. Inline on the server
 * thread like {@code WorldChuncksEvents}: a fresh chunk is not yet shared with light workers, so the
 * raw {@link LevelChunkSection#setBlockState} write (the Sable-safe path) needs no neighbourhood
 * guard. Heightmaps are re-primed afterwards so spawning and weather read the lifted surface, and
 * the chunk is lit as open sky ({@link SunlitChunks}) because those raw writes never reach the light
 * engine.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldSpheresEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    private static final Set<Heightmap.Types> FULL_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR);

    private WorldSpheresEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (SpheresBand.startX(level) == SpheresBand.OFF) return; // band disabled / no train

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        int chunkMinZ = pos.getMinBlockZ();
        if (!SpheresBand.chunkTouchesBand(level, chunkMinX)) return;

        long genT0 = GenProfiler.t0();
        boolean changed = carve(level, chunk, chunkMinX, chunkMinZ);
        GenProfiler.add(GenProfiler.Bucket.SPHERES_CARVE, genT0);
        if (changed) {
            Heightmap.primeHeightmaps(chunk, FULL_HEIGHTMAPS);
            chunk.setUnsaved(true);
            SunlitChunks.sunlight(level, chunk);   // raw writes skipped the light engine: light it as open sky
        }
    }

    /** The column-by-column rewrite; true if any block changed. */
    private static boolean carve(ServerLevel level, ChunkAccess chunk, int chunkMinX, int chunkMinZ) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        long seed = data.getGenerationSeed();
        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        TunnelGeometry tg = TunnelGeometry.from(g);
        int bedY = g.bedY();
        int laneZMin = g.trackZMin(), laneZMax = g.trackZMax();     // bed + rail rows survive here
        int railY = g.railY();
        int airZMin = tg.airMinZ(), airZMax = tg.airMaxZ();         // train airspace carved through spheres
        int airYMin = bedY + 1, airYMax = tg.ceilingY() - 1;

        double[] ramp = new double[16];
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            ramp[dx] = SpheresBand.voidRamp(level, chunkMinX + dx);
            any |= ramp[dx] > 0.0;
        }
        if (!any) return false;

        List<SphereField.Sphere> candidates = SpheresBand.candidates(level, chunkMinX >> 4, chunkMinZ >> 4);
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        BlockState[] col = new BlockState[maxY - minY];
        List<SphereField.Sphere> colSpheres = new ArrayList<>(4);
        boolean changed = false;

        for (int dx = 0; dx < 16; dx++) {
            if (ramp[dx] <= 0.0) continue;                         // before the fade: natural terrain untouched
            int worldX = chunkMinX + dx;
            boolean core = ramp[dx] >= 1.0;
            for (int dz = 0; dz < 16; dz++) {
                int worldZ = chunkMinZ + dz;
                boolean laneZ = worldZ >= laneZMin && worldZ <= laneZMax;
                boolean airZ = worldZ >= airZMin && worldZ <= airZMax;

                colSpheres.clear();
                for (SphereField.Sphere s : candidates) {
                    if (s.touchesColumn(worldX, worldZ)) colSpheres.add(s);
                }
                if (colSpheres.isEmpty() && core && !laneZ) {
                    changed |= clearColumn(chunk, dx, dz, worldX, worldZ, minY, maxY);
                    continue;
                }

                snapshot(chunk, dx, dz, col, minY);
                changed |= rewriteColumn(chunk, dx, dz, worldX, worldZ, col, colSpheres, ramp[dx], core,
                        laneZ, airZ, railY, airYMin, airYMax, bedY, seed, minY, maxY);
            }
        }
        return changed;
    }

    /** Fill {@code col} with the column's current blocks (all-air sections read as air). */
    private static void snapshot(ChunkAccess chunk, int dx, int dz, BlockState[] col, int minY) {
        Arrays.fill(col, AIR);
        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            if (section.hasOnlyAir()) continue;
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            for (int ly = 0; ly < 16; ly++) {
                col[baseY + ly - minY] = section.getBlockState(dx, ly, dz);
            }
        }
    }

    /** Erase a whole column (open void, no corridor); true if anything was non-air. */
    private static boolean clearColumn(ChunkAccess chunk, int dx, int dz, int worldX, int worldZ, int minY, int maxY) {
        boolean changed = false;
        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            if (section.hasOnlyAir()) continue;
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                if (y < minY || y >= maxY) continue;
                BlockState cur = section.getBlockState(dx, ly, dz);
                if (cur.isAir()) continue;
                if (cur.hasBlockEntity()) chunk.removeBlockEntity(new BlockPos(worldX, y, worldZ));
                section.setBlockState(dx, ly, dz, AIR, false);
                changed = true;
            }
        }
        return changed;
    }

    /** Gather-write one column from its snapshot; true if any block changed. */
    private static boolean rewriteColumn(ChunkAccess chunk, int dx, int dz, int worldX, int worldZ,
                                         BlockState[] col, List<SphereField.Sphere> spheres,
                                         double ramp, boolean core, boolean laneZ, boolean airZ,
                                         int railY, int airYMin, int airYMax, int bedY, long seed,
                                         int minY, int maxY) {
        boolean changed = false;
        for (int y = minY; y < maxY; y++) {
            if (laneZ && (y == bedY || y == railY)) continue;            // the track itself: bed + rails
            BlockState cur = col[y - minY];
            BlockState ns;
            boolean trainAir = airZ && y >= airYMin && y <= airYMax;     // keep the train's airspace open
            SphereField.Sphere owner = trainAir ? null : SphereField.bestAt(spheres, worldX, y, worldZ);
            if (owner != null) {
                int sy = owner.sourceY(y);
                ns = (sy >= minY && sy < maxY) ? lifted(col[sy - minY]) : AIR;
            } else if (core || cur.isAir()) {
                ns = AIR;
            } else {
                // Entry fade: crumble the natural terrain from below, End-erosion style.
                double p = Disintegration.removalProbabilityFromRamp(ramp, y, bedY);
                ns = Disintegration.coherentNoise(seed, worldX, y, worldZ) >= p ? cur : AIR;
            }
            if (ns == cur) continue;
            if (cur.hasBlockEntity()) chunk.removeBlockEntity(new BlockPos(worldX, y, worldZ));
            int sIdx = chunk.getSectionIndex(y);
            chunk.getSection(sIdx).setBlockState(dx, y & 15, dz, ns, false);
            changed = true;
        }
        return changed;
    }

    /**
     * The block a sphere shows for a natural source block: block entities can't travel (→ air),
     * liquids settle as still sources, fallables become their stable equivalent, everything else
     * copies through unchanged.
     */
    private static BlockState lifted(BlockState source) {
        if (source.isAir() || source.hasBlockEntity()) return AIR;
        if (source.getBlock() instanceof LiquidBlock) {
            return source.getFluidState().is(FluidTags.WATER) ? WATER : LAVA;
        }
        BlockState stable = FallingBlockAnchor.stableEquivalent(source);
        return stable != null ? stable : source;
    }
}
