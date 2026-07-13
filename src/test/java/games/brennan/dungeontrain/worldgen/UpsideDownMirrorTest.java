package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-arithmetic tests for {@link UpsideDownMirror}'s {@code MirrorPlan} local-position packing — the
 * one bit of non-obvious maths in the compute→apply split (a packing bug would silently misplace every
 * mirrored block). The full compute/apply parity against the old in-place mirror is validated in-game
 * via the {@code upsideDownMirrorPrecompute} on/off output-identity A/B, matching how the band's block
 * handler has always been integration-tested (see {@link UpsideDownBandTest}).
 */
final class UpsideDownMirrorTest {

    @Test
    @DisplayName("pack/unpack round-trips dx, dz, and y across the build range for both overworld floors")
    void packRoundTrips() {
        // Both shipped world floors: default (minY=32) and the min_y=-64 variant, plus a deep-cave floor.
        for (int minY : new int[] {32, -64, 0}) {
            int maxY = 320; // exclusive
            for (int y = minY; y < maxY; y++) {
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        int p = UpsideDownMirror.pack(dx, y, dz, minY);
                        assertEquals(dx, UpsideDownMirror.unpackDx(p), "dx at y=" + y + " minY=" + minY);
                        assertEquals(dz, UpsideDownMirror.unpackDz(p), "dz at y=" + y + " minY=" + minY);
                        assertEquals(y, UpsideDownMirror.unpackY(p, minY), "y at y=" + y + " minY=" + minY);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("dx/dz nibbles never bleed into the y field (y offset is always >= 0)")
    void nibblesDoNotCorruptY() {
        int minY = -64;
        // Max local coords at the build floor: dx=15, dz=15, y=minY → y offset 0.
        int atFloor = UpsideDownMirror.pack(15, minY, 15, minY);
        assertEquals(minY, UpsideDownMirror.unpackY(atFloor, minY));
        assertEquals(15, UpsideDownMirror.unpackDx(atFloor));
        assertEquals(15, UpsideDownMirror.unpackDz(atFloor));

        // High y with max nibbles unpacks cleanly (the >>> 8 drops the nibble bits).
        int high = UpsideDownMirror.pack(15, 319, 15, minY);
        assertEquals(319, UpsideDownMirror.unpackY(high, minY));
        assertEquals(15, UpsideDownMirror.unpackDx(high));
        assertEquals(15, UpsideDownMirror.unpackDz(high));
    }
}
