package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.mixin.StructureTemplateAccessor;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The part of a chunk-dimension room's box that its pour will write, laid cheaply.
 *
 * <p>A generated room is stamped from its template (rows of the look's ground block, air above)
 * and then poured from its sample ({@link PortalChunkDimension}), which writes every cell the sample
 * reaches. Laying that template through the light engine, after clearing the box the same way, put
 * a 3x3 room's 73,728 cells through two {@code setBlock}s each in the stamp's own tick: 112 ms
 * median in the editor, 554 ms in a ride. So over the pour's box the stamp writes the template's
 * rows straight into the chunk sections instead, the way {@link PortalClear} empties a box, and the
 * template proper is laid only around it. The room stands exactly as it did before the pour reaches
 * a row (ground to walk on, air above), and the pour finds the same blocks to skip.</p>
 *
 * <p><b>Why a box.</b> A slice holds a state for every cell inside its bounds (air where the cube
 * ran out), so the cells a pour writes are exactly the room cells whose shifted row falls inside
 * the slice: one box. The box leaves out the two end columns (room-local X {@code 0} and
 * {@code size.x - 1}), which the corridors' mouth seals copy while they are stamped, before any
 * pour ({@code PortalCarriageBuilder#sealFillFor}); those keep the template stamp itself.</p>
 *
 * <p><b>Only plain rows.</b> The fast path writes one state per row, so it is taken only when every
 * row of the template is a single plain vanilla block with no block entity (or empty). Anything
 * else (a placeholder a processor resolves, a chest, a mixed row) stamps the room whole, as before.</p>
 */
final class PortalChunkStampTrim {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Rows per template, worked out once: the templates are loaded once and stamped many times. */
    private static final Map<StructureTemplate, BlockState[]> ROWS = new WeakHashMap<>();

    /** A template with no plain rows; null cannot be cached as a value. */
    private static final BlockState[] NONE = new BlockState[0];

    private PortalChunkStampTrim() {}

    /** A trimmed stamp's plan: the world box left to the pour, and the template's state per row. */
    record Plan(BoundingBox box, BlockState[] rows) {}

    /**
     * The room-local box {@code {minX, minY, minZ, maxX, maxY, maxZ}} the pour writes and the stamp
     * may lay cheaply, or null when there is none.
     *
     * <p>Pure geometry, so it is unit-tested without a level. The pour reads the slice at row
     * {@code y + shift} for room row {@code y} ({@code PortalChunkDimension#writeStrips}).</p>
     */
    static int[] localBox(Vec3i size, int sliceWidth, int sliceHeight, int shift) {
        int maxX = Math.min(size.getX(), sliceWidth) - 2;
        int maxZ = Math.min(size.getZ(), sliceWidth) - 1;
        int minY = Math.max(0, -shift);
        int maxY = Math.min(size.getY(), sliceHeight - shift) - 1;
        if (maxX < 1 || maxZ < 0 || maxY < minY) return null;
        return new int[] {1, minY, 0, maxX, maxY, maxZ};
    }

    /**
     * What a template holds in each row when every row is one plain state, filled across its whole
     * footprint, or empty (air, which is what the box's clear left there); null otherwise.
     *
     * <p>Pure, so it is unit-tested without a level.</p>
     */
    static BlockState[] plainRows(List<StructureTemplate.StructureBlockInfo> blocks, Vec3i size) {
        int height = size.getY();
        BlockState[] rows = new BlockState[height];
        int[] counts = new int[height];
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            int y = info.pos().getY();
            if (y < 0 || y >= height) return null;
            if (!plain(info)) return null;
            if (rows[y] == null) {
                rows[y] = info.state();
            } else if (rows[y] != info.state()) {
                return null;
            }
            counts[y]++;
        }
        int footprint = size.getX() * size.getZ();
        for (int y = 0; y < height; y++) {
            if (rows[y] == null) {
                rows[y] = AIR;
            } else if (counts[y] != footprint && !rows[y].isAir()) {
                // A partly filled row: the gaps are cleared, the rest is not, so it is not one state.
                return null;
            }
        }
        return rows;
    }

    private static boolean plain(StructureTemplate.StructureBlockInfo info) {
        BlockState state = info.state();
        if (info.nbt() != null || state.hasBlockEntity()) return false;
        // DT's own blocks are placeholders and markers a processor resolves; vanilla ones stand as they are.
        return "minecraft".equals(state.getBlock().builtInRegistryHolder().key().location().getNamespace());
    }

    /**
     * The plan for a trimmed stamp, or null to stamp the whole room: no slice, nothing to trim, a
     * corridor reaching into the box (the pour skips corridor cells, so the stamp must not), or a
     * template whose rows are not plain.
     */
    static Plan plan(BlockPos roomOrigin, Vec3i size, PortalChunkSlice slice, int shift,
                     PortalCorridorMask corridors, StructureTemplate template) {
        if (slice == null || template == null || !template.getSize().equals(size)) return null;
        int[] b = localBox(size, slice.width(), slice.height(), shift);
        if (b == null) return null;
        BlockState[] rows = rowsOf(template, size);
        if (rows == null) return null;
        BoundingBox box = new BoundingBox(
            roomOrigin.getX() + b[0], roomOrigin.getY() + b[1], roomOrigin.getZ() + b[2],
            roomOrigin.getX() + b[3], roomOrigin.getY() + b[4], roomOrigin.getZ() + b[5]);
        return corridors.intersects(box) ? null : new Plan(box, rows);
    }

    private static BlockState[] rowsOf(StructureTemplate template, Vec3i size) {
        BlockState[] rows = ROWS.computeIfAbsent(template, t -> {
            List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) t).dungeontrain$palettes();
            if (palettes.size() != 1) return NONE;
            BlockState[] plain = plainRows(palettes.get(0).blocks(), size);
            return plain == null ? NONE : plain;
        });
        return rows == NONE ? null : rows;
    }

    /**
     * Write {@code plan}'s rows over its box, chunk section by section: no light engine, no
     * neighbour updates, no drops — {@link PortalClear#clearBox}'s write, with the row's state for
     * air. A block entity is evicted before its block goes; a cell that was emitting light is relit;
     * a column that is not loaded takes the silent per-cell path, as {@code PortalClear} does.
     *
     * @return how many cells changed
     */
    static int lay(ServerLevel level, Plan plan, int roomOriginY) {
        int[] changed = {0};
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<BlockPos> emitters = new ArrayList<>();
        PortalClear.forEachSpan(plan.box(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1,
            (minX, minY, minZ, maxX, maxY, maxZ) -> {
                LevelChunk chunk = level.getChunkSource().getChunkNow(minX >> 4, minZ >> 4);
                if (chunk == null) {
                    changed[0] += laySlow(level, pos, plan.rows(), roomOriginY, minX, minY, minZ, maxX, maxY, maxZ);
                    return;
                }
                int sIdx = chunk.getSectionIndex(minY);
                if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) return;
                LevelChunkSection section = chunk.getSection(sIdx);
                int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                int count = 0;
                for (int y = minY; y <= maxY; y++) {
                    BlockState row = plan.rows()[y - roomOriginY];
                    if (row.isAir() && section.hasOnlyAir()) continue;
                    for (int x = minX; x <= maxX; x++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            BlockState state = section.getBlockState(x & 15, y - baseY, z & 15);
                            if (state == row) continue;
                            pos.set(x, y, z);
                            if (state.hasBlockEntity()) SilentBlockOps.evictBlockEntity(chunk, pos);
                            if (state.getLightEmission() > 0) emitters.add(pos.immutable());
                            section.setBlockState(x & 15, y - baseY, z & 15, row, false);
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
        for (BlockPos emitter : emitters) {
            level.getChunkSource().getLightEngine().checkBlock(emitter);
        }
        return changed[0];
    }

    /** {@link #lay} for a column that is not loaded: correct and slow, as {@code PortalClear}'s fallback. */
    private static int laySlow(ServerLevel level, BlockPos.MutableBlockPos pos, BlockState[] rows, int roomOriginY,
                               int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int count = 0;
        for (int y = minY; y <= maxY; y++) {
            BlockState row = rows[y - roomOriginY];
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
