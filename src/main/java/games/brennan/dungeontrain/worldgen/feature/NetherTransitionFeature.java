package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.Set;

/**
 * Worldgen feature for the <b>Nether transition band</b> (overworld, runs at
 * {@code top_layer_modification} after {@code track_bed}). Along +X it stamps a
 * world-height mountain the train tunnels through, crossfades it to netherrack, and at
 * the core replaces the column with <em>real</em> Nether terrain sampled from the
 * Nether dimension's density router — mirroring how {@link DisintegrationFeature}
 * samples the real End. The {@link games.brennan.dungeontrain.worldgen.NetherBand}
 * cycle drives two ramps: {@link NetherTransition#heightRamp} (mountain height) and
 * {@link NetherTransition#netherRamp} (netherrack → real Nether intensity).
 *
 * <p>Where the {@link DisintegrationBand} End band is active the End wins — those
 * columns are skipped, so the two bands never double-stamp.</p>
 *
 * <p>All terrain writes go through the raw section path (the Sable-safe pattern, see
 * {@link DisintegrationFeature#place}); the train's corridor (the tunnel airspace) is
 * kept clear so the mountain forms walls + roof around it.</p>
 */
public class NetherTransitionFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Nether-space Y mapped onto the track bed (the open Nether layer sits around here). */
    private static final int NETHER_CENTER_Y = 40;
    /** Nether-space Y rows to sample for the core terrain. */
    private static final int NETHER_SAMPLE_Y_MIN = 8;
    private static final int NETHER_SAMPLE_Y_MAX = 120;
    /** X offset into the Nether so successive bands sample different (but continuous) terrain. */
    private static final int NETHER_SAMPLE_OFFSET_X = 12_000;

    /** Mega-mountain peaks above this world-Y get a snow cap (jagged-peak look). */
    private static final int SNOW_LINE_Y = 200;
    /** Blocks of clear airspace kept above the bed for the train to pass through. */
    private static final int TUNNEL_CLEAR_HEIGHT = 14;
    /** Mountains taller than this fraction of max height roof the corridor (a real tunnel); shorter ones leave it open (a canyon, so the player SEES the peaks rise). */
    private static final double MEGA_ROOF_FRACTION = 0.8;
    /** Extra Z clearance on each side of the tunnel wall span. */
    private static final int CORRIDOR_MARGIN = 1;
    /** Guaranteed solid causeway depth below the bed in the Nether core (a netherrack bridge over lava). */
    private static final int CORE_CAUSEWAY_DEPTH = 3;
    /** netherRamp at/above this is the real-Nether core (REPLACE); below it is the netherrack crossfade. */
    private static final double CORE_THRESHOLD = 0.999;

    private static final Set<Heightmap.Types> WG_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.WORLD_SURFACE_WG);

    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public NetherTransitionFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        try {
            WorldGenLevel level = ctx.level();
            ChunkPos cp = new ChunkPos(ctx.origin());
            ServerLevel serverLevel = level.getLevel();
            MinecraftServer server = serverLevel.getServer();
            if (server == null) return false;
            ServerLevel overworld = server.overworld();
            if (overworld == null) return false;

            long startX = NetherBand.startX(overworld);
            int chunkMinX = cp.getMinBlockX();
            if (chunkMinX + 15 < startX) return false; // before the first band (or disabled)

            int maxHeight = DungeonTrainCommonConfig.getNetherMaxHeight();
            int baseHeight = DungeonTrainCommonConfig.getNetherMountainBaseHeight();
            int normalHold = DungeonTrainCommonConfig.getNetherNormalHoldBlocks();
            WorldGenCycle cycle = WorldGenCycle.fromConfig();

            double[] heightRamp = new double[16];
            double[] netherRamp = new double[16];
            boolean anyHeight = false;
            for (int dx = 0; dx < 16; dx++) {
                int worldX = chunkMinX + dx;
                heightRamp[dx] = cycle.netherHeightRamp(worldX);
                netherRamp[dx] = cycle.netherRamp(worldX);
                if (heightRamp[dx] > 0.0) anyHeight = true;
            }
            if (!anyHeight) return false;

            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
            CarriageDims dims = data.dims();
            TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
            TunnelGeometry tg = TunnelGeometry.from(g);
            int bedY = g.bedY();
            int railY = g.railY();
            int zMin = g.trackZMin();
            int zMax = g.trackZMax();
            long seed = data.getGenerationSeed();
            MountainPalette palette = MountainPalette.fromSeed(seed);

            int minY = level.getMinBuildHeight();
            int worldTop = level.getMaxBuildHeight() - 1;
            int netherTopApprox = bedY + (NETHER_SAMPLE_Y_MAX - NETHER_CENTER_Y);

            ChunkAccess chunk = level.getChunk(cp.x, cp.z);
            int chunkMinZ = cp.getMinBlockZ();

            // Real-Nether density router (sampled like the End feature samples the End).
            DensityFunction netherDensity = null;
            int cellW = 4;
            int cellH = 8;
            int netherSeaLevel = 32;
            ServerLevel nether = server.getLevel(Level.NETHER);
            if (nether != null) {
                netherDensity = nether.getChunkSource().randomState().router().finalDensity();
                if (nether.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator nbg) {
                    NoiseGeneratorSettings gs = nbg.generatorSettings().value();
                    NoiseSettings ns = gs.noiseSettings();
                    cellW = ns.getCellWidth();
                    cellH = ns.getCellHeight();
                    netherSeaLevel = gs.seaLevel();
                }
            }

            boolean changed = false;
            for (int dx = 0; dx < 16; dx++) {
                if (heightRamp[dx] <= 0.0) continue;
                int worldX = chunkMinX + dx;
                // Precedence: the End band always wins — skip columns it owns.
                if (DisintegrationBand.middleRampAt(overworld, worldX) > 0.0) continue;

                double n = netherRamp[dx];
                int worldMountainTop = cycle.netherMountainTopY(worldX, bedY, maxHeight, baseHeight, normalHold, worldTop);
                // Mountain tapers down toward the open-Nether height as the core approaches.
                int columnTop = (int) Math.round(worldMountainTop * (1.0 - n) + netherTopApprox * n);
                boolean core = n >= CORE_THRESHOLD && netherDensity != null;
                // Roof the corridor (a tunnel) only for the mega-mountain / nether approach; leave the
                // lower approach peaks open to the sky (a canyon) so the rising mountains are visible.
                boolean roof = n > 0.0 || columnTop >= bedY + (int) Math.round(MEGA_ROOF_FRACTION * maxHeight);
                int sampleX = worldX + NETHER_SAMPLE_OFFSET_X;

                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    boolean colChanged;
                    if (core) {
                        colChanged = fillNetherColumn(chunk, dx, dz, worldX, worldZ, bedY, railY, zMin, zMax, tg,
                                minY, worldTop, sampleX, netherDensity, cellW, cellH, netherSeaLevel);
                    } else {
                        colChanged = fillMountainColumn(chunk, dx, dz, worldX, worldZ, bedY, railY, zMin, zMax, tg,
                                minY, columnTop, n, seed, palette, roof);
                    }
                    changed |= colChanged;
                }
            }

            if (!changed) return false;
            Heightmap.primeHeightmaps(chunk, WG_HEIGHTMAPS);
            chunk.setUnsaved(true);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] NetherTransitionFeature.place failed at chunk {}", ctx.origin(), t);
            return false;
        }
    }

    /**
     * Stages 1–4: raise a mountain by ADDING material into air from the natural surface
     * up to {@code columnTop}, leaving the tunnel corridor clear. {@code n==0} is plain
     * rock (snow-capped on tall peaks); {@code 0<n<1} dithers stone↔netherrack by the
     * nether ramp so the mountain visibly turns to netherrack.
     */
    private boolean fillMountainColumn(ChunkAccess chunk, int dx, int dz, int worldX, int worldZ,
                                       int bedY, int railY, int zMin, int zMax, TunnelGeometry tg,
                                       int minY, int columnTop, double n, long seed, MountainPalette palette,
                                       boolean roof) {
        int base = Math.max(minY, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz));
        if (base > columnTop) return false;
        ColumnWriter w = new ColumnWriter(chunk);
        boolean changed = false;
        for (int y = base; y <= columnTop; y++) {
            if (isTrackBlock(worldZ, y, bedY, railY, zMin, zMax, tg)) continue;
            if (inCorridorLane(worldZ, tg)) {
                if (y > bedY && y <= bedY + TUNNEL_CLEAR_HEIGHT) continue; // train clearance — always open
                if (!roof && y > bedY + TUNNEL_CLEAR_HEIGHT) continue;     // open canyon over the lower peaks
            }
            // ADD into air; also bury any tree/foliage so the mountain isn't pierced by stray trees.
            if (!w.isReplaceable(dx, y, dz)) continue;
            w.set(dx, y, dz, mountainMaterial(palette, y, columnTop, n, seed, worldX, worldZ));
            changed = true;
        }
        return changed;
    }

    private static BlockState mountainMaterial(MountainPalette palette, int y, int columnTop, double n,
                                               long seed, int worldX, int worldZ) {
        double accent = Disintegration.coherentNoise(seed, worldX, y, worldZ);
        BlockState rock = palette.surfaceBlock(columnTop - y, columnTop > SNOW_LINE_Y, accent);
        if (n <= 0.0) return rock;                 // stages 1–3: the chosen vanilla mountain look
        // stage 4: clumpy crossfade of the mountain rock → netherrack, rising with n.
        double dither = Disintegration.coherentNoise(seed ^ 0x9E3779B97F4A7C15L, worldX, y, worldZ);
        return dither < n ? NETHERRACK : rock;
    }

    /**
     * Stage 5: REPLACE the column with real Nether terrain sampled (trilinearly, like the
     * End feature) from the Nether dimension's density router — netherrack where the
     * density is solid, lava below the Nether sea, air for caverns. A guaranteed
     * netherrack causeway carries the track over the lava, and the tunnel stays clear.
     */
    private boolean fillNetherColumn(ChunkAccess chunk, int dx, int dz, int worldX, int worldZ,
                                     int bedY, int railY, int zMin, int zMax, TunnelGeometry tg,
                                     int minY, int worldTop, int sampleX, DensityFunction netherDensity,
                                     int cellW, int cellH, int netherSeaLevel) {
        int yLo = Math.max(minY, bedY + (NETHER_SAMPLE_Y_MIN - NETHER_CENTER_Y));
        int yHi = Math.min(worldTop, bedY + (NETHER_SAMPLE_Y_MAX - NETHER_CENTER_Y));
        if (yLo > yHi) return false;

        // Density at the four XZ cell corners across the sampled Y rows (world-anchored, like the End).
        int x0 = Math.floorDiv(sampleX, cellW) * cellW;
        int x1 = x0 + cellW;
        double fx = (double) (sampleX - x0) / cellW;
        int z0 = Math.floorDiv(worldZ, cellW) * cellW;
        int z1 = z0 + cellW;
        double fz = (double) (worldZ - z0) / cellW;
        int netherYLo = NETHER_CENTER_Y + (yLo - bedY);
        int cellRowBase = Math.floorDiv(netherYLo, cellH);
        int rows = Math.floorDiv(NETHER_CENTER_Y + (yHi - bedY), cellH) - cellRowBase + 2;
        double[] c00 = new double[rows];
        double[] c10 = new double[rows];
        double[] c01 = new double[rows];
        double[] c11 = new double[rows];
        for (int r = 0; r < rows; r++) {
            int by = (cellRowBase + r) * cellH;
            c00[r] = netherDensity.compute(new DensityFunction.SinglePointContext(x0, by, z0));
            c10[r] = netherDensity.compute(new DensityFunction.SinglePointContext(x1, by, z0));
            c01[r] = netherDensity.compute(new DensityFunction.SinglePointContext(x0, by, z1));
            c11[r] = netherDensity.compute(new DensityFunction.SinglePointContext(x1, by, z1));
        }

        ColumnWriter w = new ColumnWriter(chunk);
        boolean changed = false;
        for (int y = yLo; y <= yHi; y++) {
            if (isTrackBlock(worldZ, y, bedY, railY, zMin, zMax, tg)) continue;
            if (inCorridorClearZone(worldZ, y, bedY, tg)) {
                if (!w.isAir(dx, y, dz)) { w.set(dx, y, dz, AIR); changed = true; } // carve the tunnel clear
                continue;
            }
            // Guaranteed netherrack causeway just under the track so the train rides over the lava.
            boolean underTrack = worldZ >= tg.wallMinZ() - CORRIDOR_MARGIN && worldZ <= tg.wallMaxZ() + CORRIDOR_MARGIN
                    && y >= bedY - CORE_CAUSEWAY_DEPTH && y <= bedY;
            BlockState target;
            if (underTrack) {
                target = NETHERRACK;
            } else {
                int netherY = NETHER_CENTER_Y + (y - bedY);
                int r = Math.floorDiv(netherY, cellH) - cellRowBase;
                if (r < 0 || r + 1 >= rows) continue;
                double fy = (double) (netherY - (cellRowBase + r) * cellH) / cellH;
                double bot = bilerp(c00[r], c10[r], c01[r], c11[r], fx, fz);
                double top = bilerp(c00[r + 1], c10[r + 1], c01[r + 1], c11[r + 1], fx, fz);
                double d = bot + (top - bot) * fy;
                if (d > 0.0) {
                    target = NETHERRACK;
                } else if (netherY < netherSeaLevel) {
                    target = Blocks.LAVA.defaultBlockState();
                } else {
                    target = AIR;
                }
            }
            if (w.isSame(dx, y, dz, target)) continue;
            w.set(dx, y, dz, target);
            changed = true;
        }
        return changed;
    }

    /** True for the stone-brick bed and the two rail blocks — never overwritten so the train keeps its track. */
    private static boolean isTrackBlock(int worldZ, int y, int bedY, int railY, int zMin, int zMax, TunnelGeometry tg) {
        if (y == bedY && worldZ >= zMin && worldZ <= zMax) return true;
        return y == railY && (worldZ == tg.railZMin() || worldZ == tg.railZMax());
    }

    /** The train's Z corridor (tunnel wall span + margin) — kept clear so the train always has a path. */
    private static boolean inCorridorLane(int worldZ, TunnelGeometry tg) {
        return worldZ >= tg.wallMinZ() - CORRIDOR_MARGIN && worldZ <= tg.wallMaxZ() + CORRIDOR_MARGIN;
    }

    /** The clear tube the train rides through: the corridor lane, from just above the bed to the clear height. */
    private static boolean inCorridorClearZone(int worldZ, int y, int bedY, TunnelGeometry tg) {
        return inCorridorLane(worldZ, tg) && y > bedY && y <= bedY + TUNNEL_CLEAR_HEIGHT;
    }

    /**
     * Surface foliage that the mountain may bury — leaves, logs, vines, saplings,
     * flowers. Deliberately excludes fluids and other replaceables (catching fluids
     * would cascade neighbour updates), mirroring {@code CorridorCleanupEvents.isFoliage}.
     */
    public static boolean isStrippableFoliage(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(Blocks.VINE)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS);
    }

    private static double bilerp(double x0z0, double x1z0, double x0z1, double x1z1, double fx, double fz) {
        double z0 = x0z0 + (x1z0 - x0z0) * fx;
        double z1 = x0z1 + (x1z1 - x0z1) * fx;
        return z0 + (z1 - z0) * fz;
    }

    /**
     * Section-cached raw writer for one chunk column — fetches the owning
     * {@link net.minecraft.world.level.chunk.LevelChunkSection} only when crossing a
     * section boundary, dropping orphaned block entities before overwriting (the
     * Sable-safe path; see {@link DisintegrationFeature}).
     */
    private static final class ColumnWriter {
        private final ChunkAccess chunk;
        private int curIdx = -1;
        private net.minecraft.world.level.chunk.LevelChunkSection section;
        private int baseY;

        ColumnWriter(ChunkAccess chunk) {
            this.chunk = chunk;
        }

        private boolean ensure(int y) {
            int idx = chunk.getSectionIndex(y);
            if (idx < 0 || idx >= chunk.getSectionsCount()) return false;
            if (idx != curIdx) {
                curIdx = idx;
                section = chunk.getSection(idx);
                baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(idx));
            }
            return true;
        }

        boolean isAir(int dx, int y, int dz) {
            if (!ensure(y)) return false;
            return section.getBlockState(dx, y - baseY, dz).isAir();
        }

        /** Air or surface foliage — the cells the mountain fill may take over. */
        boolean isReplaceable(int dx, int y, int dz) {
            if (!ensure(y)) return false;
            BlockState cur = section.getBlockState(dx, y - baseY, dz);
            return cur.isAir() || isStrippableFoliage(cur);
        }

        boolean isSame(int dx, int y, int dz, BlockState state) {
            if (!ensure(y)) return false;
            return section.getBlockState(dx, y - baseY, dz) == state;
        }

        void set(int dx, int y, int dz, BlockState state) {
            if (!ensure(y)) return;
            int ly = y - baseY;
            if (section.getBlockState(dx, ly, dz).hasBlockEntity()) {
                chunk.removeBlockEntity(new BlockPos(chunk.getPos().getMinBlockX() + dx, y, chunk.getPos().getMinBlockZ() + dz));
            }
            section.setBlockState(dx, ly, dz, state, false);
        }
    }
}
