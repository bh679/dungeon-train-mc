package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import games.brennan.dungeontrain.worldgen.density.NetherCoreBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
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
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Worldgen feature for the <b>Nether transition band</b> (overworld, runs at
 * {@code top_layer_modification} before {@code track_bed}). The band's mountain BODY is no longer
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
 * <p>On chunks that are <b>entirely core</b>, after the netherrack/lava is stamped, the actual vanilla
 * {@code nether_wastes} configured features (fire, glowstone, springs, ores, mushrooms) are run over the
 * chunk ({@link #decorateCoreChunkWithNetherFeatures}, with the per-feature placement adapted to the
 * core by {@link #remapForCore}) so the core decorates exactly like the real Nether. Those columns are
 * also tagged {@code nether_wastes} by the biome-source mixin for Nether fog/ambient/music.</p>
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
    /** X offset into the Nether so successive bands sample different (but continuous) terrain. Shared with
     *  the biome sampler ({@link NetherCoreBiomes#SAMPLE_OFFSET_X}) so terrain + biome + surface align. */
    private static final int NETHER_SAMPLE_OFFSET_X = NetherCoreBiomes.SAMPLE_OFFSET_X;

    /** Height above the bed at which a shore column is tall enough that {@code track_bed} will tunnel it,
     *  so the shore ramp need not hold its own train lane open (the only remaining user of this constant). */
    private static final int TUNNEL_CLEAR_HEIGHT = 14;
    /** Depth of the surface skin recoloured to netherrack across the crossfade. */
    private static final int SURFACE_SKIN_DEPTH = 4;
    /** Salt for the crossfade rock→netherrack dither (matches the old mountainMaterial dither). */
    private static final long CROSSFADE_DITHER_SALT = 0x9E3779B97F4A7C15L;
    /** Salt for the per-biome surface-skin material mix (soul_sand/soul_soil, basalt/blackstone). */
    private static final long NETHER_SURFACE_SKIN_SALT = 0x68E31DA4FB4A7E1FL;
    /** Extra Z clearance on each side of the tunnel wall span. */
    private static final int CORRIDOR_MARGIN = 1;
    /** netherRamp at/above this is the real-Nether core (REPLACE); below it is the netherrack crossfade.
     *  Shared with the biome-source mixin via {@link WorldGenCycle#NETHER_CORE_THRESHOLD}. */
    private static final double CORE_THRESHOLD = WorldGenCycle.NETHER_CORE_THRESHOLD;

    // --- Shore (ocean-entry beach): a gentle natural ramp from the ocean floor up to the mountains. ---
    /** Minimum solid-sand body depth below the shore surface (extends down to the natural seabed when deeper). */
    private static final int SHORE_BODY_DEPTH = 8;
    /** Upward dune noise (blocks): broad swell over the base ~96-block feature size. */
    private static final double SHORE_NOISE_BROAD = 2.0;
    /** Upward ripple noise (blocks): finer features that break up the ramp's flat terraces (kept gentle). */
    private static final double SHORE_NOISE_FINE = 1.25;
    /** Frequency multiplier for the fine ripple octave (≈96/4 = 24-block features). */
    private static final double SHORE_NOISE_FINE_SCALE = 4.0;
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
        long genT0 = GenProfiler.t0();
        try {
            return placeInner(ctx);
        } finally {
            GenProfiler.add(GenProfiler.Bucket.NETHER_FEATURE, genT0);
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

            long startX = NetherBand.startX(overworld);
            int chunkMinX = cp.getMinBlockX();
            if (chunkMinX + 15 < startX) return false; // before the first band (or disabled)

            WorldGenCycle cycle = WorldGenCycle.fromConfig();
            int seaLevel = overworld.getSeaLevel();

            // The band front is edge-waved (NetherMountainTerrain#wavyX) per column, so a chunk within one
            // wave-shift of the band can have in-band columns; test the X range expanded by that margin.
            boolean anyHeight = false;
            int margin = NetherMountainTerrain.maxEdgeShift();
            for (int x = chunkMinX - margin; x <= chunkMinX + 15 + margin && !anyHeight; x++) {
                if (cycle.netherHeightRamp(x) > 0.0) anyHeight = true;
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

            // Per-world snapshot of the real-Nether biome sampler (all five biomes) for the core's
            // biome label / surface skin / decoration. Null only before the snapshot lands or on a
            // Nether-less world — then the core stays single-biome (nether_wastes / netherrack).
            NetherBandContext bandCtx = NetherBandContext.current();

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

            // Per-chunk cache of real-Nether density profiles keyed by corner column (x,z). The core
            // columns share their 4 XZ cell corners across the whole chunk, so caching collapses the
            // ~16k router evaluations/chunk to the few hundred distinct corners — the dominant
            // Nether-crossing gen cost (see GenProfiler CORE_REPLACE). Scoped to this place() call.
            Map<Long, double[]> netherCornerCache = new HashMap<>();

            boolean changed = false;
            for (int dx = 0; dx < 16; dx++) {
                int worldX = chunkMinX + dx;
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    // Evaluate the band at the edge-waved X (matched to the density router + biome source) so
                    // the leading/trailing front, the beach span and the crossfade all undulate together.
                    int wx = NetherMountainTerrain.wavyX(seed, worldX, worldZ);
                    if (cycle.netherHeightRamp(wx) <= 0.0) continue;
                    // Precedence: the End band always wins — skip columns it owns.
                    if (DisintegrationBand.middleRampAt(overworld, wx) > 0.0) continue;

                    double n = cycle.netherRamp(wx);
                    boolean core = n >= CORE_THRESHOLD && netherDensity != null;
                    boolean inBeachSpan = !core && cycle.isNetherBeachStage(wx);
                    // The beach stage exists ONLY where the band emerges from an ocean biome; otherwise it is
                    // SKIPPED — those columns stay natural overworld and the noise mountains pick up after the span.
                    if (inBeachSpan && !oceanEntrance) continue;
                    // Pure mountain-stage columns (n == 0, not the beach) are built entirely by the terrain-noise
                    // density wrapper — nothing to post-process here (so grass/trees/structures survive), EXCEPT
                    // the ocean shore-skin below.
                    boolean crossfade = !core && !inBeachSpan && n > 0.0;
                    // Over an ocean entrance the feathered mountain rises out of sea level across the entry/exit
                    // fade, leaving a stony shoreline; recolour that low surface to beach sand. Only the fade
                    // zone (feather < 1) can be near sea level — the high interior + all land bands are skipped.
                    boolean shoreSkin = !core && !inBeachSpan && !crossfade
                            && oceanEntrance && cycle.netherMountainFeather(wx) < 1.0;
                    if (!core && !inBeachSpan && !crossfade && !shoreSkin) continue;
                    double beachProgress = inBeachSpan ? cycle.netherBeachProgress(wx) : 0.0;
                    int sampleX = wx + NETHER_SAMPLE_OFFSET_X;

                    boolean colChanged;
                    if (core) {
                        ResourceKey<Biome> coreBiome = coreBiomeKeyAt(bandCtx, worldX, worldZ);
                        long coreT0 = GenProfiler.t0();       // real-Nether router sampling — the confirmed DT hotspot
                        colChanged = fillNetherColumn(chunk, dx, dz, worldX, worldZ, bedY, railY, zMin, zMax, tg,
                                minY, worldTop, sampleX, netherDensity, cellW, cellH, netherSeaLevel,
                                seed, coreBiome, netherCornerCache);
                        GenProfiler.add(GenProfiler.Bucket.CORE_REPLACE, coreT0);
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

            // Prime heightmaps before decoration so the vanilla nether features see the real surface.
            if (changed) Heightmap.primeHeightmaps(chunk, WG_HEIGHTMAPS);

            // Decorate the real-Nether core with the actual vanilla Nether features (fire, glowstone,
            // springs, ores, mushrooms). Only chunks whose every column is core get it — the core biome
            // is nether_wastes there (so each feature's biome filter passes) and the terrain is netherrack
            // (so the features land correctly).
            if (isFullCoreChunk(overworld, cycle, netherDensity, chunkMinX)) {
                decorateCoreChunkWithNetherFeatures(level, ctx.chunkGenerator(), server, cp, bedY, bandCtx);
            }

            // Clearance guarantee (runs LAST, for every in-band corridor column — including the
            // pure-mountain chunks the terrain loop skipped, and after decoration so a feature dropped
            // into the lane is removed too): clears the train's airspace of any solid netherrack/stone the
            // later track_bed wouldn't carve, so the tunnel/viaduct ride space is never blocked.
            changed |= clearCorridorClearance(chunk, overworld, cycle, seed, chunkMinX, chunkMinZ,
                    bedY, railY, zMin, zMax, tg, minY, worldTop);

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
     * Clearance guarantee for the train's ride space across the whole Nether-band corridor.
     *
     * <p>{@code track_bed}'s tunnel carve only acts on columns that qualify as "underground"
     * ({@link games.brennan.dungeontrain.tunnel.TunnelGenerator#isColumnUndergroundWorldgen} probes
     * at {@code ceilingY+1}; extended mode only reaches {@code bedY+7}). A column whose terrain pokes
     * into the lower clearance ({@code bedY+1..bedY+6}, where the train body sits) but is open above
     * is never carved, leaving a netherrack (core) / stone (mountain transition) stub in the train's
     * path. This pass removes those stubs.</p>
     *
     * <p>Bounded to {@code bedY+1 .. ceilingY-1} — strictly below the {@code ceilingY+1} qualification
     * probe, so it never suppresses a genuine tunnel — and to the airspace Z span
     * ({@code airMinZ..airMaxZ}), so the tunnel walls ({@code wallMinZ/wallMaxZ}) survive: a real
     * tunnel keeps its walls while a lava viaduct stays open. Only solid cells are cleared
     * ({@link ColumnWriter#isSolidGround}); air and lava (which sits well below the bed) are left
     * alone, and the bed/rails are preserved. Returns whether any block was cleared.</p>
     */
    private boolean clearCorridorClearance(ChunkAccess chunk, ServerLevel overworld, WorldGenCycle cycle,
                                           long seed, int chunkMinX, int chunkMinZ, int bedY, int railY,
                                           int zMin, int zMax, TunnelGeometry tg, int minY, int worldTop) {
        int zClearMin = Math.max(chunkMinZ, tg.airMinZ());
        int zClearMax = Math.min(chunkMinZ + 15, tg.airMaxZ());
        if (zClearMin > zClearMax) return false;                  // corridor not in this chunk's Z span
        int yBot = Math.max(minY, bedY + 1);
        int yTop = Math.min(worldTop, tg.ceilingY() - 1);         // below the bedY+10 qualification probe
        if (yBot > yTop) return false;

        ColumnWriter w = new ColumnWriter(chunk);
        boolean changed = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            for (int worldZ = zClearMin; worldZ <= zClearMax; worldZ++) {
                int dz = worldZ - chunkMinZ;
                // Band membership is edge-waved per column (matched to place()'s main loop), so resolve the
                // waved X here too before testing in-band / End-precedence.
                int wx = NetherMountainTerrain.wavyX(seed, worldX, worldZ);
                if (cycle.netherHeightRamp(wx) <= 0.0) continue;            // not in the band at this column
                if (DisintegrationBand.middleRampAt(overworld, wx) > 0.0) continue; // End owns this column
                for (int y = yBot; y <= yTop; y++) {
                    if (isTrackBlock(worldZ, y, bedY, railY, zMin, zMax, tg)) continue;
                    if (!w.isSolidGround(dx, y, dz)) continue;    // leave air / lava; clear solid stubs only
                    w.set(dx, y, dz, AIR);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * True when every column of this chunk is a real-Nether core column (and the Nether router is
     * available to have stamped it) — and none is owned by the End band. Only such chunks get the
     * vanilla Nether feature pass, so decoration never bleeds onto the netherrack crossfade or the
     * green mountains flanking the core.
     */
    private static boolean isFullCoreChunk(ServerLevel overworld, WorldGenCycle cycle,
                                           DensityFunction netherDensity, int chunkMinX) {
        if (netherDensity == null) return false;
        // The core boundary is edge-waved per Z (NetherMountainTerrain#wavyX, ±maxEdgeShift), so require the
        // core to extend a wave-margin past the chunk on both X sides — then EVERY column's waved X is core,
        // and the vanilla Nether decoration never bleeds onto the crossfade/mountains.
        int margin = NetherMountainTerrain.maxEdgeShift();
        for (int worldX = chunkMinX - margin; worldX <= chunkMinX + 15 + margin; worldX++) {
            if (!cycle.isNetherCore(worldX)) return false;
            if (DisintegrationBand.middleRampAt(overworld, worldX) > 0.0) return false;
        }
        return true;
    }

    /**
     * Run the real Nether decoration features over this (fully-core) chunk — the exact vanilla configured
     * features that decorate the real Nether. The chunk's biome(s) are sampled the same way the world is
     * labelled ({@link #coreChunkBiomes}), so a chunk in a crimson forest gets crimson fungi, a warped
     * forest gets warped fungi, soul sand valley gets soul fire + fossils, basalt deltas gets basalt
     * pillars + deltas, plus the shared fire/glowstone/springs/ores. We resolve each biome's
     * {@link net.minecraft.world.level.biome.BiomeGenerationSettings#features() per-step placed-feature
     * lists} and place each one ourselves (mirroring {@code ChunkGenerator.applyBiomeDecoration}'s
     * per-{@code GenerationStep} order + seeding via {@link WorldgenRandom#setDecorationSeed}/
     * {@link WorldgenRandom#setFeatureSeed}); a feature shared by several biomes is placed once per step.
     * Two adaptations are needed because we run the Nether's features inside an Overworld chunk — see
     * {@link #remapForCore}. Same invoke-a-vanilla-feature technique as {@link DisintegrationFeature}'s
     * {@code Feature.CHORUS_PLANT.place}. Each feature is isolated in a try/catch so one bad placement
     * can never abort worldgen.
     */
    private void decorateCoreChunkWithNetherFeatures(WorldGenLevel level, ChunkGenerator generator,
                                                     MinecraftServer server, ChunkPos cp, int bedY,
                                                     NetherBandContext bandCtx) {
        List<Holder<Biome>> biomes = coreChunkBiomes(server, cp, bandCtx);
        if (biomes.isEmpty()) return; // no biome resolvable (data pack stripped?) — never fail worldgen

        // The sampled core terrain occupies this world-Y band (same mapping fillNetherColumn uses);
        // retarget the Nether features' (y0..128-calibrated) height ranges onto it so they hit the rock.
        int coreBottom = bedY + (NETHER_SAMPLE_Y_MIN - NETHER_CENTER_Y);
        int coreTop = bedY + (NETHER_SAMPLE_Y_MAX - NETHER_CENTER_Y);
        BlockPos origin = cp.getWorldPosition(); // chunk min corner; placement modifiers spread/raise from here
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(level.getSeed()));
        long decoSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());

        int maxSteps = 0;
        for (Holder<Biome> b : biomes) {
            maxSteps = Math.max(maxSteps, b.value().getGenerationSettings().features().size());
        }
        int featureIndex = 0;
        int placed = 0;
        for (int step = 0; step < maxSteps; step++) {
            Set<PlacedFeature> placedThisStep = new HashSet<>(); // a feature shared by biomes runs once/step
            for (Holder<Biome> biome : biomes) {
                List<HolderSet<PlacedFeature>> steps = biome.value().getGenerationSettings().features();
                if (step >= steps.size()) continue;
                for (Holder<PlacedFeature> holder : steps.get(step)) {
                    PlacedFeature pf = holder.value();
                    if (!placedThisStep.add(pf)) continue;
                    random.setFeatureSeed(decoSeed, featureIndex++, step);
                    try {
                        if (remapForCore(pf, coreBottom, coreTop).place(level, generator, random, origin)) {
                            placed++;
                        }
                    } catch (Throwable t) {
                        LOGGER.error("[DungeonTrain] Nether-core feature placement failed at chunk {}", cp, t);
                    }
                }
            }
        }
        LOGGER.debug("[DungeonTrain] Decorated Nether core chunk {} with {} biome(s) ({}/{} features placed, band y{}..{})",
                cp, biomes.size(), placed, featureIndex, coreBottom, coreTop);
    }

    /**
     * The distinct real-Nether biomes covering this core chunk — sampled at the four corners + centre via
     * the same {@link NetherCoreBiomes} the world label uses, so the features match the labelled biome.
     * Falls back to {@code nether_wastes} when the band snapshot or Nether dimension is unavailable.
     */
    private static List<Holder<Biome>> coreChunkBiomes(MinecraftServer server, ChunkPos cp, NetherBandContext bandCtx) {
        Set<Holder<Biome>> out = new LinkedHashSet<>();
        if (bandCtx != null && bandCtx.netherCoreBiomes() != null) {
            NetherCoreBiomes ncb = bandCtx.netherCoreBiomes();
            int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ(), x1 = cp.getMaxBlockX(), z1 = cp.getMaxBlockZ();
            int[][] pts = {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}, {(x0 + x1) >> 1, (z0 + z1) >> 1}};
            for (int[] p : pts) {
                Holder<Biome> b = ncb.biomeAt(p[0], p[1]);
                if (b != null) out.add(b);
            }
        }
        if (out.isEmpty()) {
            try {
                out.add(server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES));
            } catch (Throwable ignored) {
                // no nether_wastes (data pack stripped?) — leave empty; caller skips decoration
            }
        }
        return new ArrayList<>(out);
    }

    /** The real-Nether biome KEY for a core column (drives the per-biome surface skin); nether_wastes fallback. */
    private static ResourceKey<Biome> coreBiomeKeyAt(NetherBandContext bandCtx, int worldX, int worldZ) {
        if (bandCtx == null || bandCtx.netherCoreBiomes() == null) return Biomes.NETHER_WASTES;
        return bandCtx.netherCoreBiomes().biomeAt(worldX, worldZ).unwrapKey().orElse(Biomes.NETHER_WASTES);
    }

    /**
     * Adapt a vanilla {@code nether_wastes} {@link PlacedFeature} so it actually decorates our
     * Overworld-embedded core:
     * <ol>
     *   <li>drop the {@code minecraft:biome} ({@link BiomeFilter}) modifier — it is the one modifier
     *       that requires a registered "top feature" and would otherwise throw
     *       ({@code IllegalStateException: Tried to biome check an unregistered feature}) under the
     *       no-biome-check {@link PlacedFeature#place} path; without it the feature places
     *       unconditionally on whatever its block predicate accepts (netherrack);</li>
     *   <li>retarget any {@link HeightRangePlacement} to the core's real world-Y band — the Nether's
     *       native ranges are calibrated for the Nether's {@code y0..128}, so left alone they scatter
     *       across the whole Overworld column ({@code y-64..255}) and miss the thin core, leaving it
     *       bare. The features' own block predicates still pick valid spots within the band (fire on
     *       floors, glowstone on ceilings, ores in rock).</li>
     * </ol>
     * Returns the original feature unchanged if it has neither modifier.
     */
    private static PlacedFeature remapForCore(PlacedFeature pf, int coreBottom, int coreTop) {
        List<PlacementModifier> out = new ArrayList<>(pf.placement().size());
        boolean changed = false;
        for (PlacementModifier m : pf.placement()) {
            if (m instanceof BiomeFilter) {
                changed = true;                                  // drop the biome gate
            } else if (m instanceof HeightRangePlacement) {
                out.add(HeightRangePlacement.uniform(
                        VerticalAnchor.absolute(coreBottom), VerticalAnchor.absolute(coreTop)));
                changed = true;                                  // retarget onto the core band
            } else {
                out.add(m);
            }
        }
        return changed ? new PlacedFeature(pf.feature(), out) : pf;
    }

    /**
     * Crossfade ({@code 0 < n < 1}): the mountain body is already real terrain (built by the density
     * wrapper) with vanilla grass/dirt/stone on top, so this only DITHERS the existing surface skin to
     * netherrack as the real-Nether core approaches — turning the green mountain increasingly red over
     * the {@code coreFade} span. It reads the actual chunk heightmap top (which already reflects the
     * raised terrain at {@code top_layer_modification}) and recolours the top {@link #SURFACE_SKIN_DEPTH}
     * solid blocks where the coherent dither falls under {@code n}; everything below stays natural stone.
     * No {@code MountainNoise}/palette recompute is needed.
     *
     * <p>It also <b>drains worldgen water</b> from the column (aquifer pools, springs, surface lakes):
     * the recoloured skin is only the top few blocks, so the mountain body underneath is still overworld
     * terrain and {@code aquifers_enabled} can leave water in it. The real Nether is bone-dry, so any
     * water in the netherrack crossfade is replaced with air. This runs during chunk generation (no
     * fluid-cascade hazard) and only for crossfade columns — overworld gaps, the green approach mountains,
     * the ocean beach/shore and the End band all keep their water. The Nether core needs no drain: its
     * whole band is already overwritten with netherrack/lava/air by {@link #fillNetherColumn}.</p>
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
        // Drain any worldgen water in the column. Scan from the motion-blocking surface (the top of any
        // water) down to the build floor; only water cells are cleared, so solid terrain is untouched.
        int waterTop = Math.min(worldTop, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, dx, dz));
        for (int y = waterTop; y >= minY; y--) {
            if (!w.isWater(dx, y, dz)) continue;
            w.set(dx, y, dz, AIR);
            changed = true;
        }
        return changed;
    }

    /** Upward beach-dune height (blocks) at a column: broad swell + finer ripples, the SAME coherent field
     *  the shore ramp and the shore skin both add to their base, so the two stay continuous at the seam.
     *  Uses SMOOTH (non-ridged) noise so the dunes are soft rolling mounds, not sharp ridges. */
    private static double shoreDuneHeight(long seed, int worldX, int worldZ) {
        double broad = MountainNoise.smooth01(seed ^ SHORE_NOISE_SALT, worldX, worldZ);                 // [0,1]
        double fine = MountainNoise.smooth01(seed ^ SHORE_NOISE_FINE_SALT,
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
     * up to the waterline at the inland edge ({@code progress 1}, base {@code seaLevel-1}) — a natural
     * beach. The feathered noise mountains grow inland from that same {@code seaLevel-1} gate column, and
     * both add the same upward {@link #shoreDuneHeight} dunes with identical rounding, so the shore meets
     * BOTH the seabed and the mountains with no step/notch. Below the sand
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
        // read as a natural, wavy coastline rather than flat terraces along Z. The ramp and the dune are
        // rounded SEPARATELY — exactly like recolorShoreSkinColumn does (integer base + round(dune)) — so
        // at the inland edge both reduce to (seaLevel-1) + round(dune), identical to the feathered mountain's
        // gate column, and the seam is flush (combined rounding drifted ±1 when the dune fraction crossed .5).
        int rampTop = (int) Math.round(surfaceY + (mountainTop - surfaceY) * progress);
        int columnTop = rampTop + (int) Math.round(shoreDuneHeight(seed, worldX, worldZ));
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
     * density is solid, lava below the Nether sea, air for caverns. The corridor lane gets the
     * same natural terrain as everywhere else (no forced causeway/envelope): the later
     * {@code track_bed} feature then tunnels through the solid netherrack and rides pillars
     * across the open lava lakes / caverns, exactly as it does over the End band's islands/void.
     */
    private boolean fillNetherColumn(ChunkAccess chunk, int dx, int dz, int worldX, int worldZ,
                                     int bedY, int railY, int zMin, int zMax, TunnelGeometry tg,
                                     int minY, int worldTop, int sampleX, DensityFunction netherDensity,
                                     int cellW, int cellH, int netherSeaLevel,
                                     long seed, ResourceKey<Biome> coreBiome, Map<Long, double[]> cornerCache) {
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
        // Corner profiles are cached per (cornerX, cornerZ) for this chunk — cellRowBase/rows/cellH are
        // constant across every column of the chunk, so a corner's profile is identical wherever it is
        // referenced. This collapses the per-block-column re-sampling of the shared corners.
        double[] c00 = cornerProfile(cornerCache, netherDensity, x0, z0, cellRowBase, rows, cellH);
        double[] c10 = cornerProfile(cornerCache, netherDensity, x1, z0, cellRowBase, rows, cellH);
        double[] c01 = cornerProfile(cornerCache, netherDensity, x0, z1, cellRowBase, rows, cellH);
        double[] c11 = cornerProfile(cornerCache, netherDensity, x1, z1, cellRowBase, rows, cellH);

        ColumnWriter w = new ColumnWriter(chunk);
        boolean changed = false;
        for (int y = yLo; y <= yHi; y++) {
            if (isTrackBlock(worldZ, y, bedY, railY, zMin, zMax, tg)) continue;
            // Fill the corridor lane with the same natural Nether terrain as every other column — no forced
            // solid envelope. track_bed runs after this and tunnels through the solid netherrack while
            // pillaring across the open lava lakes / caverns: its ground probe treats lava as passable and
            // rests pillars on the netherrack floor, and the tunnel only qualifies where rock sits above the bed.
            int netherY = NETHER_CENTER_Y + (y - bedY);
            int r = Math.floorDiv(netherY, cellH) - cellRowBase;
            if (r < 0 || r + 1 >= rows) continue;
            double fy = (double) (netherY - (cellRowBase + r) * cellH) / cellH;
            double bot = bilerp(c00[r], c10[r], c01[r], c11[r], fx, fz);
            double top = bilerp(c00[r + 1], c10[r + 1], c01[r + 1], c11[r + 1], fx, fz);
            double d = bot + (top - bot) * fy;
            BlockState target;
            if (d > 0.0) {
                target = NETHERRACK;
            } else if (netherY < netherSeaLevel) {
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
        // corridor lane is skipped so the train tunnel stays clean netherrack (and outside the lane there
        // are no track blocks). Every upward-facing netherrack surface in the column is skinned (like real
        // Nether nylium); cells under lava/with no air above are left as netherrack.
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
     * The real-Nether density profile of one corner column {@code (cornerX, cornerZ)} — the {@code rows}
     * samples {@code netherDensity.compute(cornerX, (cellRowBase+r)*cellH, cornerZ)} — memoised in
     * {@code cache} for the duration of a single chunk's {@code place()}. Every core block-column shares
     * its 4 XZ cell corners with its neighbours in the same {@code cellW} cell / Z-row, so the same corner
     * was previously re-evaluated once per block-column (~16k router walks/chunk); caching evaluates each
     * distinct corner exactly once (a few hundred/chunk). Byte-identical — a pure memo of the pure
     * {@code compute}, removing only duplicate evaluations.
     *
     * <p>Correctness rests on {@code cellRowBase}, {@code rows} and {@code cellH} being constant across
     * every column of the chunk (they derive from {@code bedY}/{@code minY}/{@code worldTop}, not the
     * column), so a corner's profile is independent of which column requests it. Package-private + static
     * so it is unit-testable with a stub {@link DensityFunction}.</p>
     */
    static double[] cornerProfile(Map<Long, double[]> cache, DensityFunction netherDensity,
                                  int cornerX, int cornerZ, int cellRowBase, int rows, int cellH) {
        long key = (((long) cornerX) << 32) ^ (cornerZ & 0xFFFFFFFFL);
        double[] cached = cache.get(key);
        if (cached != null) return cached;
        double[] profile = new double[rows];
        for (int r = 0; r < rows; r++) {
            int by = (cellRowBase + r) * cellH;
            profile[r] = netherDensity.compute(new DensityFunction.SinglePointContext(cornerX, by, cornerZ));
        }
        cache.put(key, profile);
        return profile;
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

        /** Water (source, flowing, or waterlogged) — the cells the crossfade drains to air. */
        boolean isWater(int dx, int y, int dz) {
            if (!ensure(y)) return false;
            return section.getBlockState(dx, y - baseY, dz).getFluidState().is(FluidTags.WATER);
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
