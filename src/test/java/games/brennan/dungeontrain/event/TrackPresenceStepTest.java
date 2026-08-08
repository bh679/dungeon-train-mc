package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.TrackPresenceEvents.PresenceStep;
import games.brennan.dungeontrain.event.TrackPresenceEvents.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link TrackPresenceEvents#step} — the leave/return state machine behind
 * <em>Off the Rails</em>, <em>Back on Board</em> and {@code landed_on_tracks}. No Minecraft
 * bootstrap: the four position reads (carriage AABB, rail bed, corridor edge, portal structure) are
 * supplied as arguments and verified in-game, so this table pins only what a given observation
 * means. Same style as {@link PlayerMobReboarderTest}.
 *
 * <p>The portal cases are the point of the file: a hallway portal puts the player at the world
 * floor, which reads as off every carriage, and without the gate a round trip through a door awards
 * both markers.</p>
 */
final class TrackPresenceStepTest {

    /** Grace is {@code OFF_GRACE_SCANS = 2}, so a departure latches on the second off-carriage scan. */
    private static final int GRACE = 2;

    /** A player who has been riding: aboard, nothing latched. */
    private static State aboard() {
        return new State(true, true, 0, false);
    }

    /** One scan while standing in a carriage. */
    private static PresenceStep onCarriage(State s) {
        return TrackPresenceEvents.step(s, true, false, false, false);
    }

    /** One scan while off the carriage and clear of the corridor — a genuine departure. */
    private static PresenceStep offSide(State s) {
        return TrackPresenceEvents.step(s, false, false, true, false);
    }

    /** One scan while inside a hallway-portal structure. */
    private static PresenceStep inPortal(State s) {
        return TrackPresenceEvents.step(s, false, false, true, true);
    }

    // ---- the ordinary path still works ----

    @Test
    @DisplayName("standing on the rail bed → landed_on_tracks")
    void railBed_grantsLandedOnTracks() {
        PresenceStep out = TrackPresenceEvents.step(State.INITIAL, false, true, false, false);
        assertTrue(out.landedOnTracks());
        assertTrue(out.next().wasOnTrainOrTracks());
    }

    @Test
    @DisplayName("off the corridor after riding → left_train")
    void offCorridor_grantsLeftTrain() {
        assertTrue(offSide(aboard()).leftTrain());
    }

    @Test
    @DisplayName("never aboard and never on the tracks → left_train stays latched shut")
    void neverAboard_noLeftTrain() {
        assertFalse(offSide(State.INITIAL).leftTrain());
    }

    @Test
    @DisplayName("jump off, wait out the grace, climb back on → returned_to_train")
    void realDeparture_thenReboard_grantsReturn() {
        State s = aboard();
        for (int i = 0; i < GRACE; i++) {
            s = offSide(s).next();
        }
        assertTrue(s.leftCarriageSinceAboard(), "departure should latch at the grace bound");

        PresenceStep back = onCarriage(s);
        assertTrue(back.returnedToTrain());
        assertFalse(back.next().leftCarriageSinceAboard(), "the latch clears on re-boarding");
    }

    @Test
    @DisplayName("a one-scan hop off the deck does not latch a departure")
    void briefHop_doesNotLatch() {
        PresenceStep hop = offSide(aboard());
        assertFalse(hop.next().leftCarriageSinceAboard());
        assertFalse(onCarriage(hop.next()).returnedToTrain());
    }

    // ---- hallway portals ----

    @Test
    @DisplayName("a scan inside a portal grants nothing and changes nothing")
    void inPortal_isInert() {
        State before = aboard();
        PresenceStep out = inPortal(before);
        assertFalse(out.landedOnTracks());
        assertFalse(out.returnedToTrain());
        assertFalse(out.leftTrain());
        assertEquals(before, out.next(), "state must be frozen, not advanced");
    }

    @Test
    @DisplayName("the whole round trip through a portal awards neither marker")
    void portalRoundTrip_awardsNothing() {
        State s = aboard();
        // Ten scans in the room is five seconds — well past the two-scan grace that would otherwise
        // have latched a departure on the way in.
        for (int i = 0; i < 10; i++) {
            PresenceStep out = inPortal(s);
            assertFalse(out.leftTrain(), "no Off the Rails while in the room");
            s = out.next();
        }
        PresenceStep back = onCarriage(s);
        assertFalse(back.returnedToTrain(), "no Back on Board on stepping out of the portal");
    }

    @Test
    @DisplayName("a portal trip does not cost a later, genuine departure")
    void portalTrip_leavesTheMachineIntact() {
        State s = onCarriage(inPortal(aboard()).next()).next();
        assertTrue(s.hasBeenAboard(), "freezing must not clear the aboard history");

        for (int i = 0; i < GRACE; i++) {
            s = offSide(s).next();
        }
        assertTrue(onCarriage(s).returnedToTrain(), "a real jump-off still earns the marker");
    }
}
