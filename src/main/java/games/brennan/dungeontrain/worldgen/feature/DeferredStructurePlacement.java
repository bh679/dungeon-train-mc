package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Which chunks hold their structure pieces back until after the Nether core is stamped.
 *
 * <p>One predicate, asked by both halves of the arrangement, so they can never disagree:
 * {@code ChunkGeneratorDecorationMixin} suppresses the normal placement for these chunks, and
 * {@link NetherStructuresFeature} places them itself once {@code nether_transition} has run. A chunk
 * answering differently to the two callers would either lose its structures or write them twice.</p>
 *
 * <p>The test is "does any column of this chunk belong to the real-Nether core", widened by the band's
 * edge-wave margin so the undulating front can't put a core column just outside the range we checked. It is
 * deliberately <em>any</em> rather than <em>every</em> column: a fortress reaching into a half-core chunk
 * still needs that chunk's slice written after the fill.</p>
 *
 * <p>Structures that are not Nether ones get deferred too on such a chunk. Nothing else passes the biome
 * filter in the core in practice, and being placed on the final terrain rather than on terrain about to be
 * replaced is the more correct outcome for anything that did.</p>
 */
public final class DeferredStructurePlacement {

    private DeferredStructurePlacement() {}

    /** True if this chunk's structure pieces must wait for the core fill. Never throws. */
    public static boolean isDeferred(WorldGenLevel level, ChunkPos chunkPos) {
        try {
            ServerLevel serverLevel = level.getLevel();
            if (!serverLevel.dimension().equals(Level.OVERWORLD)) return false;
            if (!DungeonTrainCommonConfig.isNetherStructuresEnabled()) return false;

            ServerLevel overworld = serverLevel.getServer() == null ? null : serverLevel.getServer().overworld();
            if (overworld == null || NetherBand.startX(overworld) == NetherBand.OFF) return false;

            WorldGenCycle cycle = WorldGenCycle.fromConfig();
            int margin = NetherMountainTerrain.maxEdgeShift();
            for (int worldX = chunkPos.getMinBlockX() - margin; worldX <= chunkPos.getMaxBlockX() + margin; worldX++) {
                // The End band always wins any overlap, exactly as the core fill yields those columns.
                if (cycle.isNetherCore(worldX) && cycle.endMiddleRamp(worldX) <= 0.0) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;   // never block generation over this — worst case, structures place as they used to
        }
    }
}
