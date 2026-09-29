package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LostCityGroundProcessor}: which template blocks are base, what counts as world ground, and when a
 * block yields to the world. Needs the moddev unit-test bootstrap for {@code Blocks.*}.
 */
final class LostCityGroundProcessorTest {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    private static IntFunction<BlockState> column(BlockState... fromPadUp) {
        return y -> y < fromPadUp.length ? fromPadUp[y] : AIR;
    }

    @Test
    @DisplayName("natural pad blocks are base; roads, pavements and floors are structure")
    void baseSplit() {
        for (BlockState s : new BlockState[]{GRASS, DIRT, Blocks.MOSS_BLOCK.defaultBlockState(), STONE,
                Blocks.SAND.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.WATER.defaultBlockState()}) {
            assertTrue(LostCityGroundProcessor.isBase(s), s.toString());
        }
        for (BlockState s : new BlockState[]{Blocks.COBBLESTONE.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
                Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.DEEPSLATE_TILES.defaultBlockState(),
                Blocks.GRAY_CONCRETE.defaultBlockState(), Blocks.BIRCH_PLANKS.defaultBlockState(),
                Blocks.SPAWNER.defaultBlockState()}) {
            assertFalse(LostCityGroundProcessor.isBase(s), s.toString());
        }
    }

    @Test
    @DisplayName("world ground is raw terrain only")
    void naturalGround() {
        for (BlockState s : new BlockState[]{STONE, DIRT, GRASS, Blocks.GRAVEL.defaultBlockState(),
                Blocks.SAND.defaultBlockState(), Blocks.PODZOL.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
                Blocks.PACKED_MUD.defaultBlockState(), Blocks.ICE.defaultBlockState(), Blocks.BASALT.defaultBlockState(),
                Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.TERRACOTTA.defaultBlockState()}) {
            assertTrue(LostCityGroundProcessor.isNaturalGround(s), s.toString());
        }
        for (BlockState s : new BlockState[]{AIR, Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(), Blocks.STONE_STAIRS.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.CHEST.defaultBlockState(), Blocks.SNOW.defaultBlockState()}) {
            assertFalse(LostCityGroundProcessor.isNaturalGround(s), s.toString());
        }
    }

    @Test
    @DisplayName("pad base yields where the world already has ground, stays over a dip")
    void pad() {
        assertTrue(LostCityGroundProcessor.yields(Blocks.MOSS_BLOCK.defaultBlockState(), 0, column(GRASS)));
        assertFalse(LostCityGroundProcessor.yields(Blocks.MOSS_BLOCK.defaultBlockState(), 0, column(AIR)));
        assertFalse(LostCityGroundProcessor.yields(Blocks.MOSS_BLOCK.defaultBlockState(), 0, column(Blocks.WATER.defaultBlockState())));
        // a road stays a road even on ground
        assertFalse(LostCityGroundProcessor.yields(Blocks.COBBLESTONE.defaultBlockState(), 0, column(GRASS)));
    }

    @Test
    @DisplayName("air above the pad yields to a contiguous hill, never to a shelf over a gap")
    void column() {
        IntFunction<BlockState> hill = column(STONE, STONE, DIRT, GRASS);
        assertTrue(LostCityGroundProcessor.yields(AIR, 1, hill));
        assertTrue(LostCityGroundProcessor.yields(AIR, 3, hill));
        assertFalse(LostCityGroundProcessor.yields(AIR, 4, hill));            // above the hill's top
        IntFunction<BlockState> shelf = column(STONE, AIR, DIRT, GRASS);
        assertFalse(LostCityGroundProcessor.yields(AIR, 2, shelf));           // gap below: the shelf is cut
        assertFalse(LostCityGroundProcessor.yields(AIR, 3, shelf));
    }

    @Test
    @DisplayName("air and cover yield to the world's water at any height; walls and the pad do not")
    void water() {
        BlockState water = Blocks.WATER.defaultBlockState();
        IntFunction<BlockState> flooded = column(Blocks.SAND.defaultBlockState(), water, water, water, AIR);
        assertTrue(LostCityGroundProcessor.yields(AIR, 1, flooded));
        assertTrue(LostCityGroundProcessor.yields(AIR, 3, flooded));
        assertTrue(LostCityGroundProcessor.yields(Blocks.SHORT_GRASS.defaultBlockState(), 1, flooded));
        assertFalse(LostCityGroundProcessor.yields(AIR, 4, flooded));                       // above the surface
        assertFalse(LostCityGroundProcessor.yields(Blocks.DEEPSLATE_TILES.defaultBlockState(), 2, flooded));
        assertFalse(LostCityGroundProcessor.yields(Blocks.MOSS_BLOCK.defaultBlockState(), 0, column(water)));  // pad stays over water
    }

    @Test
    @DisplayName("plant cover yields like air; walls never do")
    void coverAndWalls() {
        IntFunction<BlockState> hill = column(STONE, DIRT, GRASS);
        assertTrue(LostCityGroundProcessor.yields(Blocks.SHORT_GRASS.defaultBlockState(), 1, hill));
        assertTrue(LostCityGroundProcessor.yields(Blocks.MOSS_CARPET.defaultBlockState(), 1, hill));
        assertTrue(LostCityGroundProcessor.yields(Blocks.VINE.defaultBlockState(), 2, hill));
        assertFalse(LostCityGroundProcessor.yields(Blocks.SHORT_GRASS.defaultBlockState(), 1, column(GRASS, AIR)));
        assertFalse(LostCityGroundProcessor.yields(Blocks.DEEPSLATE_TILES.defaultBlockState(), 1, hill));
        assertFalse(LostCityGroundProcessor.yields(Blocks.DIRT.defaultBlockState(), 3, hill));  // a planter is structure
    }

    @Test
    @DisplayName("footing runs from a hanging pad down to the ground, capped, and never under grounded pads")
    void footing() {
        BlockState moss = Blocks.MOSS_BLOCK.defaultBlockState();
        BlockState road = Blocks.COBBLESTONE.defaultBlockState();
        // below.apply(1) is the block right under the pad
        IntFunction<BlockState> grounded = d -> STONE;
        IntFunction<BlockState> dip = d -> d <= 5 ? AIR : STONE;
        IntFunction<BlockState> chasm = d -> AIR;
        assertEquals(0, LostCityGroundProcessor.footingDepth(moss, grounded));
        assertEquals(5, LostCityGroundProcessor.footingDepth(moss, dip));
        assertEquals(5, LostCityGroundProcessor.footingDepth(road, dip));
        assertEquals(LostCityGroundProcessor.FOOTING_MAX_DEPTH, LostCityGroundProcessor.footingDepth(road, chasm));
        assertEquals(0, LostCityGroundProcessor.footingDepth(AIR, chasm));          // an air pad cell gets nothing
        assertEquals(5, LostCityGroundProcessor.footingDepth(moss, d -> d <= 5 ? Blocks.WATER.defaultBlockState() : STONE));
        assertEquals(Blocks.DIRT, LostCityGroundProcessor.footingFor(moss).getBlock());
        assertEquals(Blocks.STONE, LostCityGroundProcessor.footingFor(road).getBlock());
    }

    @Test
    @DisplayName("attached to Big Lost City and DT Lost City templates only")
    void appliesTo() {
        assertTrue(LostCityGroundProcessor.appliesTo(ResourceLocation.fromNamespaceAndPath("big_lost_city", "house1lt")));
        assertTrue(LostCityGroundProcessor.appliesTo(ResourceLocation.fromNamespaceAndPath("dungeontrain", "lost_city/office_tower")));
        assertFalse(LostCityGroundProcessor.appliesTo(ResourceLocation.fromNamespaceAndPath("dungeontrain", "templates/portal")));
        assertFalse(LostCityGroundProcessor.appliesTo(ResourceLocation.fromNamespaceAndPath("minecraft", "village/plains/houses/plains_small_house_1")));
        assertFalse(LostCityGroundProcessor.appliesTo(null));
    }
}
