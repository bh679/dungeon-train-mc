package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LostCityBiteProcessor}: chunks removed from the shell, rubble heaped beneath, the pad kept. Needs the bootstrap. */
final class LostCityBiteProcessorTest {

    private static final List<Block> RUBBLE = List.of(Blocks.COBBLESTONE, Blocks.GRAVEL);
    private static final LostCityBiteProcessor P = new LostCityBiteProcessor(1, 2, 0.35F, 0.6F, RUBBLE, 0.8F, 10);

    /** A hollow 21×H×21 stone shell on a 25×25 grass pad (two blocks of pavement around), world coordinates. */
    private static List<StructureBlockInfo> tower(BlockPos origin, int height) {
        List<StructureBlockInfo> out = new ArrayList<>();
        for (int x = 0; x < 25; x++) {
            for (int z = 0; z < 25; z++) {
                out.add(new StructureBlockInfo(origin.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), null));
            }
        }
        for (int y = 1; y < height; y++) {
            for (int x = 2; x < 23; x++) {
                for (int z = 2; z < 23; z++) {
                    boolean shell = x == 2 || x == 22 || z == 2 || z == 22 || y % 6 == 0;
                    Block b = shell ? Blocks.STONE : Blocks.AIR;
                    out.add(new StructureBlockInfo(origin.offset(x, y, z), b.defaultBlockState(), null));
                }
            }
        }
        return out;
    }

    private static Set<BlockPos> positions(List<StructureBlockInfo> list, Block block) {
        Set<BlockPos> out = new HashSet<>();
        for (StructureBlockInfo i : list) if (i.state().getBlock() == block) out.add(i.pos());
        return out;
    }

    @Test
    @DisplayName("a big chunk of the shell goes, rubble appears on the pad and floors beneath, the pad stays whole")
    void bites() {
        BlockPos origin = new BlockPos(500, 70, -300);
        List<StructureBlockInfo> before = tower(origin, 60);
        List<StructureBlockInfo> after = P.bite(origin, before);
        Set<BlockPos> stoneBefore = positions(before, Blocks.STONE);
        Set<BlockPos> stoneAfter = positions(after, Blocks.STONE);
        int removed = stoneBefore.size() - stoneAfter.size();
        assertTrue(removed > 300, "a huge chunk: " + removed + " of " + stoneBefore.size());
        assertTrue(stoneAfter.size() > stoneBefore.size() / 2, "most of the tower stands");
        assertEquals(25 * 25, positions(after, Blocks.GRASS_BLOCK).size(), "the pad is untouched");
        Set<BlockPos> rubble = new HashSet<>(positions(after, Blocks.COBBLESTONE));
        rubble.addAll(positions(after, Blocks.GRAVEL));
        assertTrue(rubble.size() > 40, "piles of rubble: " + rubble.size());
        for (BlockPos r : rubble) {
            assertFalse(stoneAfter.contains(r), "rubble never overwrites the building");
            assertTrue(r.getY() > origin.getY(), "rubble sits above the pad");
            BlockPos below = r.below();
            assertTrue(stoneAfter.contains(below) || rubble.contains(below) || below.getY() == origin.getY(),
                    "rubble rests on a floor, the pad or more rubble: " + r);
        }
        int onPad = 0;
        for (BlockPos r : rubble) if (r.getY() == origin.getY() + 1) onPad++;
        assertTrue(onPad > 10, "some of it lands on the ground: " + onPad);
        assertEquals(after, P.bite(origin, tower(origin, 60)), "deterministic");
    }

    @Test
    @DisplayName("a low piece is left alone")
    void tooLow() {
        BlockPos origin = new BlockPos(0, 64, 0);
        List<StructureBlockInfo> flat = tower(origin, 3);
        assertEquals(flat, P.bite(origin, flat));
    }
}
