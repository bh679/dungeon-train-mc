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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LostCityFacadeProcessor}: cornices on floor layers, pilasters up the walls, only on the outside. Needs the bootstrap. */
final class LostCityFacadeProcessorTest {

    /** A hollow 11×H×11 stone shell (floor slab every 4 layers) on a 15×15 pad, with an inner 3×3 courtyard. */
    private static List<StructureBlockInfo> tower(BlockPos origin, int height) {
        List<StructureBlockInfo> out = new ArrayList<>();
        for (int x = 0; x < 15; x++) {
            for (int z = 0; z < 15; z++) {
                out.add(new StructureBlockInfo(origin.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), null));
            }
        }
        for (int y = 1; y < height; y++) {
            for (int x = 2; x < 13; x++) {
                for (int z = 2; z < 13; z++) {
                    boolean shell = x == 2 || x == 12 || z == 2 || z == 12;
                    boolean court = x >= 6 && x <= 8 && z >= 6 && z <= 8;
                    boolean slab = y % 4 == 0 && !court;
                    Block b = shell || slab ? Blocks.STONE : Blocks.AIR;
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
    @DisplayName("a cornice rings every floor layer one block outside the shell, and never the courtyard or the pad")
    void ledges() {
        BlockPos origin = new BlockPos(100, 64, 100);
        LostCityFacadeProcessor p = new LostCityFacadeProcessor(Optional.of(Blocks.STONE_SLAB), Optional.empty(), 6, 2, 0.6F);
        List<StructureBlockInfo> tower = tower(origin, 17);
        List<StructureBlockInfo> added = p.dress(origin, tower);
        Set<BlockPos> ledges = positions(added, Blocks.STONE_SLAB);
        assertEquals(added.size(), ledges.size());
        for (BlockPos l : ledges) {
            int x = l.getX() - origin.getX(), y = l.getY() - origin.getY(), z = l.getZ() - origin.getZ();
            assertTrue(y % 4 == 0 && y >= 4, "on a floor layer: " + l);
            assertTrue(x == 1 || x == 13 || z == 1 || z == 13, "one block outside the shell: " + l);
            assertFalse(x >= 5 && x <= 9 && z >= 5 && z <= 9, "never in the courtyard");
        }
        assertEquals(4 * 11 * 4, ledges.size(), "a full ring of 44 on each of the four floor layers");
    }

    @Test
    @DisplayName("pilasters stand every N blocks along exterior walls, above the plinth, and skip taken cells")
    void pilasters() {
        BlockPos origin = new BlockPos(0, 64, 0);
        LostCityFacadeProcessor p = new LostCityFacadeProcessor(Optional.empty(), Optional.of(Blocks.STONE_BRICKS), 5, 2, 0.6F);
        List<StructureBlockInfo> tower = tower(origin, 13);
        List<StructureBlockInfo> added = p.dress(origin, tower);
        Set<BlockPos> piers = positions(added, Blocks.STONE_BRICKS);
        assertTrue(piers.size() > 20, "some pilasters: " + piers.size());
        for (BlockPos q : piers) {
            int x = q.getX(), y = q.getY() - origin.getY(), z = q.getZ();
            assertTrue(y >= 2, "above the plinth, running through slab layers as one column: " + q);
            boolean westEast = x == 1 || x == 13;
            assertTrue(westEast ? z % 5 == 0 : x % 5 == 0, "every 5 along the wall: " + q);
        }
        // nothing added where the template already has a block
        List<StructureBlockInfo> withVines = new ArrayList<>(tower);
        withVines.add(new StructureBlockInfo(origin.offset(1, 5, 5), Blocks.VINE.defaultBlockState(), null));
        for (StructureBlockInfo i : p.dress(origin, withVines)) assertFalse(i.pos().equals(origin.offset(1, 5, 5)));
        // no blocks configured: nothing
        assertTrue(new LostCityFacadeProcessor(Optional.empty(), Optional.empty(), 6, 2, 0.6F).dress(origin, tower).isEmpty());
    }
}
