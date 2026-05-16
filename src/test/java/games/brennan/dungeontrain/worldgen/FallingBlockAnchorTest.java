package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapping-table tests for {@link FallingBlockAnchor#stableEquivalent}. Locks
 * down the per-block pairings (sand→sandstone, gravel→stone, concrete powder
 * → matching concrete, etc.) and the fallback behaviour for unmapped
 * {@link FallingBlock} subclasses.
 *
 * <p>Requires {@code unitTest.enable()} in build.gradle so the NeoForge
 * moddev runtime bootstraps {@code Blocks.*} registry singletons before the
 * test class loads — without that, {@code Blocks.SAND} would resolve to
 * {@code null} and these tests would throw at class init.</p>
 */
final class FallingBlockAnchorTest {

    @Test
    @DisplayName("sand → sandstone")
    void sandToSandstone() {
        BlockState got = FallingBlockAnchor.stableEquivalent(Blocks.SAND.defaultBlockState());
        assertNotNull(got);
        assertEquals(Blocks.SANDSTONE, got.getBlock());
    }

    @Test
    @DisplayName("red_sand → red_sandstone")
    void redSandToRedSandstone() {
        BlockState got = FallingBlockAnchor.stableEquivalent(Blocks.RED_SAND.defaultBlockState());
        assertNotNull(got);
        assertEquals(Blocks.RED_SANDSTONE, got.getBlock());
    }

    @Test
    @DisplayName("gravel → stone (user choice: not cobblestone)")
    void gravelToStone() {
        BlockState got = FallingBlockAnchor.stableEquivalent(Blocks.GRAVEL.defaultBlockState());
        assertNotNull(got);
        assertEquals(Blocks.STONE, got.getBlock());
    }

    @Test
    @DisplayName("suspicious_sand → sandstone")
    void suspiciousSandToSandstone() {
        BlockState got = FallingBlockAnchor.stableEquivalent(Blocks.SUSPICIOUS_SAND.defaultBlockState());
        assertNotNull(got);
        assertEquals(Blocks.SANDSTONE, got.getBlock());
    }

    @Test
    @DisplayName("suspicious_gravel → stone")
    void suspiciousGravelToStone() {
        BlockState got = FallingBlockAnchor.stableEquivalent(Blocks.SUSPICIOUS_GRAVEL.defaultBlockState());
        assertNotNull(got);
        assertEquals(Blocks.STONE, got.getBlock());
    }

    @Test
    @DisplayName("anvil → stone (fallback — no clean petrified equivalent)")
    void anvilToStone() {
        BlockState got = FallingBlockAnchor.stableEquivalent(Blocks.ANVIL.defaultBlockState());
        assertNotNull(got);
        assertEquals(Blocks.STONE, got.getBlock());
        assertTrue(Blocks.ANVIL instanceof Fallable,
            "Test premise — AnvilBlock must implement Fallable for the fallback path to apply");
    }

    @Test
    @DisplayName("Fallable test premise — suspicious sand/gravel implement Fallable (not FallingBlock)")
    void brushableImplementsFallable() {
        assertTrue(Blocks.SUSPICIOUS_SAND instanceof Fallable,
            "BrushableBlock must implement Fallable — falls when its support is removed");
        assertTrue(Blocks.SUSPICIOUS_GRAVEL instanceof Fallable);
    }

    @Test
    @DisplayName("non-FallingBlock (stone) → null (caller no-ops)")
    void stoneReturnsNull() {
        assertNull(FallingBlockAnchor.stableEquivalent(Blocks.STONE.defaultBlockState()));
    }

    @Test
    @DisplayName("non-FallingBlock (air) → null")
    void airReturnsNull() {
        assertNull(FallingBlockAnchor.stableEquivalent(Blocks.AIR.defaultBlockState()));
    }

    @Test
    @DisplayName("non-FallingBlock (dirt) → null")
    void dirtReturnsNull() {
        assertNull(FallingBlockAnchor.stableEquivalent(Blocks.DIRT.defaultBlockState()));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("concretePowderPairs")
    @DisplayName("all 16 concrete powders → matching concrete")
    void concretePowderToConcrete(String label, BlockState powder, BlockState expectedConcrete) {
        BlockState got = FallingBlockAnchor.stableEquivalent(powder);
        assertNotNull(got, label + " mapping must not be null");
        assertEquals(expectedConcrete.getBlock(), got.getBlock(), label);
    }

    static Stream<Arguments> concretePowderPairs() {
        return Stream.of(
            Arguments.of("white",      Blocks.WHITE_CONCRETE_POWDER.defaultBlockState(),      Blocks.WHITE_CONCRETE.defaultBlockState()),
            Arguments.of("orange",     Blocks.ORANGE_CONCRETE_POWDER.defaultBlockState(),     Blocks.ORANGE_CONCRETE.defaultBlockState()),
            Arguments.of("magenta",    Blocks.MAGENTA_CONCRETE_POWDER.defaultBlockState(),    Blocks.MAGENTA_CONCRETE.defaultBlockState()),
            Arguments.of("light_blue", Blocks.LIGHT_BLUE_CONCRETE_POWDER.defaultBlockState(), Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState()),
            Arguments.of("yellow",     Blocks.YELLOW_CONCRETE_POWDER.defaultBlockState(),     Blocks.YELLOW_CONCRETE.defaultBlockState()),
            Arguments.of("lime",       Blocks.LIME_CONCRETE_POWDER.defaultBlockState(),       Blocks.LIME_CONCRETE.defaultBlockState()),
            Arguments.of("pink",       Blocks.PINK_CONCRETE_POWDER.defaultBlockState(),       Blocks.PINK_CONCRETE.defaultBlockState()),
            Arguments.of("gray",       Blocks.GRAY_CONCRETE_POWDER.defaultBlockState(),       Blocks.GRAY_CONCRETE.defaultBlockState()),
            Arguments.of("light_gray", Blocks.LIGHT_GRAY_CONCRETE_POWDER.defaultBlockState(), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState()),
            Arguments.of("cyan",       Blocks.CYAN_CONCRETE_POWDER.defaultBlockState(),       Blocks.CYAN_CONCRETE.defaultBlockState()),
            Arguments.of("purple",     Blocks.PURPLE_CONCRETE_POWDER.defaultBlockState(),     Blocks.PURPLE_CONCRETE.defaultBlockState()),
            Arguments.of("blue",       Blocks.BLUE_CONCRETE_POWDER.defaultBlockState(),       Blocks.BLUE_CONCRETE.defaultBlockState()),
            Arguments.of("brown",      Blocks.BROWN_CONCRETE_POWDER.defaultBlockState(),      Blocks.BROWN_CONCRETE.defaultBlockState()),
            Arguments.of("green",      Blocks.GREEN_CONCRETE_POWDER.defaultBlockState(),      Blocks.GREEN_CONCRETE.defaultBlockState()),
            Arguments.of("red",        Blocks.RED_CONCRETE_POWDER.defaultBlockState(),        Blocks.RED_CONCRETE.defaultBlockState()),
            Arguments.of("black",      Blocks.BLACK_CONCRETE_POWDER.defaultBlockState(),      Blocks.BLACK_CONCRETE.defaultBlockState())
        );
    }
}
