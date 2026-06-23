package games.brennan.dungeontrain.event;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link CorridorCleanupEvents#isNetherClutter} — the predicate that decides which
 * blocks the deferred corridor sweep strips out of the train tunnel inside the Nether band core
 * (cross-chunk basalt-deltas / crimson / warped / soul-sand decoration spillover). This pins the
 * accepted Nether terrain/decoration set and the rejected set (tunnel-template blocks, track,
 * fluids, plain overworld terrain).
 *
 * <p>Requires {@code unitTest.enable()} in build.gradle so the NeoForge moddev runtime bootstraps
 * {@code Blocks.*} registry singletons before the test class loads — mirrors {@code TunnelPaletteTest}.</p>
 *
 * <p><b>Tag-backed branches are NOT tested here.</b> {@code BlockState.is(BlockTags.X)} relies on
 * tags loaded from datapacks at server start, which the moddev unit-test runtime does not bootstrap,
 * so the {@code BASE_STONE_NETHER} / {@code NYLIUM} / {@code WART_BLOCKS} fallbacks report
 * {@code false} here. That is why {@code isNetherClutter} lists the offenders as direct
 * {@code Blocks.X} checks too — those DO work in this runtime and are asserted below. The tag
 * fallbacks' in-game behaviour is verified at Gate 2 by riding a Nether band.</p>
 */
final class CorridorCleanupEventsTest {

    /** Nether terrain + decoration the sweep must remove — direct {@code Blocks.X} matches. */
    static Stream<Block> netherClutter() {
        return Stream.of(
            Blocks.NETHERRACK,
            Blocks.BASALT,             // the reported offender (basalt deltas)
            Blocks.SMOOTH_BASALT,
            Blocks.BLACKSTONE,
            Blocks.GILDED_BLACKSTONE,
            Blocks.MAGMA_BLOCK,
            Blocks.GLOWSTONE,
            Blocks.SHROOMLIGHT,
            Blocks.BONE_BLOCK,
            Blocks.SOUL_SAND,
            Blocks.SOUL_SOIL,
            Blocks.CRIMSON_NYLIUM,
            Blocks.WARPED_NYLIUM,
            Blocks.NETHER_WART_BLOCK,
            Blocks.WARPED_WART_BLOCK,
            Blocks.CRIMSON_STEM,
            Blocks.WARPED_STEM,
            Blocks.CRIMSON_HYPHAE,
            Blocks.WARPED_HYPHAE,
            Blocks.CRIMSON_ROOTS,
            Blocks.WARPED_ROOTS,
            Blocks.CRIMSON_FUNGUS,
            Blocks.WARPED_FUNGUS,
            Blocks.NETHER_SPROUTS,
            Blocks.WEEPING_VINES,
            Blocks.WEEPING_VINES_PLANT,
            Blocks.TWISTING_VINES,
            Blocks.TWISTING_VINES_PLANT,
            Blocks.FIRE,
            Blocks.SOUL_FIRE,
            Blocks.GRAVEL,             // ore_gravel deltas — the falling-block hazard
            Blocks.SAND,
            Blocks.RED_SAND
        );
    }

    /**
     * Blocks the sweep must leave alone — the tunnel template (stone bricks / stairs / sea lantern),
     * the track (rails), fluids (cascade hazard — drained at worldgen instead), and plain overworld
     * terrain that shouldn't be touched even if it strays into the band corridor.
     */
    static Stream<Block> preserved() {
        return Stream.of(
            Blocks.STONE_BRICKS,       // tunnel walls / ceiling / floor + the bed
            Blocks.STONE_BRICK_STAIRS, // portal pyramid
            Blocks.SEA_LANTERN,        // tunnel lighting
            Blocks.RAIL,               // track
            Blocks.POWERED_RAIL,
            Blocks.AIR,
            Blocks.WATER,              // fluid — never removed here (would cascade)
            Blocks.LAVA,               // fluid — never removed here (would cascade)
            Blocks.STONE,              // overworld terrain
            Blocks.DEEPSLATE,
            Blocks.OAK_LOG             // overworld surface feature
        );
    }

    @ParameterizedTest
    @MethodSource("netherClutter")
    @DisplayName("nether terrain/decoration — removed from the band tunnel")
    void netherClutterRemoved(Block block) {
        assertTrue(
            CorridorCleanupEvents.isNetherClutter(block.defaultBlockState()),
            () -> block + " must be treated as Nether clutter and swept from the tunnel"
        );
    }

    @ParameterizedTest
    @MethodSource("preserved")
    @DisplayName("tunnel template / track / fluids / overworld terrain — preserved")
    void preservedBlocksKept(Block block) {
        assertFalse(
            CorridorCleanupEvents.isNetherClutter(block.defaultBlockState()),
            () -> block + " must NOT be treated as Nether clutter"
        );
    }
}
