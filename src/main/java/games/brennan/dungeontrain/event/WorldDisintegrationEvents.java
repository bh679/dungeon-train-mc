package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.SampledCells;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Set;

/**
 * Erodes overworld terrain (and the track's support pillars) to void across the
 * disintegration band, per the {@link Disintegration#middleRamp}. Runs on
 * {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()} — so it fires
 * once at generation, never on reload (player builds survive), and crucially runs
 * <b>after all worldgen decoration of every chunk</b>, so vegetation (trees/leaves)
 * that spills in from neighbouring chunks is cleaned up too.
 *
 * <p>Preserved: the track bed + rails (by geometry), the End-stone islands +
 * chorus plants that {@code DisintegrationFeature} placed during generation (by block
 * type), and the cells a sampled BetterEnd / BoP End pass wrote during the chunk's own
 * worldgen ({@link EndBandInlineTerrain}, recorded per chunk in
 * {@link ModDataAttachments#END_BAND_SAMPLED_CELLS}) — everything else in the band is
 * dissolved. The background path needs no record: it writes its sample on a later tick, after
 * this erosion. Writes go through raw {@link LevelChunkSection#setBlockState}, the Sable-safe
 * path (see {@link BedrockFloorEvents}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldDisintegrationEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private WorldDisintegrationEvents() {}

    /**
     * The End city's block palette — every block id that appears in a vanilla {@code end_city} template.
     * Cities are placed by {@link games.brennan.dungeontrain.worldgen.structure.BandEndCityStructure}
     * during generation and must survive the void erosion intact, loot chests and all.
     *
     * <p>Matching on the palette rather than on the structure's bounding box is deliberate: erosion runs
     * from a chunk-load handler, where resolving a structure start means reaching into neighbouring
     * chunks that may not be loaded. None of these blocks occur anywhere else in the band, so the palette
     * is both exact and free of re-entrancy.</p>
     */
    private static final Set<Block> END_CITY_BLOCKS = Set.of(
            Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.PURPUR_STAIRS, Blocks.PURPUR_SLAB,
            Blocks.END_STONE_BRICKS, Blocks.MAGENTA_STAINED_GLASS, Blocks.OBSIDIAN, Blocks.END_ROD,
            Blocks.LADDER, Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.BREWING_STAND,
            Blocks.DRAGON_WALL_HEAD, Blocks.MAGENTA_WALL_BANNER);

    /**
     * Blocks the End generation placed — never eroded, so the islands, chorus and End cities float in the
     * void the way they do in the real End.
     *
     * <p>{@code core} is true only in the fully-eroded band core, the one place End cities generate. The
     * city palette is checked there and nowhere else, so ordinary overworld builds that happen to share a
     * block with an End city (a village chest, a ruined portal's obsidian) still erode in the fade zones.</p>
     */
    private static boolean isPreservedEndBlock(BlockState state, boolean core) {
        if (state.is(Blocks.END_STONE) || state.is(Blocks.CHORUS_PLANT) || state.is(Blocks.CHORUS_FLOWER)) {
            return true;
        }
        return core && END_CITY_BLOCKS.contains(state.getBlock());
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        ChunkAccess chunk = event.getChunk();
        erode(level, chunk, chunk.getData(ModDataAttachments.END_BAND_SAMPLED_CELLS));
    }

    /**
     * The void erosion of one chunk; {@code exempt} cells are left alone. Deterministic in the world seed
     * and the block position, so running it twice on a chunk removes nothing the first pass kept — which
     * is how {@link EndBandInlineTerrain} uses it: once inside worldgen before the End sample is written
     * (so the sample lands in cleared air, as it does on the background path), and once more at chunk
     * load, exempting the sampled cells, to catch what neighbouring chunks' decoration spilled in since.
     * True if any block was removed.
     */
    public static boolean erode(ServerLevel level, ChunkAccess chunk, SampledCells exempt) {
        long startX = DisintegrationBand.startX(level);
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        if (startX == DisintegrationBand.OFF) return false; // disabled (bands run both ways from the anchor)
        if (exempt.isAll()) return false;                   // record lost (see ModDataAttachments) — keep the islands

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
        int bedY = g.bedY();
        int railY = g.railY();
        int zMin = g.trackZMin();
        int zMax = g.trackZMax();
        // Tunnel footprint Z-span (wall to wall) — the widest DT corridor structure (pillars and
        // down-stairs sit inside it). In the fully-eroded band core this whole span is preserved
        // below, so the bed/rails, pillars, and the tunnels/cuttings the track now carves THROUGH
        // the End islands survive the void erosion. End islands only exist where the band is fully
        // eroded (middleRamp == 1), so this protects exactly the new structures without touching
        // the fade zones (which keep their existing partial-erosion look).
        TunnelGeometry tg = TunnelGeometry.from(g);
        int preserveZMin = tg.wallMinZ();
        int preserveZMax = tg.wallMaxZ();
        long seed = data.getGenerationSeed();

        double[] middle = new double[16];
        boolean anyMiddle = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            // Entry lead-in zone (immediately before the upside-down band): stop eroding so real
            // terrain survives as WorldUpsideDownEvents' partial-mirror source material. Only affects
            // this erosion pass — DisintegrationBand.middleRampAt itself is untouched, so every other
            // consumer (End-band-wins precedence, mob spawning, BedrockFloorEvents) still treats this
            // stretch as the void/End band.
            middle[dx] = UpsideDownBand.isInEntryLead(level, worldX, pos.getMinBlockZ()) ? 0.0
                    : DisintegrationBand.middleRampAt(level, worldX, pos.getMinBlockZ());
            if (middle[dx] > 0.0) anyMiddle = true;
        }
        if (!anyMiddle) return false;

        int chunkMinZ = pos.getMinBlockZ();
        boolean changed = false;

        // Time the void erosion (main-thread; the [gen.timing] EROSION bucket). Started here — after the
        // cheap new-chunk / dimension / band guards — so only real fade-band erosion work is attributed.
        long genT0 = GenProfiler.t0();
        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            if (section.hasOnlyAir()) continue;
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            for (int dx = 0; dx < 16; dx++) {
                double ramp = middle[dx];
                if (ramp <= 0.0) continue;
                int worldX = chunkMinX + dx;
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    // Fully-eroded core (ramp == 1): keep the whole corridor structure footprint —
                    // bed, rails, pillars, and the tunnel/cutting carved through the island —
                    // intact instead of dissolving it to void.
                    if (ramp >= 1.0 && worldZ >= preserveZMin && worldZ <= preserveZMax) continue;
                    boolean corridorZ = worldZ >= zMin && worldZ <= zMax;
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (corridorZ && (y == bedY || y == railY)) continue;
                        double p = Disintegration.removalProbabilityFromRamp(ramp, y, bedY);
                        if (p <= 0.0) continue;
                        if (Disintegration.coherentNoise(seed, worldX, y, worldZ) >= p) continue;
                        BlockState cur = section.getBlockState(dx, ly, dz);
                        if (cur.isAir() || isPreservedEndBlock(cur, ramp >= 1.0)) continue;
                        if (exempt.contains(dx, y, dz)) continue;   // the End sample's own blocks
                        if (cur.hasBlockEntity()) {
                            chunk.removeBlockEntity(new BlockPos(worldX, y, worldZ));
                        }
                        section.setBlockState(dx, ly, dz, AIR, false);
                        changed = true;
                    }
                }
            }
        }
        GenProfiler.add(GenProfiler.Bucket.EROSION, genT0);
        if (changed) chunk.setUnsaved(true);
        return changed;
    }
}
