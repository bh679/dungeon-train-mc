package games.brennan.dungeontrain.portal;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link UnderwaterFlood}: water up to the top row whatever the sample's own sea level, dry above it. */
class UnderwaterFloodTest {

    private static final int W = 2;
    private static final int H = 8;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static BlockState[] column(BlockState... rows) {
        BlockState[] out = new BlockState[W * W * H];
        for (int y = 0; y < H; y++) {
            for (int i = 0; i < W * W; i++) out[y * W * W + i] = rows[y];
        }
        return out;
    }

    private static BlockState at(BlockState[] states, int y) {
        return states[y * W * W];
    }

    @Test
    @DisplayName("air at or under the top row fills with water; the seabed is kept")
    void floodsLowSea() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        // A shallow sea: water only to row 3, air above it.
        BlockState[] in = column(stone, water, water, water, air, air, air, air);
        BlockState[] out = UnderwaterFlood.apply(in, W, H, 5);
        assertSame(stone, at(out, 0));
        for (int y = 1; y <= 5; y++) assertSame(water, at(out, y), "row " + y);
        assertSame(air, at(out, 6));
        assertSame(air, at(out, 7));
        assertSame(air, at(in, 4), "input left as it was");
    }

    @Test
    @DisplayName("above the top row water, kelp and seagrass go and waterlogged blocks dry out")
    void drainsHighSea() {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState kelp = Blocks.KELP.defaultBlockState();
        BlockState seagrass = Blocks.SEAGRASS.defaultBlockState();
        BlockState wetSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState sand = Blocks.SAND.defaultBlockState();
        BlockState[] in = column(sand, water, water, water, water, kelp, seagrass, wetSlab);
        BlockState[] out = UnderwaterFlood.apply(in, W, H, 3);
        assertSame(water, at(out, 3));
        assertSame(Blocks.AIR.defaultBlockState(), at(out, 4));
        assertSame(Blocks.AIR.defaultBlockState(), at(out, 5));
        assertSame(Blocks.AIR.defaultBlockState(), at(out, 6));
        assertEquals(Blocks.STONE_SLAB, at(out, 7).getBlock());
        assertFalse(at(out, 7).getValue(BlockStateProperties.WATERLOGGED));
    }

    @Test
    @DisplayName("a gap of 0 floods to the very top row")
    void floodedToRoof() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState[] in = column(Blocks.GRAVEL.defaultBlockState(), air, air, air, air, air, air, air);
        BlockState[] out = UnderwaterFlood.apply(in, W, H, new UnderwaterLayout(1, 0).topWaterRow(H));
        assertSame(Blocks.WATER.defaultBlockState(), at(out, H - 1));
    }
}
