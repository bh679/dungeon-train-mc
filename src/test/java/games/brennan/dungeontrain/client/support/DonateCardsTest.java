package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.support.DonateCards.Arm;
import games.brennan.dungeontrain.client.support.DonateCards.Availability;
import games.brennan.dungeontrain.client.support.DonateCards.Card;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What each arm draws — the table that decides what every player sees. */
class DonateCardsTest {

    @Test
    void controlIsThePageAsItShipped() {
        assertSame(Arm.A_COVERED_UPDATES, DonateCards.CONTROL);
        assertEquals(List.of(Card.COVERED, Card.UPDATES),
                DonateCards.slots(DonateCards.CONTROL, Availability.all()),
                "control must reproduce the grid this page has always had");
    }

    @Test
    void everyArmDrawsExactlyTwoCards() {
        // The grid has four cells; the ask and Contribute hold two of them.
        for (Arm arm : Arm.values()) {
            assertEquals(2, DonateCards.slots(arm, Availability.all()).size(), arm.id());
        }
    }

    @Test
    void everyCardAppearsInExactlyTwoArms() {
        // The property that makes the results readable: each card is seen by 40% of players, so
        // its marginal effect is the two arms carrying it against the three that don't.
        Map<Card, Integer> seen = new EnumMap<>(Card.class);
        for (Arm arm : Arm.values()) {
            for (Card c : arm.cards()) seen.merge(c, 1, Integer::sum);
        }
        assertEquals(Card.values().length, seen.size(), "every card must be tested");
        for (Map.Entry<Card, Integer> e : seen.entrySet()) {
            assertEquals(2, e.getValue(), e.getKey() + " should appear in exactly two arms");
        }
    }

    @Test
    void noPairOfCardsRepeats() {
        // Two cards that always appeared together could never be told apart.
        Set<Set<Card>> pairs = new HashSet<>();
        for (Arm arm : Arm.values()) {
            assertTrue(pairs.add(new HashSet<>(arm.cards())), "duplicate pair in " + arm.id());
        }
    }

    @Test
    void noArmDrawsTheSameCardTwice() {
        for (Arm arm : Arm.values()) {
            assertEquals(2, new HashSet<>(arm.cards()).size(), arm.id());
        }
    }

    @Test
    void anUnknownFigureLeavesItsCellEmptyRatherThanSubstituting() {
        // Substituting would make the arm's identity depend on what data happened to be available:
        // a player recorded in c_hours_active would have been shown something else entirely, and
        // the funnel row would describe a page nobody was assigned.
        Availability noHours = new Availability(true, true, false, true, true);
        assertEquals(List.of(Card.LAST_ACTIVE), DonateCards.slots(Arm.C_HOURS_ACTIVE, noHours));

        Availability noneKnown = new Availability(false, false, false, false, false);
        assertEquals(List.of(), DonateCards.slots(Arm.C_HOURS_ACTIVE, noneKnown));
    }

    @Test
    void anUnrecognisedOrMissingArmIdFallsBackToControl() {
        // A relay may name an arm a newer jar draws; this one must not guess.
        assertSame(DonateCards.CONTROL, DonateCards.armOf("f_something_new"));
        assertSame(DonateCards.CONTROL, DonateCards.armOf(null));
        assertSame(DonateCards.CONTROL, DonateCards.armOf(""));
    }

    @Test
    void armIdsRoundTrip() {
        for (Arm arm : Arm.values()) {
            assertSame(arm, DonateCards.armOf(arm.id()));
            assertTrue(DonateCards.knownArms().contains(arm.id()));
        }
        assertEquals(Arm.values().length, DonateCards.knownArms().size());
    }

    @Test
    void armIdsMatchTheRelayDefinition() {
        // dp-relay experiments.js DEFAULT_EXPERIMENT. An id that drifted on either side would make
        // the relay's arm unrenderable here and quietly send those players to control.
        assertEquals(List.of("a_covered_updates", "b_updates_hours", "c_hours_active",
                        "d_active_raised", "e_raised_covered"),
                DonateCards.knownArms());
    }
}
