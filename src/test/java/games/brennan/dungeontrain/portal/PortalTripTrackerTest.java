package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link PortalTripTracker} — the dwell behind <em>Train inside a train?</em>
 * and the entry-to-exit displacement behind <em>A new way out</em>. No Minecraft bootstrap: the
 * tracker deals only in UUIDs and coordinates, and where a player <i>is</i> (room body vs corridor)
 * is decided by {@code PortalCarriageEvents.dimensionalCarriageBodyPairKey} and verified in-game.
 *
 * <p>Each test uses a fresh UUID, so the tracker's static maps cannot carry state between them.</p>
 */
final class PortalTripTrackerTest {

    private static final int PAIR = 7;

    @Test
    @DisplayName("dwell only satisfies after DWELL_SCANS consecutive scans in the room body")
    void dwellNeedsConsecutiveScans() {
        UUID player = UUID.randomUUID();
        for (int scan = 1; scan < PortalTripTracker.DWELL_SCANS; scan++) {
            assertFalse(PortalTripTracker.dwellSatisfied(PortalTripTracker.tickDwell(player, true)),
                "should not satisfy at scan " + scan);
        }
        assertTrue(PortalTripTracker.dwellSatisfied(PortalTripTracker.tickDwell(player, true)));
    }

    @Test
    @DisplayName("stepping out of the room body resets the dwell — no accumulating it at the door")
    void dwellResetsOnLeavingTheBody() {
        UUID player = UUID.randomUUID();
        for (int scan = 0; scan < PortalTripTracker.DWELL_SCANS - 1; scan++) {
            PortalTripTracker.tickDwell(player, true);
        }
        assertEquals(0, PortalTripTracker.tickDwell(player, false), "a corridor scan clears it");
        assertEquals(1, PortalTripTracker.tickDwell(player, true), "and the count starts again");
    }

    @Test
    @DisplayName("banking dwell, stepping back to the train and returning does not shortcut it")
    void dwellCannotBeBankedAcrossATripToTheTrain() {
        UUID player = UUID.randomUUID();
        // Four seconds in the room...
        for (int scan = 0; scan < PortalTripTracker.DWELL_SCANS - 2; scan++) {
            PortalTripTracker.tickDwell(player, true);
        }
        // ...then back out to the train, which is a scan not in the room body.
        PortalTripTracker.tickDwell(player, false);
        // Coming straight back must not finish the count off in a scan or two.
        for (int scan = 1; scan < PortalTripTracker.DWELL_SCANS; scan++) {
            assertFalse(PortalTripTracker.dwellSatisfied(PortalTripTracker.tickDwell(player, true)),
                "should not satisfy at scan " + scan + " of the second visit");
        }
        assertTrue(PortalTripTracker.dwellSatisfied(PortalTripTracker.tickDwell(player, true)));
    }

    @Test
    @DisplayName("dwell is held at the threshold rather than climbing forever")
    void dwellIsCapped() {
        UUID player = UUID.randomUUID();
        for (int scan = 0; scan < PortalTripTracker.DWELL_SCANS * 3; scan++) {
            PortalTripTracker.tickDwell(player, true);
        }
        assertEquals(PortalTripTracker.DWELL_SCANS, PortalTripTracker.tickDwell(player, true));
    }

    @Test
    @DisplayName("exit displacement is measured from the way in, and the trip is then over")
    void exitMeasuresFromEntry() {
        UUID player = UUID.randomUUID();
        PortalTripTracker.noteEntered(player, PAIR, 100.0, 200.0);

        OptionalDouble first = PortalTripTracker.noteExited(player, 130.0, 240.0);
        assertTrue(first.isPresent());
        assertEquals(50.0, first.getAsDouble(), 1.0e-9, "3-4-5 triangle scaled by ten");

        assertTrue(PortalTripTracker.noteExited(player, 130.0, 240.0).isEmpty(),
            "the trip was consumed by the first exit");
    }

    @Test
    @DisplayName("leaving by the corridor you came in through measures ~zero")
    void sameCorridorIsNotANewWayOut() {
        UUID player = UUID.randomUUID();
        PortalTripTracker.noteEntered(player, PAIR, -40.0, 12.0);
        OptionalDouble metres = PortalTripTracker.noteExited(player, -41.5, 12.5);
        assertTrue(metres.isPresent());
        assertFalse(PortalTripTracker.isDistantExit(metres.getAsDouble()));
    }

    @Test
    @DisplayName("walking back in replaces the trip, so the newest way in is what counts")
    void reEnteringReplacesTheTrip() {
        UUID player = UUID.randomUUID();
        PortalTripTracker.noteEntered(player, PAIR, 0.0, 0.0);
        PortalTripTracker.noteEntered(player, PAIR, 500.0, 0.0);
        OptionalDouble metres = PortalTripTracker.noteExited(player, 500.0, 10.0);
        assertTrue(metres.isPresent());
        assertEquals(10.0, metres.getAsDouble(), 1.0e-9);
    }

    @Test
    @DisplayName("an exit we never saw the way in for earns nothing rather than guessing")
    void exitWithoutEntryIsEmpty() {
        assertTrue(PortalTripTracker.noteExited(UUID.randomUUID(), 0.0, 0.0).isEmpty());
    }

    @Test
    @DisplayName("the 50-block boundary is inclusive")
    void thresholdIsInclusive() {
        assertFalse(PortalTripTracker.isDistantExit(PortalTripTracker.EXIT_DISTANCE_BLOCKS - 0.001));
        assertTrue(PortalTripTracker.isDistantExit(PortalTripTracker.EXIT_DISTANCE_BLOCKS));
    }

    @Test
    @DisplayName("dwell() reads the count without advancing it")
    void dwellReadsWithoutTicking() {
        UUID player = UUID.randomUUID();
        assertEquals(0, PortalTripTracker.dwell(player));
        PortalTripTracker.tickDwell(player, true);
        assertEquals(1, PortalTripTracker.dwell(player));
        assertEquals(1, PortalTripTracker.dwell(player), "reading must not advance the count");
    }

    @Test
    @DisplayName("the arrival edge is one scan only — the THRESHOLD photo is not asked for twice")
    void arrivalEdgeFiresOnce() {
        UUID player = UUID.randomUUID();
        int edges = 0;
        for (int scan = 0; scan < PortalTripTracker.DWELL_SCANS + 5; scan++) {
            boolean was = PortalTripTracker.dwellSatisfied(PortalTripTracker.dwell(player));
            boolean now = PortalTripTracker.dwellSatisfied(PortalTripTracker.tickDwell(player, true));
            if (now && !was) edges++;
        }
        assertEquals(1, edges, "a single visit is a single arrival");
    }

    @Test
    @DisplayName("leaving and coming back is a new arrival — a second visit earns a second photo")
    void leavingAndReturningIsANewEdge() {
        UUID player = UUID.randomUUID();
        for (int scan = 0; scan < PortalTripTracker.DWELL_SCANS; scan++) {
            PortalTripTracker.tickDwell(player, true);
        }
        assertTrue(PortalTripTracker.dwellSatisfied(PortalTripTracker.dwell(player)));

        PortalTripTracker.tickDwell(player, false); // walked back out to the train
        assertEquals(0, PortalTripTracker.dwell(player));

        for (int scan = 1; scan < PortalTripTracker.DWELL_SCANS; scan++) {
            assertFalse(PortalTripTracker.dwellSatisfied(PortalTripTracker.tickDwell(player, true)));
        }
        boolean was = PortalTripTracker.dwellSatisfied(PortalTripTracker.dwell(player));
        boolean now = PortalTripTracker.dwellSatisfied(PortalTripTracker.tickDwell(player, true));
        assertTrue(now && !was, "the second visit crosses the threshold again");
    }

    @Test
    @DisplayName("forget drops both the trip and the dwell")
    void forgetClearsEverything() {
        UUID player = UUID.randomUUID();
        PortalTripTracker.noteEntered(player, PAIR, 0.0, 0.0);
        PortalTripTracker.tickDwell(player, true);

        PortalTripTracker.forget(player);

        assertTrue(PortalTripTracker.noteExited(player, 900.0, 900.0).isEmpty());
        assertEquals(1, PortalTripTracker.tickDwell(player, true));
    }
}
