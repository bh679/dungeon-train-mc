package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OthersideSurfaceSkin} against vanilla's surface-rule semantics, with stand-in blocks and stubbed
 * randoms: the gradients' certain ends, the floor skins per biome, the echo-soil depth, and the stone-depth
 * counter's reset rules (air resets, fluid does not, bedrock counts).
 */
final class OthersideSurfaceSkinTest {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();      // stands in for sculk stone
    private static final BlockState ECHO = Blocks.DIRT.defaultBlockState();
    private static final BlockState BLOOM = Blocks.MOSS_BLOCK.defaultBlockState();
    private static final BlockState GLOOMY = Blocks.GRAY_WOOL.defaultBlockState();
    private static final BlockState GLOOMSLATE = Blocks.TUFF.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();

    private static final OthersideSurfaceSkin.Blocks4 BLOCKS = new OthersideSurfaceSkin.Blocks4(STONE, ECHO, BLOOM, GLOOMY, GLOOMSLATE);

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A column indexed by Otherside y 0..127. */
    private static final class Column implements OthersideSurfaceSkin.Column {
        final BlockState[] cells = new BlockState[128];

        Column() {
            Arrays.fill(cells, STONE);
        }

        @Override
        public BlockState get(int y) {
            return y < 0 || y > 127 ? AIR : cells[y];
        }

        @Override
        public void set(int y, BlockState state) {
            cells[y] = state;
        }
    }

    /** Gradients that never roll (nextFloat 1.0 is never below the threshold), surface depth 2. */
    private static OthersideSurfaceSkin skin(float roll, int surfaceDepth) {
        OthersideSurfaceSkin.Gradient g = (x, y, z) -> roll;
        return new OthersideSurfaceSkin(new OthersideSurfaceSkin.Randoms(g, g, g, g, (x, z) -> surfaceDepth), BLOCKS);
    }

    @Test
    @DisplayName("bedrock at the certain ends of its gradients whatever the roll")
    void gradientsCertainEnds() {
        for (float roll : new float[] {0.0f, 0.5f, 1.0f}) {
            Column c = new Column();
            assertTrue(skin(roll, 2).dress(c, 5, 7, 127, 0, OthersideSurfaceSkin.ECHOING_FOREST));
            assertEquals(BEDROCK, c.cells[0], "above_bottom 0 is always bedrock");
            assertEquals(BEDROCK, c.cells[127], "below_top 0 is always bedrock");
            assertEquals(STONE, c.cells[64], "the body stays sculk stone");
        }
    }

    @Test
    @DisplayName("a never-rolling gradient (nextFloat 1.0): floor zones stay stone, the inverted roof zones fill")
    void neverRolling() {
        Column c = new Column();
        skin(1.0f, 2).dress(c, 0, 0, 127, 0, null);
        assertEquals(BEDROCK, c.cells[0]);
        // bedrock_floor is true at y<=0, false at y>=5, a coin toss between: 1.0 never wins → y1..4 stay stone;
        // deepslate_floor (y<=0 / y>=12) the same → y1..11 stone.
        for (int y = 1; y <= 115; y++) assertEquals(STONE, c.cells[y], "y" + y);
        // The roofs are `not(vertical_gradient)`: the gradient is true at y<=122 (bedrock) / y<=115 (deepslate)
        // and false at y>=127, so a losing toss means the roof material.
        for (int y = 116; y <= 122; y++) assertEquals(Blocks.DEEPSLATE, c.cells[y].getBlock(), "deepslate roof y" + y);
        for (int y = 123; y <= 127; y++) assertEquals(BEDROCK, c.cells[y], "bedrock roof y" + y);
    }

    @Test
    @DisplayName("an always-rolling gradient (nextFloat 0.0): floor zones fill, the inverted roof zones stay stone")
    void alwaysRolling() {
        Column c = new Column();
        skin(0.0f, 2).dress(c, 0, 0, 127, 0, null);
        for (int y = 0; y <= 4; y++) assertEquals(BEDROCK, c.cells[y], "bedrock floor y" + y);
        for (int y = 5; y <= 11; y++) assertEquals(Blocks.DEEPSLATE, c.cells[y].getBlock(), "deepslate floor y" + y);
        // y126 sits under the roof bedrock, which counts as stone depth 1, so it is body, not floor
        for (int y = 12; y <= 126; y++) assertEquals(STONE, c.cells[y], "y" + y);
        assertEquals(BEDROCK, c.cells[127]);
    }

    @Test
    @DisplayName("floor skins: sculk by default, blooming / gloomy per biome, echo soil to the surface depth")
    void floorSkins() {
        for (var biome : new Object[][] {
                {null, Blocks.SCULK.defaultBlockState(), 1},
                {OthersideSurfaceSkin.BLOOMING_CAVERNS, BLOOM, 1},
                {OthersideSurfaceSkin.OVERCAST_COLUMNS, GLOOMY, 1},
                {OthersideSurfaceSkin.ECHOING_FOREST, ECHO, 3}}) {
            Column c = new Column();
            for (int y = 60; y <= 80; y++) c.cells[y] = AIR;          // a cave: floor at y59, ceiling at y81
            skin(1.0f, 2).dress(c, 0, 0, 127, 0, (net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>) biome[0]);
            int depth = (int) biome[2];
            for (int d = 0; d < depth; d++) {
                assertEquals(biome[1], c.cells[59 - d], "floor depth " + d + " for " + biome[0]);
            }
            assertEquals(biome[0] == OthersideSurfaceSkin.OVERCAST_COLUMNS ? GLOOMSLATE : STONE, c.cells[59 - depth],
                    "below the skin for " + biome[0]);
            assertEquals(biome[0] == OthersideSurfaceSkin.OVERCAST_COLUMNS ? GLOOMSLATE : STONE, c.cells[81],
                    "ceiling block is not a floor for " + biome[0]);
        }
    }

    @Test
    @DisplayName("fluid neither resets nor grows the stone depth; a non-default block counts but is not replaced")
    void depthCounterRules() {
        Column c = new Column();
        for (int y = 60; y <= 80; y++) c.cells[y] = AIR;
        for (int y = 55; y <= 59; y++) c.cells[y] = WATER;             // a lake on the floor
        c.cells[54] = Blocks.GRAVEL.defaultBlockState();                // a non-default block at the lake bed
        skin(1.0f, 2).dress(c, 0, 0, 127, 0, null);
        assertEquals(Blocks.GRAVEL, c.cells[54].getBlock(), "the rule only replaces the default block");
        // gravel is stone depth 1; the sculk stone under it is depth 2 → no floor skin
        assertEquals(STONE, c.cells[53]);
        assertFalse(Arrays.asList(c.cells).contains(Blocks.SCULK.defaultBlockState()), "no exposed sculk-stone floor anywhere");
    }
}
