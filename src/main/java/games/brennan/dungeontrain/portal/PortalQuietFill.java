package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Writing a chunk room's shell straight into the chunk sections, the way {@link PortalClear}
 * empties a box: no light-engine check, no neighbour updates, no physics hook per cell.
 *
 * <p>A 3x3 chunk room's first stamp wrote some 25,000 cells through {@code setBlock(UPDATE_ALL)}:
 * the room's template, its skin, its two seal planes, end caps and corridor rings, all into basement
 * rock that nothing was standing on. Each paid for neighbour updates, a light check and the physics
 * hook. This does the same writes for a fraction of that, and keeps what those updates were for:</p>
 * <ul>
 *   <li><b>Block entities</b> are evicted before their block goes, so nothing spills.</li>
 *   <li><b>Light,</b> as {@code LevelChunk.setBlockState} keeps it: where a cell's light properties
 *       change, the column's sky source is moved and a light check is queued, and a section's
 *       emptiness is reported. The check is skipped only where it could find nothing: the sky source
 *       did not move and neither the section nor any beside it holds light (basement rock, the usual
 *       case). {@link #quietSafe} keeps glowing and see-through shell blocks on the ordinary path.</li>
 *   <li><b>Heightmaps</b> are updated per cell, so an attic room's ceiling counts for rain, snow
 *       and spawning as it did.</li>
 *   <li><b>Shapes:</b> a cell inside {@code loud} (callers pass the corridors, grown by one) is
 *       written the ordinary way, so a corridor block beside new bedrock or a new seal still gets its
 *       shape update: a pane's arm, a fence's post.</li>
 *   <li>A column that is not loaded takes the silent per-cell path, as {@code PortalClear} does.</li>
 * </ul>
 */
final class PortalQuietFill {

    private PortalQuietFill() {}

    /**
     * Whether {@code state} can be written without the light engine: no light of its own, no block
     * entity, and either opaque (so whatever light the cell held is hidden) or Forced Air (so the cell
     * keeps the light it had). Glass, an air lock or a glowing block go the ordinary way.
     */
    static boolean quietSafe(BlockState state) {
        if (state == null || state.isAir() || state.hasBlockEntity() || state.getLightEmission() > 0) return false;
        if (ModBlocks.FORCED_AIR.isBound() && state.is(ModBlocks.FORCED_AIR.get())) return true;
        return state.canOcclude();
    }

    /** {@code mask}'s boxes, each grown by one block on every side. Pure. */
    static List<BoundingBox> grown(PortalCorridorMask mask) {
        List<BoundingBox> out = new ArrayList<>(mask.boxes().size());
        for (BoundingBox box : mask.boxes()) out.add(box.inflatedBy(1));
        return out;
    }

    static boolean inAny(List<BoundingBox> boxes, int x, int y, int z) {
        for (int i = 0; i < boxes.size(); i++) {
            BoundingBox b = boxes.get(i);
            if (x >= b.minX() && x <= b.maxX() && y >= b.minY() && y <= b.maxY()
                && z >= b.minZ() && z <= b.maxZ()) return true;
        }
        return false;
    }

    /** {@link #fill(ServerLevel, BoundingBox, IntFunction, List)} with one state throughout. */
    static int fill(ServerLevel level, BoundingBox box, BlockState state, List<BoundingBox> loud) {
        return fill(level, box, y -> state, loud);
    }

    /**
     * Write {@code rowAt.apply(y)} into every cell of {@code box}, section by section.
     *
     * @return how many cells changed
     */
    static int fill(ServerLevel level, BoundingBox box, IntFunction<BlockState> rowAt, List<BoundingBox> loud) {
        int[] changed = {0};
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<BlockPos> emitters = new ArrayList<>();
        PortalClear.forEachSpan(box, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1,
            (minX, minY, minZ, maxX, maxY, maxZ) -> {
                LevelChunk chunk = level.getChunkSource().getChunkNow(minX >> 4, minZ >> 4);
                if (chunk == null) {
                    changed[0] += fillSlow(level, pos, rowAt, minX, minY, minZ, maxX, maxY, maxZ);
                    return;
                }
                int sIdx = chunk.getSectionIndex(minY);
                if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) return;
                LevelChunkSection section = chunk.getSection(sIdx);
                int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                boolean lit = lightNear(level, SectionPos.of(minX >> 4, baseY >> 4, minZ >> 4));
                int count = 0;
                for (int y = minY; y <= maxY; y++) {
                    BlockState row = rowAt.apply(y);
                    if (row.isAir() && section.hasOnlyAir()) continue;
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            BlockState state = section.getBlockState(x & 15, y - baseY, z & 15);
                            if (state == row) continue;
                            pos.set(x, y, z);
                            if (inAny(loud, x, y, z)) {
                                level.setBlock(pos, row, Block.UPDATE_ALL);
                                changed[0]++;
                                continue;
                            }
                            if (state.hasBlockEntity()) SilentBlockOps.evictBlockEntity(chunk, pos);
                            section.setBlockState(x & 15, y - baseY, z & 15, row, false);
                            if (needsLightCheck(chunk, pos, state, row, lit)) emitters.add(pos.immutable());
                            updateHeightmaps(chunk, x, y, z, row);
                            level.getChunkSource().blockChanged(pos);
                            count++;
                        }
                    }
                }
                if (count > 0) {
                    chunk.setUnsaved(true);
                    level.getChunkSource().getLightEngine().updateSectionStatus(
                        SectionPos.of(minX >> 4, baseY >> 4, minZ >> 4), section.hasOnlyAir());
                    changed[0] += count;
                }
            });
        relight(level, emitters);
        return changed[0];
    }

    /** One cell, quietly unless {@code loud} covers it — for a plane whose cells are picked one by one. */
    static void set(ServerLevel level, BlockPos pos, BlockState state, List<BoundingBox> loud) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || inAny(loud, pos.getX(), pos.getY(), pos.getZ())) {
            level.setBlock(pos, state, Block.UPDATE_ALL);
            return;
        }
        int sIdx = chunk.getSectionIndex(pos.getY());
        if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) return;
        LevelChunkSection section = chunk.getSection(sIdx);
        int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
        BlockState old = section.getBlockState(pos.getX() & 15, pos.getY() - baseY, pos.getZ() & 15);
        if (old == state) return;
        boolean wasEmpty = section.hasOnlyAir();
        if (old.hasBlockEntity()) SilentBlockOps.evictBlockEntity(chunk, pos);
        section.setBlockState(pos.getX() & 15, pos.getY() - baseY, pos.getZ() & 15, state, false);
        updateHeightmaps(chunk, pos.getX(), pos.getY(), pos.getZ(), state);
        level.getChunkSource().blockChanged(pos);
        chunk.setUnsaved(true);
        if (wasEmpty != section.hasOnlyAir()) {
            level.getChunkSource().getLightEngine().updateSectionStatus(
                SectionPos.of(pos.getX() >> 4, baseY >> 4, pos.getZ() >> 4), section.hasOnlyAir());
        }
        if (needsLightCheck(chunk, pos, old, state,
                lightNear(level, SectionPos.of(pos.getX() >> 4, baseY >> 4, pos.getZ() >> 4)))) {
            level.getChunkSource().getLightEngine().checkBlock(pos);
        }
    }

    /**
     * What {@code LevelChunk.setBlockState} does for light, after the write: where the light
     * properties changed, move the column's sky source and ask for a light check. The check is skipped
     * only where it could find nothing: the sky source did not move and neither this section nor any
     * beside it holds light, which is basement rock.
     */
    private static boolean needsLightCheck(LevelChunk chunk, BlockPos pos, BlockState old, BlockState now,
                                           boolean lightNear) {
        if (!LightEngine.hasDifferentLightProperties(chunk, pos, old, now)) return false;
        boolean skyMoved = chunk.getSkyLightSources().update(chunk, pos.getX() & 15, pos.getY(), pos.getZ() & 15);
        return skyMoved || lightNear;
    }

    /** Whether {@code section} or any of its six neighbours may hold light. */
    private static boolean lightNear(ServerLevel level, SectionPos section) {
        if (holdsLight(level, section)) return true;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            SectionPos beside = SectionPos.of(
                section.x() + d.getStepX(), section.y() + d.getStepY(), section.z() + d.getStepZ());
            if (holdsLight(level, beside)) return true;
        }
        return false;
    }

    /**
     * Whether any cell of the section may hold light, sky or block. Conservative: a sky layer the
     * engine holds no data for counts as lit, since the sky may be standing in for it.
     */
    private static boolean holdsLight(ServerLevel level, SectionPos section) {
        LevelLightEngine engine = level.getChunkSource().getLightEngine();
        DataLayer block = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(section);
        if (block != null && !block.isDefinitelyFilledWith(0)) return true;
        DataLayer sky = engine.getLayerListener(LightLayer.SKY).getDataLayerData(section);
        return sky == null || !sky.isDefinitelyFilledWith(0);
    }

    private static void updateHeightmaps(LevelChunk chunk, int x, int y, int z, BlockState state) {
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            entry.getValue().update(x & 15, y, z & 15, state);
        }
    }

    private static void relight(ServerLevel level, List<BlockPos> emitters) {
        // Cells whose light needs another look (see needsLightCheck), queued as setBlock would.
        for (BlockPos emitter : emitters) {
            level.getChunkSource().getLightEngine().checkBlock(emitter);
        }
    }

    /** {@link #fill} for a column that is not loaded: correct and slow, as {@code PortalClear}'s fallback. */
    private static int fillSlow(ServerLevel level, BlockPos.MutableBlockPos pos, IntFunction<BlockState> rowAt,
                                int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int count = 0;
        for (int y = minY; y <= maxY; y++) {
            BlockState row = rowAt.apply(y);
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos) == row) continue;
                    SilentBlockOps.setBlockSilentNoCascade(level, pos, row, null);
                    count++;
                }
            }
        }
        return count;
    }
}
