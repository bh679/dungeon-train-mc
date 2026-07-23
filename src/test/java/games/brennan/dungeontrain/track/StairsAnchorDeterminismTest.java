package games.brennan.dungeontrain.track;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the deterministic stairs anchor grid ({@link TrackGenerator#stairsAnchorPhase},
 * {@link TrackGenerator#stairsAnchorInRange}, {@link TrackGenerator#stairsSideForAnchor})
 * that replaced the chunk-generation-order-dependent {@code tryReserveStairs} arbitration.
 *
 * <p>The determinism claim these functions carry: stairs decisions are pure functions of
 * (worldSeed, chunk range), so the set of stairs placed is identical no matter which order
 * chunks generate in — verified directly by the arrival-order shuffle test below.</p>
 */
class StairsAnchorDeterminismTest {

    private static final int SPACING = 100; // MIN_STAIRS_SPACING — pinned by the phase-range test

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, -1L, 42L, 0x5DEECE66DL, Long.MIN_VALUE, Long.MAX_VALUE})
    @DisplayName("anchor phase is stable per seed and within [0, MIN_STAIRS_SPACING)")
    void phaseStableAndInRange(long seed) {
        int phase = TrackGenerator.stairsAnchorPhase(seed);
        assertEquals(phase, TrackGenerator.stairsAnchorPhase(seed), "phase must be pure");
        assertTrue(phase >= 0 && phase < SPACING, "phase out of range: " + phase);
    }

    @Test
    @DisplayName("every 16-wide chunk range yields at most one anchor, on the seed's grid")
    void chunkRangeYieldsAtMostOneAnchor() {
        long seed = 987654321L;
        int phase = TrackGenerator.stairsAnchorPhase(seed);
        for (int chunkMinX = -400; chunkMinX <= 400; chunkMinX += 16) {
            int anchor = TrackGenerator.stairsAnchorInRange(seed, chunkMinX, chunkMinX + 15);
            if (anchor != Integer.MIN_VALUE) {
                assertTrue(anchor >= chunkMinX && anchor <= chunkMinX + 15, "anchor outside range");
                assertEquals(phase, Math.floorMod(anchor, SPACING), "anchor off the seed grid");
            }
        }
    }

    @Test
    @DisplayName("consecutive anchors are exactly MIN_STAIRS_SPACING apart")
    void anchorsExactlySpacingApart() {
        long seed = -777L;
        List<Integer> anchors = new ArrayList<>();
        for (int minX = -1000; minX <= 1000; minX += 16) {
            int a = TrackGenerator.stairsAnchorInRange(seed, minX, minX + 15);
            if (a != Integer.MIN_VALUE) anchors.add(a);
        }
        for (int i = 1; i < anchors.size(); i++) {
            assertEquals(SPACING, anchors.get(i) - anchors.get(i - 1),
                "anchor gap must be exactly the grid period");
        }
    }

    @Test
    @DisplayName("sides strictly alternate along consecutive anchors")
    void sidesAlternate() {
        long seed = 1234L;
        Boolean prev = null;
        for (int minX = -1000; minX <= 1000; minX += 16) {
            int a = TrackGenerator.stairsAnchorInRange(seed, minX, minX + 15);
            if (a == Integer.MIN_VALUE) continue;
            boolean side = TrackGenerator.stairsSideForAnchor(seed, a);
            if (prev != null) {
                assertEquals(!prev, side, "sides must alternate at anchor " + a);
            }
            prev = side;
        }
    }

    /**
     * The core regression guard: simulate the per-chunk decision (anchor lookup + nearest
     * own-chunk candidate) over a fixed pillar-candidate field, processing chunks in two
     * different arrival orders. The placed set must be identical — the old registry racing
     * could not guarantee this (first-come-wins on the shared spacing window).
     */
    @Test
    @DisplayName("stairs placement set is identical for shuffled chunk arrival orders")
    void arrivalOrderIndependent() {
        long seed = 31337L;
        // Candidate pillar Xs (multiple per anchor window, spanning chunk borders).
        List<Integer> pillars = List.of(-260, -228, -96, -88, -80, 4, 12, 104, 112, 120, 296, 304);

        List<Integer> chunkMins = new ArrayList<>();
        for (int minX = -320; minX <= 320; minX += 16) chunkMins.add(minX);

        List<int[]> orderA = simulate(seed, chunkMins, pillars);
        Collections.reverse(chunkMins);
        List<int[]> orderB = simulate(seed, chunkMins, pillars);
        orderA.sort(java.util.Comparator.comparingInt(p -> p[0]));
        orderB.sort(java.util.Comparator.comparingInt(p -> p[0]));

        assertEquals(orderA.size(), orderB.size(), "placement count differs by arrival order");
        for (int i = 0; i < orderA.size(); i++) {
            assertEquals(orderA.get(i)[0], orderB.get(i)[0], "stairs X differs by arrival order");
            assertEquals(orderA.get(i)[1], orderB.get(i)[1], "stairs side differs by arrival order");
        }
        // Spacing floor: chosen positions never closer than 60 (the compat-guard radius).
        for (int i = 1; i < orderA.size(); i++) {
            assertTrue(orderA.get(i)[0] - orderA.get(i - 1)[0] >= 60,
                "spacing floor violated between " + orderA.get(i - 1)[0] + " and " + orderA.get(i)[0]);
        }
    }

    /** Per-chunk anchor decision mirroring placePillarsAtWorldgen's selection loop. */
    private static List<int[]> simulate(long seed, List<Integer> chunkMins, List<Integer> pillars) {
        List<int[]> placed = new ArrayList<>();
        for (int chunkMinX : chunkMins) {
            int chunkMaxX = chunkMinX + 15;
            int anchor = TrackGenerator.stairsAnchorInRange(seed, chunkMinX, chunkMaxX);
            if (anchor == Integer.MIN_VALUE) continue;
            Integer chosen = null;
            for (int x : pillars) {
                if (x < chunkMinX || x > chunkMaxX) continue; // own-chunk candidates only
                if (chosen == null || Math.abs(x - anchor) < Math.abs(chosen - anchor)) chosen = x;
            }
            if (chosen == null) continue;
            placed.add(new int[]{chosen, TrackGenerator.stairsSideForAnchor(seed, anchor) ? 1 : 0});
        }
        return placed;
    }
}
