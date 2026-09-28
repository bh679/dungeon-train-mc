package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Optional;

/**
 * Two read-only reports for proving the one-resident-category rule from a shell — what the status
 * HUD would say at a player's position, and what is standing in the plot layer.
 *
 * <p>Both exist because the HUD packet is the only other witness and it never reaches a log. The
 * layer count is the same walk {@link EditorLayerSweep} does, counting instead of erasing: blocks
 * in loaded chunks inside the resident category's plots and, separately, outside every one of
 * them. After a category switch settles, the second number should be zero.</p>
 */
public final class EditorLayerDebug {

    private EditorLayerDebug() {}

    /** The line the status HUD is built from, for {@code player}'s position. */
    public static String locateLine(ServerPlayer player, CarriageDims dims) {
        Optional<EditorCategory.Located> located = EditorCategory.locate(player, dims);
        String resident = EditorStampedCategoryState.current().map(EditorCategory::id).orElse("none");
        BlockPos pos = player.blockPosition();
        String where = located
            .map(l -> l.category().displayName() + " / " + l.model().displayName() + " (" + l.model().id() + ")")
            .orElse("outside every plot");
        return "[DungeonTrain] editor-locate at " + pos.toShortString() + ": " + where + " — resident=" + resident;
    }

    /** Blocks inside / outside the resident category's plots, over loaded chunks of the plot layer. */
    public static String layerLine(ServerLevel overworld, CarriageDims dims) {
        Optional<EditorCategory> resident = EditorStampedCategoryState.current();
        List<BoundingBox> plots = resident
            .map(c -> EditorLayerSweep.plotBoxes(overworld, c, dims))
            .orElse(List.of());
        BoundingBox region = EditorLayerSweep.layerRegion(overworld, dims);
        long inside = 0;
        long outside = 0;
        java.util.List<String> samples = new java.util.ArrayList<>();
        int chunks = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int cx = region.minX() >> 4; cx <= region.maxX() >> 4; cx++) {
            for (int cz = region.minZ() >> 4; cz <= region.maxZ() >> 4; cz++) {
                LevelChunk chunk = overworld.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                chunks++;
                for (int sy = region.minY() >> 4; sy <= region.maxY() >> 4; sy++) {
                    int index = chunk.getSectionIndex(sy << 4);
                    if (index < 0 || index >= chunk.getSectionsCount()) continue;
                    if (chunk.getSection(index).hasOnlyAir()) continue;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                int wx = (cx << 4) + x, wy = (sy << 4) + y, wz = (cz << 4) + z;
                                if (!region.isInside(wx, wy, wz)) continue;
                                cursor.set(wx, wy, wz);
                                if (chunk.getBlockState(cursor).isAir()) continue;
                                if (inAny(plots, wx, wy, wz)) {
                                    inside++;
                                } else {
                                    outside++;
                                    if (samples.size() < 12) {
                                        samples.add(wx + "," + wy + "," + wz + "=" + chunk.getBlockState(cursor).getBlock().getDescriptionId());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return "[DungeonTrain] editor-layer resident=" + resident.map(EditorCategory::id).orElse("none")
            + " plots=" + plots.size() + " loadedChunks=" + chunks
            + " blocksInsidePlots=" + inside + " blocksOutsidePlots=" + outside
            + " region=" + region.minX() + ".." + region.maxX() + " x " + region.minZ() + ".." + region.maxZ()
            + " stampedFlag=" + DungeonTrainWorldData.get(overworld).editorPlotsStamped()
            + (samples.isEmpty() ? "" : " outsideSamples=" + samples);
    }

    private static boolean inAny(List<BoundingBox> boxes, int x, int y, int z) {
        for (BoundingBox b : boxes) {
            if (b.isInside(x, y, z)) return true;
        }
        return false;
    }
}
