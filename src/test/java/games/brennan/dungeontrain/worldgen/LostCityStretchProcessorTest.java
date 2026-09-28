package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LostCityStretchProcessor}: the floor band is found, repeated or removed, the roof and pad kept. Needs the bootstrap. */
final class LostCityStretchProcessorTest {

    /** Floors of period 5: a stone slab layer then four layers of glass walls; a distinct lobby and a gold roof. */
    private static final int PERIOD = 5;

    private static List<StructureBlockInfo> tower(BlockPos origin, int floors) {
        List<StructureBlockInfo> out = new ArrayList<>();
        int height = 3 + floors * PERIOD + 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 9; x++) {
                for (int z = 0; z < 9; z++) {
                    boolean wall = x == 0 || x == 8 || z == 0 || z == 8;
                    Block b;
                    if (y == 0) b = Blocks.GRASS_BLOCK;
                    else if (y < 3) b = Blocks.BRICKS;                                              // a solid plinth
                    else if (y >= height - 2) b = y == height - 1 ? Blocks.GOLD_BLOCK : Blocks.STONE;   // roof
                    else if ((y - 3) % PERIOD == 0) b = Blocks.STONE;                                   // floor slab
                    else b = wall ? Blocks.GLASS : Blocks.AIR;                                          // walls
                    out.add(new StructureBlockInfo(origin.offset(x, y, z), b.defaultBlockState(), null));
                }
            }
        }
        return out;
    }

    private static int count(List<StructureBlockInfo> list, Block block) {
        int n = 0;
        for (StructureBlockInfo i : list) if (i.state().getBlock() == block) n++;
        return n;
    }

    private static int top(List<StructureBlockInfo> list, BlockPos origin) {
        int t = 0;
        for (StructureBlockInfo i : list) t = Math.max(t, i.pos().getY() - origin.getY());
        return t;
    }

    @Test
    @DisplayName("taller: the floor band repeats, the roof rises by whole floors, the lobby and pad stay put")
    void taller() {
        LostCityStretchProcessor p = new LostCityStretchProcessor(3, 3, 3, 12, 0.85F);
        BlockPos origin = new BlockPos(64, 70, 64);
        List<StructureBlockInfo> before = tower(origin, 6);
        List<StructureBlockInfo> after = p.stretch(origin, before);
        assertEquals(top(before, origin) + 3 * PERIOD, top(after, origin));
        assertEquals(count(before, Blocks.STONE) + 3 * 81, count(after, Blocks.STONE));
        assertEquals(count(before, Blocks.GOLD_BLOCK), count(after, Blocks.GOLD_BLOCK));
        assertEquals(count(before, Blocks.BRICKS), count(after, Blocks.BRICKS));
        assertEquals(81, count(after, Blocks.GRASS_BLOCK));
        int goldY = -1;
        for (StructureBlockInfo i : after) if (i.state().getBlock() == Blocks.GOLD_BLOCK) goldY = i.pos().getY() - origin.getY();
        assertEquals(top(after, origin), goldY, "the roof is still on top");
        assertEquals(after, p.stretch(origin, tower(origin, 6)), "deterministic");
    }

    @Test
    @DisplayName("shorter: floors are removed, never below one floor of the band")
    void shorter() {
        LostCityStretchProcessor p = new LostCityStretchProcessor(-2, -2, 3, 12, 0.85F);
        BlockPos origin = new BlockPos(0, 64, 0);
        List<StructureBlockInfo> before = tower(origin, 6);
        List<StructureBlockInfo> after = p.stretch(origin, before);
        assertEquals(top(before, origin) - 2 * PERIOD, top(after, origin));
        assertEquals(count(before, Blocks.GOLD_BLOCK), count(after, Blocks.GOLD_BLOCK));
        assertEquals(count(before, Blocks.BRICKS), count(after, Blocks.BRICKS));
        LostCityStretchProcessor greedy = new LostCityStretchProcessor(-16, -16, 3, 12, 0.85F);
        List<StructureBlockInfo> clamped = greedy.stretch(origin, tower(origin, 4));
        assertTrue(top(clamped, origin) >= 3 + PERIOD + 1, "one floor of the band is kept");
    }

    @Test
    @DisplayName("a change is always a whole number of floors, and a building with no repeat is left alone")
    void band() {
        LostCityStretchProcessor p = new LostCityStretchProcessor(1, 2, 3, 12, 0.85F);
        BlockPos origin = BlockPos.ZERO;
        List<StructureBlockInfo> before = tower(origin, 6);
        List<StructureBlockInfo> after = p.stretch(origin, before);
        assertEquals(0, (top(after, origin) - top(before, origin)) % PERIOD);
        assertTrue(top(after, origin) > top(before, origin));
        List<StructureBlockInfo> cone = new ArrayList<>();          // every layer differs from the one five above
        for (int y = 0; y < 40; y++) {
            int r = 20 - y / 2;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    cone.add(new StructureBlockInfo(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), null));
                }
            }
        }
        assertEquals(cone, p.stretch(origin, cone));
    }
}
