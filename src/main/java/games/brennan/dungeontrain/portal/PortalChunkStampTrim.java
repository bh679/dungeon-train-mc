package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.mixin.StructureTemplateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A chunk-dimension room's template, laid straight into the chunk sections.
 *
 * <p>A generated room is stamped from its template (rows of the look's ground block, air above)
 * and then poured from its sample ({@link PortalChunkDimension}), which writes nearly every cell
 * again. Stamping that template the ordinary way cleared the box through the light engine and then
 * ran every template block through the structure processors and a {@code setBlock}: for a 3x3 room's
 * 73,728 cells that was 112 ms median in the editor and 554 ms in a ride, all in one tick. Because
 * these templates are plain rows, the stamp can write each row's state straight into the sections
 * instead, the way {@link PortalClear} empties a box. The room ends up block for block as the
 * ordinary stamp left it (ground to walk on, air above, until the pour reaches a row), and the pour
 * finds the same blocks to skip.</p>
 *
 * <p><b>Only plain rows.</b> Taken only when every row of the template is one plain vanilla block
 * with no block entity across its whole footprint, or air, and the template has no entities.
 * Anything else (a placeholder a processor resolves, a chest, a mixed row, a picture) is stamped
 * the ordinary way.</p>
 *
 * <p><b>Light.</b> A section write skips the light engine, as {@code PortalClear}'s does: cells that
 * were emitting are relit, and the section's emptiness is reported. The rooms stand in the twin
 * space under the bedrock, where there is no sky to let in; {@code PortalRoomSky} lights them on
 * the client. The pour writes its terrain through {@code setBlock}, which lights it.</p>
 */
final class PortalChunkStampTrim {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Rows per template, worked out once: the templates are loaded once and stamped many times. */
    private static final Map<StructureTemplate, BlockState[]> ROWS = new WeakHashMap<>();

    /** A template with no plain rows; null cannot be cached as a value. */
    private static final BlockState[] NONE = new BlockState[0];

    private PortalChunkStampTrim() {}

    /** A laid stamp's plan: the room's box, and the template's state per row. */
    record Plan(BoundingBox box, BlockState[] rows) {}

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
     * The plan for laying a room of {@code size} at {@code roomOrigin} from {@code template}'s rows,
     * or null to stamp it the ordinary way: no template, one of another size (a resize the author
     * has not saved), saved entities, or rows that are not plain.
     */
    static Plan plan(BlockPos roomOrigin, Vec3i size, StructureTemplate template) {
        if (template == null || !template.getSize().equals(size)) return null;
        if (!((StructureTemplateAccessor) template).dungeontrain$entityInfoList().isEmpty()) return null;
        BlockState[] rows = rowsOf(template, size);
        if (rows == null) return null;
        return new Plan(new BoundingBox(
            roomOrigin.getX(), roomOrigin.getY(), roomOrigin.getZ(),
            roomOrigin.getX() + size.getX() - 1, roomOrigin.getY() + size.getY() - 1,
            roomOrigin.getZ() + size.getZ() - 1), rows);
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
     * Write {@code plan}'s rows over its box straight into the chunk sections ({@link PortalQuietFill}).
     *
     * @return how many cells changed
     */
    static int lay(ServerLevel level, Plan plan, int roomOriginY) {
        BlockState[] rows = plan.rows();
        return PortalQuietFill.fill(level, plan.box(), y -> rows[y - roomOriginY], List.of());
    }
}
