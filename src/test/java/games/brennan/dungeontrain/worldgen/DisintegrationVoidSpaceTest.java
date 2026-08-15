package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for {@link DisintegrationBand#certainVoid} — the conservative "did erosion
 * remove this block with certainty?" predicate behind the void-band fluid guard. No NeoForge
 * bootstrap, same convention as {@link DisintegrationTest} and {@link ChuncksBandTest}.
 *
 * <p>The predicate must stay a <b>strict subset</b> of what {@code WorldDisintegrationEvents}
 * actually removes: a false positive would veto fluid spread into a position that still has terrain,
 * which the player would see as water stopping in mid-air.</p>
 *
 * <p>Geometry: {@code trainY=78} ⇒ {@code bedY=76}, {@code railY=77}, corridor {@code Z=0..4}
 * (a 5-wide carriage), tunnel walls at {@code Z=-4..8}. Removal probability is
 * {@code ramp × (1 + depth/96)} clamped to 1, so certainty needs {@code ramp ≥ 0.5} and enough
 * depth below the bed.</p>
 */
final class DisintegrationVoidSpaceTest {

    private static final int TRAIN_Y = 78;
    private static final int BED_Y = TRAIN_Y - 2;   // 76
    private static final int RAIL_Y = TRAIN_Y - 1;  // 77
    private static final TrackGeometry G = new TrackGeometry(BED_Y, RAIL_Y, 0, 4);

    /** A Z well outside both the track corridor and the preserved tunnel footprint. */
    private static final int OPEN_Z = 40;

    // ---- band core (ramp == 1) ----------------------------------------------

    @Test
    @DisplayName("core column is certain void at every depth, and above the bed too")
    void core_voidEverywhere() {
        assertTrue(DisintegrationBand.certainVoid(1.0, BED_Y, OPEN_Z, G));
        assertTrue(DisintegrationBand.certainVoid(1.0, BED_Y + 40, OPEN_Z, G));   // above the bed
        assertTrue(DisintegrationBand.certainVoid(1.0, BED_Y - 1, OPEN_Z, G));
        assertTrue(DisintegrationBand.certainVoid(1.0, -48, OPEN_Z, G));          // dimension floor
    }

    @Test
    @DisplayName("core preserves the whole tunnel footprint, so it is never certain void there")
    void core_tunnelFootprintPreserved() {
        TunnelGeometry tg = TunnelGeometry.from(G);
        for (int z = tg.wallMinZ(); z <= tg.wallMaxZ(); z++) {
            assertFalse(DisintegrationBand.certainVoid(1.0, BED_Y - 60, z, G),
                "tunnel footprint z=" + z + " must not read as void in the core");
        }
        // Just outside the walls, the core is void again.
        assertTrue(DisintegrationBand.certainVoid(1.0, BED_Y - 60, tg.wallMinZ() - 1, G));
        assertTrue(DisintegrationBand.certainVoid(1.0, BED_Y - 60, tg.wallMaxZ() + 1, G));
    }

    // ---- fade zone (0 < ramp < 1) -------------------------------------------

    @Test
    @DisplayName("fade ramp>=0.5 is certain void only from VERTICAL_SPAN blocks below the bed down")
    void fade_certainOnlyAtDepth() {
        // ramp 0.5 needs depthBoost 1.0 ⇒ depth >= 96 (Disintegration.VERTICAL_SPAN).
        assertFalse(DisintegrationBand.certainVoid(0.5, BED_Y - 95, OPEN_Z, G));
        assertTrue(DisintegrationBand.certainVoid(0.5, BED_Y - 96, OPEN_Z, G));
        assertTrue(DisintegrationBand.certainVoid(0.5, BED_Y - 200, OPEN_Z, G));
        // Surface of the same column stays patchy — must not read as void.
        assertFalse(DisintegrationBand.certainVoid(0.5, BED_Y, OPEN_Z, G));
        assertFalse(DisintegrationBand.certainVoid(0.5, BED_Y + 10, OPEN_Z, G));
    }

    @Test
    @DisplayName("a stronger fade ramp reaches certainty at a shallower depth")
    void fade_strongerRampShallower() {
        // ramp 0.8 needs (1 + d/96) >= 1.25 ⇒ depth >= 24.
        assertFalse(DisintegrationBand.certainVoid(0.8, BED_Y - 23, OPEN_Z, G));
        assertTrue(DisintegrationBand.certainVoid(0.8, BED_Y - 24, OPEN_Z, G));
    }

    @Test
    @DisplayName("fade ramp<0.5 is never certain void, however deep — depthBoost caps at 2x")
    void fade_weakRampNeverCertain() {
        for (int depth = 0; depth <= 400; depth += 20) {
            assertFalse(DisintegrationBand.certainVoid(0.49, BED_Y - depth, OPEN_Z, G),
                "ramp 0.49 at depth " + depth + " must not read as certain void");
        }
    }

    @Test
    @DisplayName("fade zone does not preserve the tunnel footprint — only the core does")
    void fade_tunnelFootprintNotPreserved() {
        TunnelGeometry tg = TunnelGeometry.from(G);
        // Inside the wall span but well below the bed, a deep fade column IS eroded.
        assertTrue(DisintegrationBand.certainVoid(0.9, BED_Y - 300, tg.wallMinZ(), G));
    }

    // ---- corridor carve-outs -------------------------------------------------

    @Test
    @DisplayName("bed and rail rows inside the track corridor are never certain void")
    void corridor_bedAndRailRowsSurvive() {
        for (int z = G.trackZMin(); z <= G.trackZMax(); z++) {
            assertFalse(DisintegrationBand.certainVoid(1.0, BED_Y, z, G), "bed row z=" + z);
            assertFalse(DisintegrationBand.certainVoid(1.0, RAIL_Y, z, G), "rail row z=" + z);
        }
        // Well clear of both the corridor and the tunnel footprint, those same rows are void.
        // (In the core the corridor sits inside the preserved tunnel span, so the bed/rail check
        // is belt-and-braces there; it earns its keep only if the two geometries ever diverge.)
        assertTrue(DisintegrationBand.certainVoid(1.0, BED_Y, OPEN_Z, G));
        assertTrue(DisintegrationBand.certainVoid(1.0, RAIL_Y, OPEN_Z, G));
    }

    // ---- off band -----------------------------------------------------------

    @Test
    @DisplayName("ramp 0 (overworld / nether column) is never void")
    void offBand_neverVoid() {
        assertFalse(DisintegrationBand.certainVoid(0.0, BED_Y, OPEN_Z, G));
        assertFalse(DisintegrationBand.certainVoid(0.0, BED_Y - 300, OPEN_Z, G));
        assertFalse(DisintegrationBand.certainVoid(-1.0, BED_Y - 300, OPEN_Z, G));
    }
}
