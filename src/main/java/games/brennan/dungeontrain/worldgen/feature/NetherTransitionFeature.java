package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
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
 * {@code top_layer_modification} after {@code track_bed}). The band's mountain BODY is no longer
 * stamped here — it is raised directly in the chunk generator's density router (see
 * {@code worldgen.density.NetherBandTerrainDensityFunction}), so vanilla surface rules
 * (grass/dirt/snow), trees, and structures land on it naturally. This post-process feature now only
 * does the two things that <em>cannot</em> be plain terrain noise:
 * <ul>
 *   <li>the netherrack <b>crossfade</b> — dithering the (real, noise-built) mountain surface to
 *       netherrack as the real-Nether core approaches ({@link NetherTransition#netherRamp});</li>
 *   <li>the real-Nether <b>core</b> — replacing the column with terrain sampled from the Nether
 *       dimension's density router, mirroring how {@link DisintegrationFeature} samples the real
 *       End; and the ocean-entry <b>shore</b> ({@link #fillShoreColumn}).</li>
 * </ul>
 * Pure mountain-stage columns ({@code netherRamp == 0}) are left untouched — the noise built them.
 *
 * <p>Where the {@link DisintegrationBand} End band is active the End wins — those columns are
 * skipped (the density wrapper also yields there), so the two bands never double-stamp.</p>
 *
 * <p>All terrain writes go through the raw section path (the Sable-safe pattern, see
 * {@link DisintegrationFeature#place}).</p>
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

    /** Nether-core corridor envelope height above the bed (kept solid netherrack so track_bed tunnels it). */
    private static final int TUNNEL_CLEAR_HEIGHT = 14;
    /** Depth of the surface skin recoloured to netherrack across the crossfade. */
    private static final int SURFACE_SKIN_DEPTH = 4;
    /** Salt for the crossfade rock→netherrack dither (matches the old mountainMaterial dither). */
    private static final long CROSSFADE_DITHER_SALT = 0x9E3779B97F4A7C15L;
    /** Extra Z clearance on each side of the tunnel wall span. */
    private static final int CORRIDOR_MARGIN = 1;
    /** Guaranteed solid causeway depth below the bed in the Nether core (a netherrack bridge over lava). */
    private static final int CORE_CAUSEWAY_DEPTH = 3;
    /** netherRamp at/above this is the real-Nether core (REPLACE); below it is the netherrack crossfade. */
    private static final double CORE_THRESHOLD = 0.999;

    // --- Shore (ocean-entry beach): a gentle natural ramp from the ocean floor up to the mountains. ---
    /** Minimum solid-sand body depth below the shore surface (extends down to the natural seabed when deeper). */
    private static final int SHORE_BODY_DEPTH = 8;
    /** Upward dune noise (blocks): broad swell over the base ~96-block feature size. */
    private static final double SHORE_NOISE_BROAD = 2.0;
    /** Upward ripple noise (blocks): finer ~16-block features that break up the ramp's flat terraces. */
    private static final double SHORE_NOISE_FINE = 2.5;
    /** Frequency multiplier for the fine ripple octave (≈96/6 = 16-block features). */
    private static final double SHORE_NOISE_FINE_SCALE = 6.0;
    /** Salts for the two shore-dune octaves (distinct from the mountain + shore-skin salts). */
    private static final long SHORE_NOISE_SALT = 0x5EA5A11DBEA1L;
    private static final long SHORE_NOISE_FINE_SALT = 0x27D4EB2F165667C5L;
    /** Blocks above sea level the beach sand climbs onto the feathered mountain over an ocean entrance. */
    private static final int SHORE_SKIN_BAND = 6;
    /** Salt for the shore-skin sand→grass dither (distinct from the crossfade + jitter salts). */
    private static final long SHORE_SKIN_SALT = 0xC2B2AE3D27D4EB4FL;

    private static final Set<Heightmap.Types> WG_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.WORLD_SURFACE_WG);

    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

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

            WorldGenCycle cycle = WorldGenCycle.fromConfig();
            int seaLevel = overworld.getSeaLevel();

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

            // Is THIS band's entrance over ocean? (Computed once per chunk from the natural surface at the band
            // start.) If so, the leading beach span is rendered as a gentle sand shore — a ramp out of the
            // shallows up to the mountains — instead of staying natural overworld.
            boolean oceanEntrance = isOceanEntrance(overworld, cycle.netherBandEntranceX(chunkMinX + 8),
                    g.trackCenterZ(), seaLevel);

            int minY = level.getMinBuildHeight();
            int worldTop = level.getMaxBuildHeight() - 1;

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
                boolean core = n >= CORE_THRESHOLD && netherDensity != null;
                boolean inBeachSpan = !core && cycle.isNetherBeachStage(worldX);
                // The beach stage exists ONLY where the band emerges from an ocean biome; otherwise it is
                // SKIPPED — those columns stay natural overworld and the noise mountains pick up after the span.
                if (inBeachSpan && !oceanEntrance) continue;
                // Pure mountain-stage columns (n == 0, not the beach) are now built entirely by the terrain-noise
                // density wrapper — nothing to post-process here, so skip them (this is what lets grass/trees/
                // structures survive on the mountain instead of being re-buried by a rock stamp).
                boolean crossfade = !core && !inBeachSpan && n > 0.0;
                // Over an ocean entrance the feathered mountain rises out of sea level across the entry/exit
                // fade, leaving a stony shoreline; recolour that low surface to beach sand. Only the fade
                // zone (feather < 1) can be near sea level — the high interior + all land bands are skipped.
                boolean shoreSkin = !core && !inBeachSpan && !crossfade
                        && oceanEntrance && cycle.netherMountainFeather(worldX) < 1.0;
                if (!core && !inBeachSpan && !crossfade && !shoreSkin) continue;
                double beachProgress = inBeachSpan ? cycle.netherBeachProgress(worldX) : 0.0;
                int sampleX = worldX + NETHER_SAMPLE_OFFSET_X;

                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    boolean colChanged;
                    if (core) {
                        colChanged = fillNetherColumn(chunk, dx, dz, worldX, worldZ, bedY, railY, zMin, zMax, tg,
                                minY, worldTop, sampleX, netherDensity, cellW, cellH, netherSeaLevel);
                    } else if (inBeachSpan) {
                        colChanged = fillShoreColumn(chunk, dx, dz, worldX, worldZ, bedY, railY, zMin, zMax, tg,
                                minY, worldTop, seaLevel, seed, beachProgress);
                    } else if (crossfade) {
                        colChanged = recolorCrossfadeColumn(chunk, dx, dz, worldX, worldZ, minY, worldTop, n, seed);
                    } else {
                        colChanged = recolorShoreSkinColumn(chunk, dx, dz, worldX, worldZ, bedY, railY, zMin, zMax, tg,
                                minY, worldTop, seaLevel, seed);
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
     * Crossfade ({@code 0 < n < 1}): the mountain body is already real terrain (built by the density
     * wrapper) with vanilla grass/dirt/stone on top, so this only DITHERS the existing surface skin to
     * netherrack as the real-Nether core approaches — turning the green mountain increasingly red over
     * the {@code coreFade} span. It reads the actual chunk heightmap top (which already reflects the
     * raised terrain at {@code top_layer_modification}) and recolours the top {@link #SURFACE_SKIN_DEPTH}
     * solid blocks where the coherent dither falls under {@code n}; everything below stays natural stone,
     * and no terrain is added or removed. No {@code MountainNoise}/palette recompute is needed.
     */
    private boolean recolorCrossfadeColumn(ChunkAccess chunk, int dx, int dz, int worldX, int worldZ,
                                           int minY, int worldTop, double n, long seed) {
        int top = Math.max(minY, Math.min(worldTop, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz)));
        ColumnWriter w = new ColumnWriter(chunk);
        boolean changed = false;
        int floor = Math.max(minY, top - SURFACE_SKIN_DEPTH + 1);
        for (int y = top; y >= floor; y--) {
            double dither = Disintegration.coherentNoise(seed ^ CROSSFADE_DITHER_SALT, worldX, y, worldZ);
            if (dither >= n) continue;                 // keep the natural mountain surface in this cell
            if (!w.isSolidGround(dx, y, dz)) continue; // only recolour existing solid ground (never air/fluid)
            w.set(dx, y, dz, NETHERRACK);
            changed = true;
        }
        return changed;
    }

    /** Upward beach-dune height (blocks) at a column: broad swell + finer ripples, the SAME coherent field
     *  the shore ramp and the shore skin both add to their base, so the two stay continuous at the seam. */
    private static double shoreDuneHeight(long seed, int worldX, int worldZ) {
        double broad = MountainNoise.height01(seed ^ SHORE_NOISE_SALT, worldX, worldZ);                 // [0,1]
        double fine = MountainNoise.height01(seed ^ SHORE_NOISE_FINE_SALT,
                worldX * SHORE_NOISE_FINE_SCALE, worldZ * SHORE_NOISE_FINE_SCALE);                       // [0,1]
        return broad * SHORE_NOISE_BROAD + fine * SHORE_NOISE_FINE;                                      // [0, BROAD+FINE]
    }

    /**
     * Shore skin ({@code n == 0}, ocean entrance, in the entry/exit fade): the feathered mountains rise
     * out of sea level over former ocean, and vanilla paints that near-waterline surface as a stony/gravel
     * shore in the forced highland biome — a STONE BAND between the sand beach and the green mountain. This
     * recolours the near-sea-level surface to {@link BeachPalette} sand (within {@link #SHORE_SKIN_BAND}
     * blocks of sea level, with a coherent dither for an irregular sand→grass line) AND adds the same
     * upward {@link #shoreDuneHeight} dunes the shore ramp uses, so the low mountain isn't flat terraces and
     * stays continuous with the beach. Upward-only (never digs), skips the train lane, leaves grass above
     * the band — geometry elsewhere is untouched.
     */
    private boolean recolorShoreSkinColumn(ChunkAccess chunk, int dx, int dz, int worldX, int worldZ,
                                           int bedY, int railY, int zMin, int zMax, TunnelGeometry tg,
                                           int minY, int worldTop, int seaLevel, long seed) {
        int top = Math.max(minY, Math.min(worldTop, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz)));
        if (top > seaLevel + SHORE_SKIN_BAND) return false;          // a normal grassy mountain up here
        // 1 at/below sea level, fading to 0 at the top of the band → an irregular, natural sand line.
        double sandiness = (double) (seaLevel + SHORE_SKIN_BAND - top) / SHORE_SKIN_BAND;
        if (MountainNoise.height01(seed ^ SHORE_SKIN_SALT, worldX, worldZ) >= sandiness) return false;
        ColumnWriter w = new ColumnWriter(chunk);
        boolean changed = false;
        // Pile up sand dunes (same coherent field as the shore ramp) to break the flat low-relief terraces.
        int duneTop = Math.min(worldTop, top + (int) Math.round(shoreDuneHeight(seed, worldX, worldZ)));
        for (int y = top + 1; y <= duneTop; y++) {
            if (isTrackBlock(worldZ, y, bedY, railY, zMin, zMax, tg)) continue;
            if (y > bedY && inCorridorLane(worldZ, tg)) continue;     // keep the train lane clear
            if (!w.isAir(dx, y, dz)) continue;                        // only build into open air above the surface
            w.set(dx, y, dz, BeachPalette.surfaceBlock(duneTop - y, Disintegration.coherentNoise(seed, worldX, y, worldZ)));
            changed = true;
        }
        // Recolour the surface skin (dune + the original top) to sand so nothing stony shows through.
        int floor = Math.max(minY, top - SURFACE_SKIN_DEPTH + 1);
        for (int y = duneTop; y >= floor; y--) {
            if (isTrackBlock(worldZ, y, bedY, railY, zMin, zMax, tg)) continue;
            if (!w.isSolidGround(dx, y, dz)) continue;               // only recolour solid ground, never air/water
            BlockState block = BeachPalette.surfaceBlock(duneTop - y, Disintegration.coherentNoise(seed, worldX, y, worldZ));
            if (!w.isSame(dx, y, dz, block)) { w.set(dx, y, dz, block); changed = true; }
        }
        return changed;
    }

    /**
     * Leading <b>shore</b> column where the band emerges from an ocean biome: a gentle sand ramp that
     * climbs smoothly from the natural ocean floor at the seaward edge ({@code progress 0}, submerged)
     * up to sea level at the inland edge ({@code progress 1}) — a natural beach to the waterline. The
     * noise mountains grow inland from sea level (feathered to 0 at the band gate by
     * {@link WorldGenCycle#netherMountainFeather}), so the shore meets BOTH the seabed and the mountains
     * with no step/notch. Shaped by low-amplitude value noise (a couple of blocks of jitter), NOT the
     * dramatic ridged mountain heightmap. Below the sand
     * surface is solid {@link BeachPalette} (sand → sandstone → stone, extended down to the seabed);
     * where the surface sits below sea level, shallows water fills from the sand up to sea level — so
     * the column reads submerged sand → waterline → dry sand → mountains. Seabed poking above the ramp
     * is cleared to water/air so the ramp is the true surface.
     *
     * <p>{@code progress} is {@link WorldGenCycle#netherBeachProgress} (0 seaward → 1 inland). Raw,
     * Sable-safe section writes via {@link ColumnWriter}; the track + (low-mound) corridor lane stay
     * clear so the train still rides through — by default the whole shore sits well below the bed.</p>
     */
    private boolean fillShoreColumn(ChunkAccess chunk, int dx, int dz, int worldX, int worldZ,
                                    int bedY, int railY, int zMin, int zMax, TunnelGeometry tg,
                                    int minY, int worldTop, int seaLevel, long seed, double progress) {
        int surfaceY = Math.max(minY, Math.min(worldTop, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz)));
        // Ramp from the natural ocean floor (progress 0 → submerged) up to the inland edge (progress 1),
        // where it hands off to the feathered mountains. The feather is 0 at the band gate, so the density
        // mountain's first solid block there lands at seaLevel-1 (the raise crosses 0 at seaLevel); the
        // ramp's base targets exactly that so the seam is flush.
        int mountainTop = seaLevel - 1;
        // Upward two-octave coherent dunes (broad swell + finer ripples) so the sub-sea ramp + waterline
        // read as a natural, wavy coastline rather than flat terraces along Z. Upward-only (added to the
        // ramp base) so the surface never dips below its base — no dry pits — and the SAME dune field is
        // added to the feathered low mountain in recolorShoreSkinColumn, so beach and mountain stay
        // continuous across the seam (both = their base + the same dune at the join).
        int columnTop = (int) Math.round(surfaceY + (mountainTop - surfaceY) * progress
                + shoreDuneHeight(seed, worldX, worldZ));
        columnTop = Math.max(minY + 1, Math.min(worldTop, columnTop));
        // A shore is low, so it never qualifies as a tunnel — its corridor lane stays open for the train.
        boolean lanesTunnel = columnTop >= bedY + TUNNEL_CLEAR_HEIGHT;

        ColumnWriter w = new ColumnWriter(chunk);
        boolean changed = false;

        // Above the sand surface: shallows water up to sea level, air above it — clearing any natural
        // seabed that pokes above the ramp so the shore reads cleanly.
        int top = Math.min(worldTop, Math.max(seaLevel, surfaceY));
        for (int y = top; y > columnTop; y--) {
            if (isTrackBlock(worldZ, y, bedY, railY, zMin, zMax, tg)) continue;
            if (!lanesTunnel && y > bedY && inCorridorLane(worldZ, tg)) {
                if (!w.isAir(dx, y, dz)) { w.set(dx, y, dz, AIR); changed = true; } // keep the train lane clear
                continue;
            }
            // Vanilla ocean water tops out at seaLevel-1 (y=seaLevel is the first air); match it so the
            // shallows sit flush with the surrounding sea rather than one block proud.
            BlockState want = y < seaLevel ? WATER : AIR;
            if (!w.isSame(dx, y, dz, want)) { w.set(dx, y, dz, want); changed = true; }
        }

        // Solid sand body below the surface: sand → sandstone → stone with depth, extended down to the seabed.
        int floor = Math.max(minY, Math.min(columnTop - SHORE_BODY_DEPTH, surfaceY));
        for (int y = columnTop; y >= floor; y--) {
            if (isTrackBlock(worldZ, y, bedY, railY, zMin, zMax, tg)) continue;
            if (!lanesTunnel && y > bedY && inCorridorLane(worldZ, tg)) continue;
            BlockState block = BeachPalette.surfaceBlock(columnTop - y, Disintegration.coherentNoise(seed, worldX, y, worldZ));
            if (!w.isSame(dx, y, dz, block)) { w.set(dx, y, dz, block); changed = true; }
        }
        return changed;
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
            // Keep the whole corridor envelope solid netherrack (causeway under the bed + walls/roof through
            // the tunnel height) so the later track_bed feature reliably carves a clean, walled tunnel through
            // the nether — never leaving the train exposed over a lava cavern.
            boolean envelope = inCorridorLane(worldZ, tg)
                    && y >= bedY - CORE_CAUSEWAY_DEPTH && y <= bedY + TUNNEL_CLEAR_HEIGHT;
            BlockState target;
            if (envelope) {
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

    /** The train's Z corridor (tunnel wall span + margin) that track_bed will carve. */
    private static boolean inCorridorLane(int worldZ, TunnelGeometry tg) {
        return worldZ >= tg.wallMinZ() - CORRIDOR_MARGIN && worldZ <= tg.wallMaxZ() + CORRIDOR_MARGIN;
    }

    /**
     * Whether the band emerges from an <b>ocean biome</b> — sampled from the overworld biome source at the
     * band entrance (the seaward edge of the beach). Only ocean entrances get the leading sand shore; over
     * any other biome the beach stage is skipped. Rivers/lakes are excluded ({@code #minecraft:is_ocean}).
     */
    private static boolean isOceanEntrance(ServerLevel overworld, long entranceX, int trackZ, int seaLevel) {
        if (entranceX == Long.MIN_VALUE) return false;
        try {
            ChunkGenerator gen = overworld.getChunkSource().getGenerator();
            RandomState rs = overworld.getChunkSource().randomState();
            Holder<Biome> biome = gen.getBiomeSource().getNoiseBiome(
                    QuartPos.fromBlock((int) entranceX), QuartPos.fromBlock(seaLevel), QuartPos.fromBlock(trackZ),
                    rs.sampler());
            return biome.is(BiomeTags.IS_OCEAN);
        } catch (Throwable t) {
            return false; // never block worldgen on the biome probe
        }
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

        /** Solid ground (not air, not a fluid) — the surface cells the crossfade may recolour to netherrack. */
        boolean isSolidGround(int dx, int y, int dz) {
            if (!ensure(y)) return false;
            BlockState cur = section.getBlockState(dx, y - baseY, dz);
            return !cur.isAir() && cur.getFluidState().isEmpty();
        }

        boolean isSame(int dx, int y, int dz, BlockState state) {
            if (!ensure(y)) return false;
            return section.getBlockState(dx, y - baseY, dz) == state;
        }

        boolean isAir(int dx, int y, int dz) {
            if (!ensure(y)) return false;
            return section.getBlockState(dx, y - baseY, dz).isAir();
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
