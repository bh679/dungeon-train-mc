package games.brennan.dungeontrain.portal;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The box a generated room's stamp leaves to its pour: {minX, minY, minZ, maxX, maxY, maxZ}. */
class PortalChunkStampTrimTest {

    private static final Vec3i GRID = new Vec3i(48, 32, 48);
    private static final Vec3i ONE = new Vec3i(16, 32, 16);
    private static final Vec3i SMALL = new Vec3i(4, 6, 4);

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A template like make-chunk-area-templates.py writes: ground rows, then some air, then nothing. */
    private static List<StructureTemplate.StructureBlockInfo> ground(BlockState ground, int groundRows, int airRows) {
        List<StructureTemplate.StructureBlockInfo> blocks = new ArrayList<>();
        for (int y = 0; y < groundRows + airRows; y++) {
            BlockState state = y < groundRows ? ground : Blocks.AIR.defaultBlockState();
            for (int x = 0; x < SMALL.getX(); x++) {
                for (int z = 0; z < SMALL.getZ(); z++) {
                    // The air rows are captured only partly, as a saved template's are.
                    if (y >= groundRows && (x + z) % 3 == 0) continue;
                    blocks.add(new StructureTemplate.StructureBlockInfo(new BlockPos(x, y, z), state, null));
                }
            }
        }
        return blocks;
    }

    @Test
    @DisplayName("a chunk template's ground and air rows read back one state per row")
    void plainGroundRows() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState[] rows = PortalChunkStampTrim.plainRows(ground(stone, 2, 2), SMALL);
        assertArrayEquals(new BlockState[] {stone, stone, air, air, air, air}, rows);
    }

    @Test
    @DisplayName("a mixed row stamps the room whole")
    void mixedRow() {
        List<StructureTemplate.StructureBlockInfo> blocks = ground(Blocks.STONE.defaultBlockState(), 2, 0);
        blocks.set(0, new StructureTemplate.StructureBlockInfo(BlockPos.ZERO, Blocks.DIRT.defaultBlockState(), null));
        assertNull(PortalChunkStampTrim.plainRows(blocks, SMALL));
    }

    @Test
    @DisplayName("a partly filled solid row stamps the room whole")
    void partSolidRow() {
        List<StructureTemplate.StructureBlockInfo> blocks = ground(Blocks.STONE.defaultBlockState(), 2, 0);
        blocks.remove(blocks.size() - 1);
        assertNull(PortalChunkStampTrim.plainRows(blocks, SMALL));
    }

    @Test
    @DisplayName("a block entity stamps the room whole")
    void blockEntity() {
        List<StructureTemplate.StructureBlockInfo> blocks = ground(Blocks.STONE.defaultBlockState(), 1, 0);
        blocks.add(new StructureTemplate.StructureBlockInfo(new BlockPos(0, 1, 0), Blocks.CHEST.defaultBlockState(), null));
        assertNull(PortalChunkStampTrim.plainRows(blocks, SMALL));
    }

    @Test
    @DisplayName("an empty template is air throughout")
    void emptyTemplate() {
        BlockState[] rows = PortalChunkStampTrim.plainRows(List.of(), SMALL);
        for (BlockState row : rows) assertSame(Blocks.AIR.defaultBlockState(), row);
    }

    @Test
    @DisplayName("a 3x3 room with no shift lays everything but its end columns cheaply")
    void unshiftedGrid() {
        assertArrayEquals(new int[] {1, 0, 0, 46, 31, 47}, PortalChunkStampTrim.localBox(GRID, 48, 32, 0));
    }

    @Test
    @DisplayName("a one-chunk room is trimmed the same way")
    void unshiftedOneChunk() {
        assertArrayEquals(new int[] {1, 0, 0, 14, 31, 15}, PortalChunkStampTrim.localBox(ONE, 16, 32, 0));
    }

    @Test
    @DisplayName("a positive shift reads past the slice's top, so the top rows stay with the template")
    void positiveShift() {
        // Room row y reads slice row y + 3: rows 29..31 fall outside a 32-row slice.
        assertArrayEquals(new int[] {1, 0, 0, 46, 28, 47}, PortalChunkStampTrim.localBox(GRID, 48, 32, 3));
    }

    @Test
    @DisplayName("a negative shift reads below the slice, so the bottom rows stay with the template")
    void negativeShift() {
        assertArrayEquals(new int[] {1, 2, 0, 46, 31, 47}, PortalChunkStampTrim.localBox(GRID, 48, 32, -2));
    }

    @Test
    @DisplayName("a box wider than its slice is trimmed only where the slice reaches")
    void boxWiderThanSlice() {
        assertArrayEquals(new int[] {1, 0, 0, 14, 31, 15},
            PortalChunkStampTrim.localBox(new Vec3i(20, 32, 20), 16, 32, 0));
    }

    @Test
    @DisplayName("a held-down room is trimmed to its own height")
    void heldDownRoom() {
        assertArrayEquals(new int[] {1, 0, 0, 46, 23, 47},
            PortalChunkStampTrim.localBox(new Vec3i(48, 24, 48), 48, 32, 0));
    }

    @Test
    @DisplayName("nothing to trim gives null, so the room is stamped whole")
    void empty() {
        assertNull(PortalChunkStampTrim.localBox(GRID, 48, 32, 32));
        assertNull(PortalChunkStampTrim.localBox(GRID, 48, 32, -32));
        assertNull(PortalChunkStampTrim.localBox(new Vec3i(2, 32, 48), 48, 32, 0));
    }
}
