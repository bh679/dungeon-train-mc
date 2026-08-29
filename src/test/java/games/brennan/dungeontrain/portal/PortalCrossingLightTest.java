package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for {@link PortalCrossingLight} — the single train-to-room transition a portal
 * corridor carries its lighting across.
 *
 * <p>Two properties matter, and they pull in different directions. The ramp must read the same on
 * both sides of a swap, or the swap pops; and it must never fall anywhere along the walk, or the
 * player feels two transitions per carriage instead of one. A constant satisfies the first and is
 * useless, which is why {@link #rampNeverFalls} and {@link #rampSpansTheCorridor} are here beside
 * {@link #survivesASwap}.</p>
 */
final class PortalCrossingLightTest {

    private static PortalCarriageLayout layout(int length) {
        return new PortalCarriageLayout(length, 7, 7);
    }

    /**
     * The two ends of the ramp. This is the shape the whole feature is about: the train-side door
     * plane reads as the world the train is running in, the room-side one as the room, and the walk
     * between them is the transition.
     */
    @Test
    @DisplayName("the ramp runs from nothing at the train door to full at the room door")
    void rampSpansTheCorridor() {
        for (int length : new int[] {7, 9, 13, 16}) {
            PortalCarriageLayout l = layout(length);

            // ENTRY puts the train at low local X, EXIT at high — the mirror PortalFacing carries.
            assertEquals(PortalCrossingLight.OFF,
                PortalCrossingLight.intensityAt(l.nearDoorX() + 0.5, l, PortalCarriageRole.ENTRY),
                1e-9, "ENTRY train door at length " + length);
            assertEquals(1.0,
                PortalCrossingLight.intensityAt(l.farDoorX() + 0.5, l, PortalCarriageRole.ENTRY),
                1e-9, "ENTRY room door at length " + length);

            assertEquals(PortalCrossingLight.OFF,
                PortalCrossingLight.intensityAt(l.farDoorX() + 0.5, l, PortalCarriageRole.EXIT),
                1e-9, "EXIT train door at length " + length);
            assertEquals(1.0,
                PortalCrossingLight.intensityAt(l.nearDoorX() + 0.5, l, PortalCarriageRole.EXIT),
                1e-9, "EXIT room door at length " + length);
        }
    }

    /**
     * <b>The doorways are flat.</b> The ramp runs across the corridor's interior —
     * {@link PortalFacing#FIRST_RAMP_BLOCK} to {@link PortalFacing#lastRampBlock}, the same span the
     * facing sweep uses — so it is already finished a block before the room and the last step
     * through the door changes nothing. A ramp that only reached full <i>at</i> the room door put its
     * final slice of change in exactly the step where it is most visible.
     */
    @Test
    @DisplayName("the transition is over a block before the room, and has not begun a block after the train")
    void rampIsFlatThroughBothDoorways() {
        for (int length : new int[] {7, 9, 13, 16}) {
            PortalCarriageLayout l = layout(length);
            PortalCarriageRole role = PortalCarriageRole.ENTRY;

            assertEquals(PortalCrossingLight.OFF,
                PortalCrossingLight.intensityAt(PortalFacing.FIRST_RAMP_BLOCK + 0.5, l, role),
                1e-9, "first ramp block should still be off at length " + length);
            assertEquals(1.0,
                PortalCrossingLight.intensityAt(PortalFacing.lastRampBlock(length) + 0.5, l, role),
                1e-9, "last ramp block should be full at length " + length);
        }
    }

    /**
     * <b>One transition, not two.</b> The bug this pins is the shape the ramp used to have: it held
     * at full across the middle and fell away at both ends, so a player walking a corridor crossed
     * it twice — up on the way in and down on the way out — and felt two lighting changes per
     * carriage where the place has one boundary to cross. Any fall anywhere along the walk is that
     * bug coming back.
     */
    @Test
    @DisplayName("the ramp never falls anywhere along the corridor, in either role")
    void rampNeverFalls() {
        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            for (int length : new int[] {7, 9, 13, 16}) {
                PortalCarriageLayout l = layout(length);
                // Walked in the direction the player does: from the train door toward the room.
                int from = role == PortalCarriageRole.ENTRY ? l.nearDoorX() : l.farDoorX();
                int step = role == PortalCarriageRole.ENTRY ? 1 : -1;

                double previous = -1.0;
                for (int i = 0; i < length; i++) {
                    int x = from + i * step;
                    double t = PortalCrossingLight.intensityAt(x + 0.5, l, role);
                    assertTrue(t >= previous,
                        "fell at block " + x + " (" + role + ", length " + length + ")");
                    assertTrue(t >= 0.0 && t <= 1.0,
                        "out of range at block " + x + " (" + role + ", length " + length + ")");
                    previous = t;
                }
                assertEquals(1.0, previous, 1e-9,
                    "did not reach the room end (" + role + ", length " + length + ")");
            }
        }
    }

    /**
     * The two roles are the same ramp read from opposite ends — the mirror
     * {@link PortalFacing#axisTowardRoom} carries, and what lets a player walk train → room → train
     * without the lighting running backwards under them halfway.
     */
    @Test
    @DisplayName("the roles mirror each other about the corridor's middle")
    void rolesMirror() {
        PortalCarriageLayout l = layout(13);
        for (int x = 0; x < l.length(); x++) {
            assertEquals(
                PortalCrossingLight.intensityAt(x + 0.5, l, PortalCarriageRole.ENTRY),
                PortalCrossingLight.intensityAt((l.length() - 1 - x) + 0.5, l, PortalCarriageRole.EXIT),
                1e-9, "block " + x + " does not mirror");
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
        double at = PortalCrossingLight.intensityAt(1.01, l, PortalCarriageRole.ENTRY);
        assertEquals(at, PortalCrossingLight.intensityAt(1.5, l, PortalCarriageRole.ENTRY), 1e-9);
        assertEquals(at, PortalCrossingLight.intensityAt(1.99, l, PortalCarriageRole.ENTRY), 1e-9);
    }

    /** Positions past either end clamp to the end block rather than running negative or past 1. */
    @Test
    @DisplayName("positions outside the corridor clamp to its end blocks")
    void clampsOutsideTheCorridor() {
        PortalCarriageLayout l = layout(9);
        PortalCarriageRole role = PortalCarriageRole.ENTRY;
        assertEquals(PortalCrossingLight.intensityAt(0.5, l, role),
            PortalCrossingLight.intensityAt(-4.0, l, role), 1e-9);
        assertEquals(PortalCrossingLight.intensityAt(l.farDoorX() + 0.5, l, role),
            PortalCrossingLight.intensityAt(l.length() + 4.0, l, role), 1e-9);
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
