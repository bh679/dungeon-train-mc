package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LostCityTruncateProcessor}: the cut height, the jagged band, the rubble crown, the pad. Needs the bootstrap. */
final class LostCityTruncateProcessorTest {

    private static final List<Block> RUBBLE = List.of(Blocks.COBBLESTONE, Blocks.GRAVEL);
    private static final LostCityTruncateProcessor P = new LostCityTruncateProcessor(0.4F, 0.7F, 3, RUBBLE);

    /** A solid 5×H×5 column of stone on a grass pad, in world coordinates from {@code origin}. */
    private static List<StructureBlockInfo> tower(BlockPos origin, int height) {
        List<StructureBlockInfo> out = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    Block b = y == 0 ? Blocks.GRASS_BLOCK : Blocks.STONE;
                    out.add(new StructureBlockInfo(origin.offset(x, y, z), b.defaultBlockState(), null));
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("the cut lands between the fractions of the height, the same for every block of a placement")
    void cutHeight() {
        for (int i = 0; i < 50; i++) {
            BlockPos origin = new BlockPos(i * 97, 60, -i * 31);
            int cut = P.cutHeight(origin, 100);
            assertTrue(cut >= 39 && cut <= 70, "cut " + cut);
            assertEquals(cut, P.cutHeight(origin, 100));
        }
        assertEquals(1, new LostCityTruncateProcessor(0.0F, 0.0F, 0, RUBBLE).cutHeight(BlockPos.ZERO, 50));
        assertEquals(49, new LostCityTruncateProcessor(1.0F, 1.0F, 0, RUBBLE).cutHeight(BlockPos.ZERO, 50));
    }

    @Test
    @DisplayName("nothing above cut + jagged, everything below, rubble on the cut layer, the pad untouched")
    void truncate() {
        BlockPos origin = new BlockPos(1000, 64, 2000);
        int height = 60;
        List<StructureBlockInfo> kept = P.truncate(origin, tower(origin, height));
        int cut = P.cutHeight(origin, height);
        int top = 0;
        int pad = 0;
        int rubble = 0;
        int cutLayer = 0;
        int below = 0;
        for (StructureBlockInfo info : kept) {
            int y = info.pos().getY() - origin.getY();
            top = Math.max(top, y);
            if (y == 0) {
                pad++;
                assertEquals(Blocks.GRASS_BLOCK, info.state().getBlock());
            } else if (y == cut) {
                cutLayer++;
                assertTrue(Set.copyOf(RUBBLE).contains(info.state().getBlock()));
                rubble++;
            } else if (y < cut) {
                below++;
                assertEquals(Blocks.STONE, info.state().getBlock());
            }
        }
        assertTrue(top <= cut + 3, "top " + top + " cut " + cut);
        assertEquals(25, pad);
        assertEquals(25, cutLayer);
        assertEquals(25 * (cut - 1), below);
        assertEquals(25, rubble);
        int jaggedKept = kept.size() - pad - below - cutLayer;
        assertTrue(jaggedKept > 0 && jaggedKept < 75, "a broken edge, not a slice: " + jaggedKept);
        assertEquals(kept, P.truncate(origin, tower(origin, height)));   // deterministic
    }

    @Test
    @DisplayName("a piece too low to cut is returned as it is")
    void tooLow() {
        BlockPos origin = new BlockPos(0, 64, 0);
        List<StructureBlockInfo> flat = tower(origin, 2);
        assertEquals(flat, P.truncate(origin, flat));
    }
}
