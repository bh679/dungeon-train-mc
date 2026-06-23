package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link NetherSurfacePalette} — the per-Nether-biome floor material the Nether core's
 * surface skin applies. Pins each biome's surface block (and the netherrack default).
 *
 * <p>Requires {@code unitTest.enable()} in build.gradle so the NeoForge moddev runtime bootstraps
 * {@code Blocks.*} registry singletons before the class loads (otherwise {@code Blocks.CRIMSON_NYLIUM}
 * is {@code null}). {@code Biomes.*} are plain {@code ResourceKey}s and need no bootstrap. Mirrors
 * {@code TunnelPaletteTest}.</p>
 */
final class NetherSurfacePaletteTest {

    @Test
    @DisplayName("hasSurface: the four decorated biomes paint a floor, nether_wastes does not")
    void hasSurface() {
        assertTrue(NetherSurfacePalette.hasSurface(Biomes.CRIMSON_FOREST));
        assertTrue(NetherSurfacePalette.hasSurface(Biomes.WARPED_FOREST));
        assertTrue(NetherSurfacePalette.hasSurface(Biomes.SOUL_SAND_VALLEY));
        assertTrue(NetherSurfacePalette.hasSurface(Biomes.BASALT_DELTAS));
        assertFalse(NetherSurfacePalette.hasSurface(Biomes.NETHER_WASTES));
        assertFalse(NetherSurfacePalette.hasSurface(Biomes.PLAINS)); // non-Nether → no skin
    }

    @Test
    @DisplayName("crimson/warped: nylium caps the top block, netherrack beneath")
    void nyliumCap() {
        assertTrue(NetherSurfacePalette.surfaceBlock(Biomes.CRIMSON_FOREST, 0, 0.5).is(Blocks.CRIMSON_NYLIUM));
        assertTrue(NetherSurfacePalette.surfaceBlock(Biomes.CRIMSON_FOREST, 1, 0.5).is(Blocks.NETHERRACK));
        assertTrue(NetherSurfacePalette.surfaceBlock(Biomes.WARPED_FOREST, 0, 0.5).is(Blocks.WARPED_NYLIUM));
        assertTrue(NetherSurfacePalette.surfaceBlock(Biomes.WARPED_FOREST, 3, 0.5).is(Blocks.NETHERRACK));
    }

    @Test
    @DisplayName("soul sand valley: soul sand / soul soil noise mix at every skin depth")
    void soulMix() {
        for (int depth = 0; depth < 4; depth++) {
            assertTrue(NetherSurfacePalette.surfaceBlock(Biomes.SOUL_SAND_VALLEY, depth, 0.0).is(Blocks.SOUL_SAND));
            assertTrue(NetherSurfacePalette.surfaceBlock(Biomes.SOUL_SAND_VALLEY, depth, 0.99).is(Blocks.SOUL_SOIL));
        }
    }

    @Test
    @DisplayName("basalt deltas: basalt / blackstone noise mix at every skin depth")
    void basaltMix() {
        for (int depth = 0; depth < 4; depth++) {
            assertTrue(NetherSurfacePalette.surfaceBlock(Biomes.BASALT_DELTAS, depth, 0.0).is(Blocks.BASALT));
            assertTrue(NetherSurfacePalette.surfaceBlock(Biomes.BASALT_DELTAS, depth, 0.99).is(Blocks.BLACKSTONE));
        }
    }

    @Test
    @DisplayName("nether_wastes / unknown: always plain netherrack")
    void netherrackDefault() {
        BlockState wastes = NetherSurfacePalette.surfaceBlock(Biomes.NETHER_WASTES, 0, 0.5);
        BlockState plains = NetherSurfacePalette.surfaceBlock(Biomes.PLAINS, 0, 0.5);
        assertTrue(wastes.is(Blocks.NETHERRACK));
        assertTrue(plains.is(Blocks.NETHERRACK));
    }
}
