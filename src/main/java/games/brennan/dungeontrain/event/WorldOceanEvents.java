package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.OceanBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Raises the sea in the {@link OceanBand}. Forcing the ocean/beach biome (in
 * {@code MultiNoiseBiomeSourceMixin}) only recolours the terrain — the underlying overworld noise still
 * produces whatever land the world-X happens to sit on — so this pass rebuilds every in-band chunk into an
 * open sea whose surface sits at the train track bed height ({@link OceanBand#waterSurfaceY}, well above
 * vanilla sea level 63):
 *
 * <ul>
 *   <li><b>Open-water columns</b> — any natural land at/above the waterline is erased, and water is placed
 *       from a flat sealed seabed up to just below the bed, giving a uniform-depth sea.</li>
 *   <li><b>Island chunks</b> — a sparse minority; a small radial sand mound is stamped rising from the
 *       seabed to a few blocks above the waterline (a beach-biome island).</li>
 * </ul>
 *
 * <p>The corridor Z-span (tunnel wall to wall) is skipped entirely, leaving a dry channel for the train —
 * kept dry by construction (the waterline sits at {@code bedY}, so it can never rise into the ride space at
 * {@code bedY+1}) and defended by {@code FlowingFluidOceanMixin}. Runs on {@link ChunkEvent.Load} gated on
 * {@link ChunkEvent.Load#isNewChunk()} — once at generation, never on reload (player builds survive), after
 * all decoration. Writes go through raw {@link LevelChunkSection#setBlockState} with no relight — the
 * Sable-safe, no-cascade path {@code WorldChuncksEvents} uses.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldOceanEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    /** Depth (blocks) of the raised sea below the waterline — a flat, sealed seabed sits this far down. */
    private static final int OCEAN_DEPTH = 16;
    /** Thickness of the solid seabed seal placed under the water so it can never leak downward. */
    private static final int SEABED_SEAL = 3;
    /** Island dome radius (blocks) and peak height above the waterline; islands are sparse per {@code oceanIslandDensity}. */
    private static final double ISLAND_RADIUS = 6.0;
    private static final int ISLAND_MAX_HEIGHT = 5;

    private WorldOceanEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (OceanBand.startX(level) == OceanBand.OFF) return; // band disabled / no train

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        int chunkMinZ = pos.getMinBlockZ();

        // Only chunks whose columns lie in the band participate. The band has hard X edges; a boundary
        // chunk that straddles the edge is handled per-column below (out-of-band columns are left natural).
        if (!OceanBand.isInBand(level, chunkMinX) && !OceanBand.isInBand(level, chunkMinX + 15)) return;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
        int surface = g.bedY();                       // waterline == track bed height
        int seabedTop = surface - OCEAN_DEPTH;        // flat seabed (bottom water block)
        // Preserve the corridor footprint (bed, rails, pillars, tunnel) wall to wall — a dry channel.
        TunnelGeometry tg = TunnelGeometry.from(g);
        int preserveZMin = tg.wallMinZ();
        int preserveZMax = tg.wallMaxZ();

        boolean islandChunk = OceanBand.isIslandChunk(level, pos.x, pos.z);
        // Per-column island top (Integer.MIN_VALUE = open water); computed once for the chunk.
        int[] islandTop = islandChunk ? islandTops(surface) : null;

        int minBuild = level.getMinBuildHeight();
        int maxBuild = level.getMaxBuildHeight() - 1;
        boolean changed = false;

        long genT0 = GenProfiler.t0();
        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            // Below the sealed seabed → nothing to do; above the waterline with an all-air section → already
            // clear (no land to erase). Both cases skip. Otherwise the section may need water / a mound /
            // land removal, so it is processed even when currently all air (water is added into air).
            if (baseY + 15 < seabedTop - SEABED_SEAL) continue;
            if (section.hasOnlyAir() && baseY >= surface) continue;

            for (int dx = 0; dx < 16; dx++) {
                int worldX = chunkMinX + dx;
                if (!OceanBand.isInBand(level, worldX)) continue;     // out-of-band column (edge chunk) → natural
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    if (worldZ >= preserveZMin && worldZ <= preserveZMax) continue; // dry corridor channel
                    int colTop = islandTop == null ? Integer.MIN_VALUE : islandTop[(dx << 4) | dz];
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y < minBuild || y > maxBuild) continue;
                        BlockState target = targetState(y, surface, seabedTop, colTop);
                        if (target == null) continue;                 // leave natural
                        BlockState cur = section.getBlockState(dx, ly, dz);
                        if (cur == target || (target == AIR && cur.isAir())) continue;
                        if (target == WATER && cur.getFluidState().isSourceOfType(Fluids.WATER)) continue;
                        if (cur.hasBlockEntity()) chunk.removeBlockEntity(new BlockPos(worldX, y, worldZ));
                        section.setBlockState(dx, ly, dz, target, false);
                        changed = true;
                    }
                }
            }
        }
        GenProfiler.add(GenProfiler.Bucket.OCEAN_FILL, genT0);
        if (changed) chunk.setUnsaved(true);
    }

    /**
     * The block a cell should become, or {@code null} to leave the natural block untouched.
     * {@code colTop == Integer.MIN_VALUE} is an open-water column; otherwise it is an island column whose
     * sand mound peaks at {@code colTop}.
     */
    private static BlockState targetState(int y, int surface, int seabedTop, int colTop) {
        if (colTop != Integer.MIN_VALUE) {                            // island column
            if (y > colTop) return AIR;                               // clear anything above the mound
            if (y >= seabedTop) return (y >= colTop - 2) ? SAND : STONE; // sandy-topped stone mound
            if (y >= seabedTop - SEABED_SEAL) return SAND;            // sealed seabed under the mound
            return null;
        }
        // open-water column
        if (y >= surface) return AIR;                                 // erase land at/above the waterline
        if (y >= seabedTop) return WATER;                             // raised sea
        if (y >= seabedTop - SEABED_SEAL) return SAND;               // sealed flat seabed
        return null;
    }

    /** Per-column island-top Y for an island chunk: a radial mound peaking {@code ISLAND_MAX_HEIGHT} above the waterline. */
    private static int[] islandTops(int surface) {
        int[] tops = new int[256];
        for (int dx = 0; dx < 16; dx++) {
            double ddx = (dx + 0.5) - 8.0;
            for (int dz = 0; dz < 16; dz++) {
                double ddz = (dz + 0.5) - 8.0;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d > ISLAND_RADIUS) {
                    tops[(dx << 4) | dz] = Integer.MIN_VALUE;         // open water around the mound
                } else {
                    int h = (int) Math.round((1.0 - d / ISLAND_RADIUS) * ISLAND_MAX_HEIGHT);
                    tops[(dx << 4) | dz] = (surface - 1) + h;         // land from the seabed up to here
                }
            }
        }
        return tops;
    }
}
