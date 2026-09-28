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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void everyFixedArmDrawsExactlyTwoCards() {
        // The grid has four cells; the ask and Contribute hold two of them.
        for (Arm arm : DonateCards.FIXED) {
            assertEquals(2, DonateCards.slots(arm, Availability.all()).size(), arm.id());
        }
    }

    @Test
    void everyCardAppearsInExactlyTwoFixedArms() {
        // The property that makes the results readable: each card is seen by 40% of players, so
        // its marginal effect is the two arms carrying it against the three that don't.
        Map<Card, Integer> seen = new EnumMap<>(Card.class);
        for (Arm arm : DonateCards.FIXED) {
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
        for (Arm arm : DonateCards.FIXED) {
            assertTrue(pairs.add(new HashSet<>(arm.cards())), "duplicate pair in " + arm.id());
        }
    }

    @Test
    void noArmDrawsTheSameCardTwice() {
        for (Arm arm : DonateCards.FIXED) {
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
                        "d_active_raised", "e_raised_covered", "f_rotating"),
                DonateCards.knownArms());
    }

    // ---- the rotating arm ----

    @Test
    void theRotatingArmWalksEveryPairBeforeRepeatingOne() {
        // Its whole treatment is variety, so a cycle that revisited a pair early — or skipped one —
        // would be testing something quieter than the thing we think we are testing.
        Set<Arm> seen = new HashSet<>();
        for (int death = 0; death < DonateCards.FIXED.size(); death++) {
            assertTrue(seen.add(DonateCards.pairFor(death, 0)), "pair repeated within one cycle");
        }
        assertEquals(new HashSet<>(DonateCards.FIXED), seen, "every pair must come up");
    }

    @Test
    void theRotationCyclesAndSurvivesOddIndices() {
        assertSame(DonateCards.pairFor(0, 0), DonateCards.pairFor(5, 0), "the cycle closes");
        assertSame(DonateCards.pairFor(1, 0), DonateCards.pairFor(6, 0));
        // A wrapped counter or an odd offset must still land on a real pair, not throw.
        assertNotNull(DonateCards.pairFor(-3, 0));
        assertNotNull(DonateCards.pairFor(Integer.MIN_VALUE, 4));
        assertNotNull(DonateCards.pairFor(Integer.MAX_VALUE, 4));
    }

    @Test
    void theOffsetSpreadsWherePlayersStart() {
        // Otherwise every rotating player's FIRST death — their most-attended-to look at the page —
        // is the same pair, and that pair quietly becomes what the arm is measuring.
        Set<Arm> firsts = new HashSet<>();
        for (int offset = 0; offset < DonateCards.FIXED.size(); offset++) {
            firsts.add(DonateCards.pairFor(0, offset));
        }
        assertEquals(DonateCards.FIXED.size(), firsts.size(), "each offset starts somewhere else");
    }

    @Test
    void aFixedArmIgnoresTheRotationEntirely() {
        for (Arm arm : DonateCards.FIXED) {
            assertSame(arm, DonateCards.drawnPair(arm, 3, 2), arm.id());
        }
    }

    @Test
    void theRotatingArmHasNoPairOfItsOwn() {
        assertTrue(Arm.F_ROTATING.rotating());
        assertEquals(List.of(), Arm.F_ROTATING.cards());
        assertEquals(List.of(), DonateCards.slots(Arm.F_ROTATING, Availability.all()),
                "it must be resolved through drawnPair before it can be drawn");
        for (Arm arm : DonateCards.FIXED) assertFalse(arm.rotating(), arm.id());
    }

    @Test
    void theRotatingArmDrawsARealPair() {
        Arm drawn = DonateCards.drawnPair(Arm.F_ROTATING, 2, 1);
        assertTrue(DonateCards.FIXED.contains(drawn));
        assertEquals(2, DonateCards.slots(drawn, Availability.all()).size());
    }
}
