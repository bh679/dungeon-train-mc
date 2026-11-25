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

/** Which chunk-room templates are plain rows a stamp may lay straight into the sections. */
class PortalChunkStampTrimTest {

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
}
