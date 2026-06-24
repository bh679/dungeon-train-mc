package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    @DisplayName("drainBudget — scales with the backlog, capped at MAX_CHUNKS_PER_TICK (16)")
    void drainBudgetClampsToCap() {
        assertEquals(0, CorridorCleanupEvents.drainBudget(0), "empty queue → no work");
        assertEquals(1, CorridorCleanupEvents.drainBudget(1), "tiny backlog drains fully");
        assertEquals(15, CorridorCleanupEvents.drainBudget(15), "below the cap drains fully");
        assertEquals(16, CorridorCleanupEvents.drainBudget(16), "at the cap drains fully");
        assertEquals(16, CorridorCleanupEvents.drainBudget(17), "above the cap is clamped to the cap");
        assertEquals(16, CorridorCleanupEvents.drainBudget(10_000), "a huge backlog stays bounded");
        assertEquals(0, CorridorCleanupEvents.drainBudget(-3), "defensive: negative → 0");
    }

    @Test
    @DisplayName("trackRepairBlock — buried bed/rail cells restore the track, off-track cells do not")
    void trackRepairRestoresTrackSurface() {
        // trainY 84 → bedY 82, railY 83; 5-wide track at world Z 0..4 (rails at Z 1 and 3),
        // matching the reported basalt at bedY 82 / Z 2 (track centre).
        TrackGeometry g = new TrackGeometry(82, 83, 0, 4);
        TunnelGeometry tg = TunnelGeometry.from(g);

        // Bed row (bedY, across the track Z-span) → restore the stone-brick bed.
        assertEquals(TrackPalette.BED, CorridorCleanupEvents.trackRepairBlock(82, 2, g, tg), "bed centre (the reported cell) restored");
        assertEquals(TrackPalette.BED, CorridorCleanupEvents.trackRepairBlock(82, 0, g, tg), "bed edge restored");
        assertEquals(TrackPalette.BED, CorridorCleanupEvents.trackRepairBlock(82, 4, g, tg), "bed edge restored");

        // Rail columns (railY, Z 1 and 3) → restore the rail.
        assertEquals(TrackPalette.RAIL, CorridorCleanupEvents.trackRepairBlock(83, 1, g, tg), "near rail restored");
        assertEquals(TrackPalette.RAIL, CorridorCleanupEvents.trackRepairBlock(83, 3, g, tg), "far rail restored");

        // Off-track cells → null (the caller clears these to air, never to a track block).
        assertNull(CorridorCleanupEvents.trackRepairBlock(83, 2, g, tg), "rail-level centre is air between the rails");
        assertNull(CorridorCleanupEvents.trackRepairBlock(83, 0, g, tg), "rail-level bed edge is not a rail column");
        assertNull(CorridorCleanupEvents.trackRepairBlock(82, -1, g, tg), "pad beside the bed is not track");
        assertNull(CorridorCleanupEvents.trackRepairBlock(82, 5, g, tg), "pad beside the bed is not track");
        assertNull(CorridorCleanupEvents.trackRepairBlock(84, 2, g, tg), "above the rail is air");
        assertNull(CorridorCleanupEvents.trackRepairBlock(81, 2, g, tg), "below the bed is not track");
    }

    @Test
    @DisplayName("chunkOverlapsCorridorZ — the pad-only chunk near a chunk boundary still overlaps")
    void chunkOverlapsCorridorZcoversPad() {
        // Corridor airspace z = [-3..9] (e.g. track z=0..6 widened ±3) — straddles the z=-1 / z=0 seam.
        int zMin = -3, zMax = 9;
        // Chunk z=0 spans [0..15] — holds the track + most of the corridor.
        assertTrue(CorridorCleanupEvents.chunkOverlapsCorridorZ(0, 15, zMin, zMax),
            "the track chunk overlaps the corridor");
        // Chunk z=-1 spans [-16..-1] — holds ONLY the outer pad (z=-3..-1), no track row. This is the
        // chunk the old track-Z prefilter dropped; it must now be enqueued so its pad basalt is swept.
        assertTrue(CorridorCleanupEvents.chunkOverlapsCorridorZ(-16, -1, zMin, zMax),
            "a pad-only chunk near the boundary must still overlap");
        // Chunks fully outside the corridor span are correctly skipped.
        assertFalse(CorridorCleanupEvents.chunkOverlapsCorridorZ(16, 31, zMin, zMax),
            "a chunk past the corridor does not overlap");
        assertFalse(CorridorCleanupEvents.chunkOverlapsCorridorZ(-32, -17, zMin, zMax),
            "a chunk before the corridor does not overlap");
    }
}
