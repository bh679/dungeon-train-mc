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
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
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
import java.util.List;
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
    /** X offset into the Nether so successive bands sample different (but continuous) terrain. */
    private static final int NETHER_SAMPLE_OFFSET_X = 12_000;

    /** Nether-core corridor envelope height above the bed (kept solid netherrack so track_bed tunnels it). */
    private static final int TUNNEL_CLEAR_HEIGHT = 14;
    /** Depth of the surface skin recoloured to netherrack across the crossfade. */
    private static final int SURFACE_SKIN_DEPTH = 4;
    /** Natural relief (blocks above sea) that contributes a full +1 to the [0,1] shore relief. */
    private static final int NATURAL_RELIEF_NORM = 96;
    /** Salt for the crossfade rock→netherrack dither (matches the old mountainMaterial dither). */
    private static final long CROSSFADE_DITHER_SALT = 0x9E3779B97F4A7C15L;
    /** Extra Z clearance on each side of the tunnel wall span. */
    private static final int CORRIDOR_MARGIN = 1;
    /** Guaranteed solid causeway depth below the bed in the Nether core (a netherrack bridge over lava). */
    private static final int CORE_CAUSEWAY_DEPTH = 3;
    /** netherRamp at/above this is the real-Nether core (REPLACE); below it is the netherrack crossfade.
     *  Shared with the biome-source mixin via {@link WorldGenCycle#NETHER_CORE_THRESHOLD}. */
    private static final double CORE_THRESHOLD = WorldGenCycle.NETHER_CORE_THRESHOLD;

    // --- Shore (ocean-entry beach): a gentle natural ramp from the ocean floor up to the mountains. ---
    /** Low-amplitude jitter (± blocks) on the shore surface so the ramp isn't dead flat. */
    private static final double SHORE_JITTER_BLOCKS = 1.5;
    /** Salt mixed into the seed for the shore's gentle value noise (distinct from the mountain noise). */
    private static final long SHORE_JITTER_SALT = 0x5EA5A11DBEA1L;
    /** Minimum solid-sand body depth below the shore surface (extends down to the natural seabed when deeper). */
    private static final int SHORE_BODY_DEPTH = 8;

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
            int baseRelief = DungeonTrainCommonConfig.getNetherBaseReliefBlocks();

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
                if (!core && !inBeachSpan && !crossfade) continue;
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
                                minY, worldTop, seaLevel, baseRelief, seed, beachProgress);
                    } else {
                        colChanged = recolorCrossfadeColumn(chunk, dx, dz, worldX, worldZ, minY, worldTop, n, seed);
                    }
                    changed |= colChanged;
                }
            }

            if (!changed) return false;
            Heightmap.primeHeightmaps(chunk, WG_HEIGHTMAPS);

            // Decorate the real-Nether core with the actual vanilla Nether features (fire, glowstone,
            // springs, ores, mushrooms). Only chunks whose every column is core get it — the core biome
            // is nether_wastes there (so each feature's biome filter passes) and the terrain is netherrack
            // (so the features land correctly). Heightmaps are already primed, so the features see the
            // real surface. track_bed runs after this and carves the tunnel, clearing the corridor lane.
            if (isFullCoreChunk(overworld, cycle, netherDensity, chunkMinX)) {
                decorateCoreChunkWithNetherFeatures(level, ctx.chunkGenerator(), server, cp, bedY);
            }

            chunk.setUnsaved(true);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] NetherTransitionFeature.place failed at chunk {}", ctx.origin(), t);
            return false;
        }
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
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            if (!cycle.isNetherCore(worldX)) return false;
            if (DisintegrationBand.middleRampAt(overworld, worldX) > 0.0) return false;
        }
        return true;
    }

    /**
     * Run the real {@code minecraft:nether_wastes} decoration features over this (fully-core) chunk —
     * the exact vanilla configured features that decorate the real Nether (fire, glowstone, springs,
     * ores, mushrooms). We resolve the biome's
     * {@link net.minecraft.world.level.biome.BiomeGenerationSettings#features() per-step placed-feature
     * lists} and place each one ourselves (mirroring {@code ChunkGenerator.applyBiomeDecoration}'s
     * seeding via {@link WorldgenRandom#setDecorationSeed}/{@link WorldgenRandom#setFeatureSeed}). Two
     * adaptations are needed because we run the Nether's features inside an Overworld chunk — see
     * {@link #remapForCore}. Same invoke-a-vanilla-feature technique as {@link DisintegrationFeature}'s
     * {@code Feature.CHORUS_PLANT.place}. Each feature is isolated in a try/catch so one bad placement
     * can never abort worldgen.
     */
    private void decorateCoreChunkWithNetherFeatures(WorldGenLevel level, ChunkGenerator generator,
                                                     MinecraftServer server, ChunkPos cp, int bedY) {
        Holder<Biome> netherBiome;
        try {
            netherBiome = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES);
        } catch (Throwable t) {
            return; // no nether_wastes biome (data pack stripped?) — skip decoration, never fail worldgen
        }
        // The sampled core terrain occupies this world-Y band (same mapping fillNetherColumn uses);
        // retarget the Nether features' (y0..128-calibrated) height ranges onto it so they hit the rock.
        int coreBottom = bedY + (NETHER_SAMPLE_Y_MIN - NETHER_CENTER_Y);
        int coreTop = bedY + (NETHER_SAMPLE_Y_MAX - NETHER_CENTER_Y);
        List<HolderSet<PlacedFeature>> steps = netherBiome.value().getGenerationSettings().features();
        BlockPos origin = cp.getWorldPosition(); // chunk min corner; placement modifiers spread/raise from here
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(level.getSeed()));
        long decoSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        int featureIndex = 0;
        int placed = 0;
        for (int step = 0; step < steps.size(); step++) {
            for (Holder<PlacedFeature> holder : steps.get(step)) {
                random.setFeatureSeed(decoSeed, featureIndex++, step);
                try {
                    if (remapForCore(holder.value(), coreBottom, coreTop).place(level, generator, random, origin)) {
                        placed++;
                    }
                } catch (Throwable t) {
                    LOGGER.error("[DungeonTrain] Nether-core feature placement failed at chunk {}", cp, t);
                }
            }
        }
        LOGGER.debug("[DungeonTrain] Decorated Nether core chunk {} with vanilla nether_wastes features ({}/{} placed, band y{}..{})",
                cp, placed, featureIndex, coreBottom, coreTop);
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

    /**
     * Leading <b>shore</b> column where the band emerges from an ocean biome: a gentle sand ramp that
     * climbs smoothly from the natural ocean floor at the seaward edge ({@code progress 0}, submerged)
     * up to the height the first mountain stage needs at the inland edge ({@code progress 1}), so the
     * shore meets BOTH the seabed and the mountains with no step. Shaped by low-amplitude value noise
     * (a couple of blocks of jitter), NOT the dramatic ridged mountain heightmap. Below the sand
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
                                    int minY, int worldTop, int seaLevel, int baseRelief, long seed, double progress) {
        int surfaceY = Math.max(minY, Math.min(worldTop, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz)));
        // Smooth ramp from the natural ocean floor here (progress 0 → submerged, shallows above) up to the
        // height the first mountain stage (×1) needs here (progress 1) — the SAME relief math as
        // fillMountainColumn at mult ×1, so the shore and the mountains meet seamlessly. Plus gentle jitter.
        double natural01 = Math.max(0, surfaceY - seaLevel) / (double) NATURAL_RELIEF_NORM;
        double relief01 = Math.min(1.0, MountainNoise.height01(seed, worldX, worldZ) + natural01);
        int mountainTop = seaLevel + (int) Math.round(relief01 * baseRelief);
        double jitter = (MountainNoise.height01(seed ^ SHORE_JITTER_SALT, worldX, worldZ) - 0.5) * (2.0 * SHORE_JITTER_BLOCKS);
        int columnTop = (int) Math.round(surfaceY + (mountainTop - surfaceY) * progress + jitter);
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
