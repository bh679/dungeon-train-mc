package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.GenDeterminismLog;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.MixBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.NetherCoreGeometry;
import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldFloor;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.Set;

/**
 * Stamps the Nether band's real-Nether <b>core</b> terrain into a chunk at the end of the
 * {@code CARVERS} status — netherrack / lava / air sampled from the Nether's density router
 * ({@link #fillNetherColumn}), the per-biome surface skin, and the one-block caps over the sampled slab
 * ({@link #coverCoreCaps}).
 *
 * <p><b>Why here and not in {@link NetherTransitionFeature}.</b> The core is decorated with the real
 * Nether's placed features in the FEATURES step, and those features spill into the 3×3
 * {@code WorldGenRegion} neighbours. When the stamp lived in the same feature, a neighbour had not been
 * stamped yet — it still held overworld stone — so rose quartz, fungi, vines and bone blocks grew against
 * that stone, and the neighbour's own later stamp turned the stone to air, leaving them floating (mostly
 * on chunk borders). FEATURES needs every neighbour at least at CARVERS, so stamping here guarantees every
 * chunk a decoration can reach already holds its Nether terrain. The stamp reads only this chunk and the
 * seed-pure {@link NetherCoreGeometry} density, so it is deterministic at any status. Running after the
 * carvers (not at noise/surface) keeps the core byte-identical to the old FEATURES-time stamp, which
 * also overwrote any caves carved into the band.</p>
 *
 * <p>Everything else in the band — crossfade, shore, the core-facing mountain face, the decoration and
 * the corridor clearance — still runs in {@link NetherTransitionFeature}.</p>
 */
public final class NetherCoreStamp {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Depth of the per-biome surface skin recoloured over exposed core floors. */
    private static final int SURFACE_SKIN_DEPTH = 4;
    /** Salt for the per-biome surface-skin material mix (soul_sand/soul_soil, basalt/blackstone). */
    private static final long NETHER_SURFACE_SKIN_SALT = 0x68E31DA4FB4A7E1FL;
    /** Extra Z clearance on each side of the tunnel wall span. */
    private static final int CORRIDOR_MARGIN = 1;
    /** netherRamp at/above this is the real-Nether core. Shared with the biome-source mixin. */
    private static final double CORE_THRESHOLD = WorldGenCycle.NETHER_CORE_THRESHOLD;

    /** The worldgen heightmaps that exist at CARVERS status; re-primed after the stamp rewrites the column. */
    private static final Set<Heightmap.Types> CARVER_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.WORLD_SURFACE_WG);

    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private NetherCoreStamp() {}

    /**
     * Stamp every real-Nether core column of {@code chunk} (an overworld chunk that has just finished its
     * carvers). No-op outside the band, and never throws — worldgen is never broken by this pass.
     */
    public static void stampChunk(ServerLevel level, ChunkAccess chunk) {
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        long genT0 = GenProfiler.t0();
        try {
            stampInner(level, chunk);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Nether core stamp failed at chunk {}", chunk.getPos(), t);
        } finally {
            GenProfiler.add(GenProfiler.Bucket.NETHER_FEATURE, genT0);
        }
    }

    private static void stampInner(ServerLevel overworld, ChunkAccess chunk) {
        MinecraftServer server = overworld.getServer();
        if (server == null) return;
        ChunkPos cp = chunk.getPos();

        long startX = NetherBand.startX(overworld);
        int chunkMinX = cp.getMinBlockX();
        if (chunkMinX + 15 < startX) return; // before the first band (or disabled)

        WorldGenCycle cycle = MixBand.cycleAt(overworld, cp.x, cp.z);   // mix zone: the chunk's picked band
        // Cheap reject: the core is edge-waved by at most maxEdgeShift, so a chunk with no core X in that
        // expanded span has no core column at all.
        int margin = NetherMountainTerrain.maxEdgeShift();
        boolean anyCore = false;
        for (int x = chunkMinX - margin; x <= chunkMinX + 15 + margin && !anyCore; x++) {
            if (cycle.netherRamp(x) >= CORE_THRESHOLD) anyCore = true;
        }
        if (!anyCore) return;

        boolean endBandActive = DisintegrationBand.startX(overworld) != DisintegrationBand.OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        TunnelGeometry tg = TunnelGeometry.from(g);
        int bedY = g.bedY();
        long seed = data.getGenerationSeed();

        NetherCoreGeometry.Source source = NetherCoreGeometry.Source.resolve(server, bedY);
        NetherCoreGeometry coreGeom = source == null ? null
                : source.open(WorldFloor.bedrockY(overworld), overworld.getMaxBuildHeight() - 1);
        if (coreGeom == null || !coreGeom.isUsable()) return;   // no Nether router: the band has no core

        NetherBandContext bandCtx = NetherBandContext.current();
        int chunkMinZ = cp.getMinBlockZ();
        boolean changed = false;
        int columns = 0;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            for (int dz = 0; dz < 16; dz++) {
                int worldZ = chunkMinZ + dz;
                // Same column gates as NetherTransitionFeature: evaluate the band at the edge-waved X, and the
                // End band always wins any overlap.
                int wx = NetherMountainTerrain.wavyX(seed, worldX, worldZ);
                if (cycle.netherHeightRamp(wx) <= 0.0) continue;
                if (endBandActive && cycle.endMiddleRamp(wx) > 0.0) continue;
                if (cycle.netherRamp(wx) < CORE_THRESHOLD) continue;

                int sampleX = NetherCoreGeometry.sampleX(wx);
                ResourceKey<Biome> coreBiome = coreBiomeKeyAt(bandCtx, worldX, worldZ);
                long coreT0 = GenProfiler.t0();       // real-Nether router sampling — the confirmed DT hotspot
                changed |= fillNetherColumn(chunk, dx, dz, worldX, worldZ, g, tg, sampleX, coreGeom, seed, coreBiome);
                GenProfiler.add(GenProfiler.Bucket.CORE_REPLACE, coreT0);
                changed |= coverCoreCaps(chunk, dx, dz, sampleX, worldZ, coreGeom);
                columns++;
            }
        }

        if (GenDeterminismLog.ENABLED) {
            GenDeterminismLog.log("stamp", "chunk=(%d,%d) columns=%d changed=%b bedY=%d genSeed=%016x",
                    cp.x, cp.z, columns, changed, bedY, seed);
        }
        if (!changed) return;
        Heightmap.primeHeightmaps(chunk, CARVER_HEIGHTMAPS);
        chunk.setUnsaved(true);
    }

    /** The real-Nether biome KEY for a core column (drives the per-biome surface skin); nether_wastes fallback. */
    static ResourceKey<Biome> coreBiomeKeyAt(NetherBandContext bandCtx, int worldX, int worldZ) {
        if (bandCtx == null || bandCtx.netherCoreBiomes() == null) return Biomes.NETHER_WASTES;
        return bandCtx.netherCoreBiomes().biomeAt(worldX, worldZ, bandCtx.cycle().isBetterNetherAt(worldX))
                .unwrapKey().orElse(Biomes.NETHER_WASTES);
    }

    /**
     * REPLACE the column with real Nether terrain sampled (trilinearly, like the End feature) from the
     * Nether dimension's density router — netherrack where the density is solid, lava below the Nether
     * sea, air for caverns. The corridor lane gets the same natural terrain as everywhere else (no forced
     * causeway/envelope): the later {@code track_bed} feature then tunnels through the solid netherrack
     * and rides pillars across the open lava lakes / caverns, exactly as it does over the End band's
     * islands/void.
     */
    private static boolean fillNetherColumn(ChunkAccess chunk, int dx, int dz, int worldX, int worldZ,
                                            TrackGeometry g, TunnelGeometry tg, int sampleX,
                                            NetherCoreGeometry coreGeom, long seed, ResourceKey<Biome> coreBiome) {
        int yLo = coreGeom.minCoreY();
        int yHi = coreGeom.maxCoreY();
        if (yLo > yHi) return false;

        // One column view over the four shared XZ cell corners across the sampled Y rows (world-anchored,
        // like the End) — opened once here rather than per block, so the corner memo is hit four times per
        // column instead of four times per block on the hottest path in Nether generation.
        NetherCoreGeometry.Column density = coreGeom.column(sampleX, worldZ);

        ColumnWriter w = new ColumnWriter(chunk);
        boolean changed = false;
        for (int y = yLo; y <= yHi; y++) {
            if (isTrackBlock(worldZ, y, g, tg)) continue;
            double d = density.densityAt(y);
            if (Double.isNaN(d)) continue;   // outside the sampled cell rows — leave the block untouched
            BlockState target;
            if (d > 0.0) {
                target = NETHERRACK;
            } else if (coreGeom.isLavaLevel(y)) {
                target = Blocks.LAVA.defaultBlockState();
            } else {
                target = AIR;
            }
            if (w.isSame(dx, y, dz, target)) continue;
            w.set(dx, y, dz, target);
            changed = true;
        }

        // Per-biome surface skin: recolour exposed netherrack floors to the biome's surface material
        // (nylium / soul_sand-soil / basalt-blackstone). nether_wastes keeps plain netherrack. The whole
        // corridor lane is skipped so the train tunnel stays clean netherrack. Every upward-facing
        // netherrack surface in the column is skinned (like real Nether nylium); cells under lava/with no
        // air above are left as netherrack.
        if (NetherSurfacePalette.hasSurface(coreBiome) && !inCorridorLane(worldZ, tg)) {
            boolean airAbove = true;                 // the sampled band is open above yHi
            int depth = SURFACE_SKIN_DEPTH;          // >= depth ⇒ not currently skinning a floor
            for (int y = yHi; y >= yLo; y--) {
                boolean air = w.isAir(dx, y, dz);
                boolean nr = !air && w.isSame(dx, y, dz, NETHERRACK);
                if (nr && airAbove) {
                    depth = 0;                       // top of an exposed floor
                } else if (!nr) {
                    depth = SURFACE_SKIN_DEPTH;      // air/lava/other ends the skin run
                }
                if (nr && depth < SURFACE_SKIN_DEPTH) {
                    double noise = Disintegration.coherentNoise(seed ^ NETHER_SURFACE_SKIN_SALT, worldX, y, worldZ);
                    BlockState surf = NetherSurfacePalette.surfaceBlock(coreBiome, depth, noise);
                    if (!w.isSame(dx, y, dz, surf)) { w.set(dx, y, dz, surf); changed = true; }
                    depth++;
                }
                airAbove = air;
            }
        }
        return changed;
    }

    /**
     * The core restamps only its sampled Y band, so where the band's top or bottom row is open the
     * overworld rock just beyond it shows through as a stone roof/floor. Repaint that single capping
     * block netherrack; the mountain further up/down stays as it is.
     */
    private static boolean coverCoreCaps(ChunkAccess chunk, int dx, int dz, int sampleX, int worldZ,
                                         NetherCoreGeometry coreGeom) {
        NetherCoreGeometry.Column col = coreGeom.column(sampleX, worldZ);
        ColumnWriter w = new ColumnWriter(chunk);
        boolean changed = false;
        int[][] caps = {{coreGeom.maxCoreY(), coreGeom.maxCoreY() + 1}, {coreGeom.minCoreY(), coreGeom.minCoreY() - 1}};
        for (int[] cap : caps) {
            if (col.isSolid(cap[0])) continue;                       // band edge is rock — nothing shows through
            if (!NetherRockCover.isOverworldRock(w.state(dx, cap[1], dz))) continue;
            w.set(dx, cap[1], dz, NETHERRACK);
            changed = true;
        }
        return changed;
    }

    /** True for the stone-brick bed and the two rail blocks — never overwritten so the train keeps its track. */
    static boolean isTrackBlock(int worldZ, int y, TrackGeometry g, TunnelGeometry tg) {
        if (y == g.bedY() && worldZ >= g.trackZMin() && worldZ <= g.trackZMax()) return true;
        return y == g.railY() && (worldZ == tg.railZMin() || worldZ == tg.railZMax());
    }

    /** The train's Z corridor (tunnel wall span + margin) that track_bed will carve. */
    static boolean inCorridorLane(int worldZ, TunnelGeometry tg) {
        return worldZ >= tg.wallMinZ() - CORRIDOR_MARGIN && worldZ <= tg.wallMaxZ() + CORRIDOR_MARGIN;
    }
}
