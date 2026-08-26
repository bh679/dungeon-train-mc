package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link PortalCrossingLight} — the ramp that stops a portal swap from popping.
 *
 * <p>The property that actually matters is the last one: the ramp must read the same on both sides
 * of a swap. Everything above it is the shape that makes that property useful rather than trivially
 * true (a constant zero would also never pop, and would also never help).</p>
 */
final class PortalCrossingLightTest {

    private static PortalCarriageLayout layout(int length) {
        return new PortalCarriageLayout(length, 7, 7);
    }

    /**
     * The doorways are the one place a teleport has a fixed reference right in front of the player
     * to be noticed against ({@link PortalFacing#FIRST_RAMP_BLOCK}), and they are also where the two
     * copies genuinely differ — one opens onto the train, the other onto a plug. Holding the
     * lighting there would flatten a difference the player is about to see anyway.
     */
    @Test
    @DisplayName("both door planes hold nothing")
    void doorPlanesAreOff() {
        for (int length : new int[] {7, 9, 13, 16}) {
            PortalCarriageLayout l = layout(length);
            assertEquals(PortalCrossingLight.OFF,
                PortalCrossingLight.intensityAt(l.nearDoorX() + 0.5, l), 1e-9,
                "near door at length " + length);
            assertEquals(PortalCrossingLight.OFF,
                PortalCrossingLight.intensityAt(l.farDoorX() + 0.5, l), 1e-9,
                "far door at length " + length);
        }
    }

    /**
     * The plateau is the baffle-to-baffle stretch, which is exactly what
     * {@link PortalCarriageLayout#isCrossingZone} claims for the lantern floor. Two definitions of
     * "the middle" would be free to drift apart.
     */
    @Test
    @DisplayName("the hold is at full strength from each baffle inward, crossing zone included")
    void plateauCoversTheCrossingZone() {
        for (int length : new int[] {9, 13, 16}) {
            PortalCarriageLayout l = layout(length);
            for (int x = l.nearBaffleX(); x <= l.farBaffleX(); x++) {
                assertEquals(1.0, PortalCrossingLight.intensityAt(x + 0.5, l), 1e-9,
                    "block " + x + " at length " + length);
            }
            assertTrue(l.isCrossingZone((int) Math.floor(l.midX())));
        }
    }

    @Test
    @DisplayName("the ramp rises monotonically from each door to the plateau")
    void rampIsMonotonic() {
        PortalCarriageLayout l = layout(13);
        double previous = -1.0;
        for (int x = l.nearDoorX(); x <= (int) Math.floor(l.midX()); x++) {
            double t = PortalCrossingLight.intensityAt(x + 0.5, l);
            assertTrue(t >= previous, "fell at block " + x);
            assertTrue(t >= 0.0 && t <= 1.0, "out of range at block " + x);
            previous = t;
        }
    }

    /**
     * The corridor is symmetric and so is the ramp: an ENTRY corridor and an EXIT one are stamped
     * from the same source and lit the same, so a ramp that could tell them apart would be
     * describing something that is not there.
     */
    @Test
    @DisplayName("the ramp is a mirror about the corridor's middle")
    void rampIsSymmetric() {
        PortalCarriageLayout l = layout(13);
        for (int x = 0; x < l.length(); x++) {
            assertEquals(PortalCrossingLight.intensityAt(x + 0.5, l),
                PortalCrossingLight.intensityAt((l.length() - 1 - x) + 0.5, l), 1e-9,
                "block " + x + " does not mirror");
        }
    }

    /**
     * Quantised to the block, for the reason {@link PortalFacing#depthFromTrainDoor} is: a rider's
     * position on a Sable carriage disagrees between client and server by a couple of tenths, and a
     * ramp read off the raw coordinate would shimmer while the player stood still.
     */
    @Test
    @DisplayName("sub-block movement inside one block does not move the ramp")
    void quantisedToTheBlock() {
        PortalCarriageLayout l = layout(13);
        double at = PortalCrossingLight.intensityAt(1.01, l);
        assertEquals(at, PortalCrossingLight.intensityAt(1.5, l), 1e-9);
        assertEquals(at, PortalCrossingLight.intensityAt(1.99, l), 1e-9);
    }

    /** Positions past either end clamp to the end block rather than running negative or past 1. */
    @Test
    @DisplayName("positions outside the corridor clamp to its end blocks")
    void clampsOutsideTheCorridor() {
        PortalCarriageLayout l = layout(9);
        assertEquals(PortalCrossingLight.intensityAt(0.5, l),
            PortalCrossingLight.intensityAt(-4.0, l), 1e-9);
        assertEquals(PortalCrossingLight.intensityAt(l.farDoorX() + 0.5, l),
            PortalCrossingLight.intensityAt(l.length() + 4.0, l), 1e-9);
    }

    /**
     * <b>The point of the whole class.</b> A swap preserves the corridor-local offset exactly
     * ({@link PortalFrames}) and both frames are stamped from one layout — so the ramp a player is
     * standing in is the same number before and after they are carried across. That is what leaves
     * the swap with nothing to change, and it is asserted through {@code PortalFrames} rather than
     * on {@code intensityAt} alone, because the property lives in the pairing of the two.
     *
     * <p>Geometry borrowed from {@code PortalFramesTest}: a 9×7×7 carriage corridor at world
     * {@code (100, 78, 0)} and its twin 96 blocks above.</p>
     */
    @Test
    @DisplayName("the hold is identical either side of a swap, so a swap cannot change it")
    void survivesASwap() {
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            PortalCarriageLayout l = layout(9);
            PortalFrames frames = new PortalFrames(l,
                new PortalFrames.Origin(100, 78, 0),
                new PortalFrames.Origin(-4000, 174, 512),
                role);

            for (int x = 0; x < l.length(); x++) {
                double localX = x + 0.5, localY = 1, localZ = 3;

                double before = frames.crossingIntensityAt(
                    frames.originOf(PortalFrames.FRAME_CARRIAGE).x() + localX,
                    frames.originOf(PortalFrames.FRAME_CARRIAGE).y() + localY,
                    frames.originOf(PortalFrames.FRAME_CARRIAGE).z() + localZ);

                // Where the swap puts them: the same local offset in the other frame.
                double after = frames.crossingIntensityAt(
                    frames.originOf(PortalFrames.FRAME_TWIN).x() + localX,
                    frames.originOf(PortalFrames.FRAME_TWIN).y() + localY,
                    frames.originOf(PortalFrames.FRAME_TWIN).z() + localZ);

                assertEquals(before, after, 1e-9,
                    "block " + x + " differs across a " + role + " swap");
            }
        }
    }

    /** Nowhere near either corridor holds nothing at all. */
    @Test
    @DisplayName("a position in neither corridor is off")
    void outsideBothCorridorsIsOff() {
        PortalCarriageLayout l = layout(9);
        PortalFrames frames = new PortalFrames(l,
            new PortalFrames.Origin(100, 78, 0),
            new PortalFrames.Origin(-4000, 174, 512),
            PortalCarriageRole.ENTRY);

        assertEquals(PortalCrossingLight.OFF, frames.crossingIntensityAt(0, 0, 0), 1e-9);
    }

    @Test
    @DisplayName("the wire form round-trips within a byte's resolution, and clamps")
    void wireRoundTrip() {
        for (double t : new double[] {0.0, 0.25, 0.5, 0.75, 1.0}) {
            assertEquals(t, PortalCrossingLight.fromWire(PortalCrossingLight.toWire(t)), 1.0 / 255.0);
        }
        assertEquals(0, PortalCrossingLight.toWire(-3.0));
        assertEquals(255, PortalCrossingLight.toWire(9.0));
        assertEquals(0.0f, PortalCrossingLight.fromWire(-1));
        assertEquals(1.0f, PortalCrossingLight.fromWire(9999));
    }
}
