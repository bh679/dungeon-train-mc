package games.brennan.dungeontrain.client.moty;

import games.brennan.dungeontrain.client.moty.ModOfTheYear.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModOfTheYearTest {

    private static LocalDate day(int month, int dayOfMonth) {
        return LocalDate.of(2026, month, dayOfMonth);
    }

    @Test
    @DisplayName("nominations run Oct 26 to Nov 9, both days included")
    void nominationWindow() {
        assertEquals(Phase.NONE, ModOfTheYear.phaseOn(day(10, 25), true));
        assertEquals(Phase.NOMINATE, ModOfTheYear.phaseOn(day(10, 26), true));
        assertEquals(Phase.NOMINATE, ModOfTheYear.phaseOn(day(11, 1), false));
        assertEquals(Phase.NOMINATE, ModOfTheYear.phaseOn(day(11, 9), true));
        assertEquals(Phase.NONE, ModOfTheYear.phaseOn(day(11, 10), true));
    }

    @Test
    @DisplayName("voting runs Dec 7 to Dec 17, but only once Dungeon Train is a finalist")
    void votingWindowNeedsFinalist() {
        assertEquals(Phase.NONE, ModOfTheYear.phaseOn(day(12, 6), true));
        assertEquals(Phase.VOTE, ModOfTheYear.phaseOn(day(12, 7), true));
        assertEquals(Phase.VOTE, ModOfTheYear.phaseOn(day(12, 17), true));
        assertEquals(Phase.NONE, ModOfTheYear.phaseOn(day(12, 18), true));
        assertEquals(Phase.NONE, ModOfTheYear.phaseOn(day(12, 10), false));
    }

    @Test
    @DisplayName("the shipped build has the vote phase off until the finalists are announced")
    void shipsWithVotingOff() {
        assertFalse(ModOfTheYear.DT_IS_FINALIST);
        assertEquals(Phase.NONE, ModOfTheYear.phaseOn(day(12, 10)));
    }

    @Test
    @DisplayName("8, 9 and 10 are recommendations; 7 is not")
    void recommendationThreshold() {
        assertFalse(ModOfTheYear.isRecommendation(-1));
        assertFalse(ModOfTheYear.isRecommendation(7));
        assertTrue(ModOfTheYear.isRecommendation(8));
        assertTrue(ModOfTheYear.isRecommendation(9));
        assertTrue(ModOfTheYear.isRecommendation(10));
    }

    @Test
    @DisplayName("a player qualifies from the sticky flag, or from a last answer given before the flag existed")
    void qualification() {
        assertTrue(ModOfTheYear.qualifies(true, 3));
        assertTrue(ModOfTheYear.qualifies(false, 9));
        assertFalse(ModOfTheYear.qualifies(false, 7));
        assertFalse(ModOfTheYear.qualifies(false, -1));
    }

    @Test
    @DisplayName("each phase is asked until the player has done that phase")
    void askedUntilDone() {
        assertTrue(ModOfTheYear.shouldAsk(Phase.NOMINATE, true, false, false));
        assertFalse(ModOfTheYear.shouldAsk(Phase.NOMINATE, true, true, false));
        assertTrue(ModOfTheYear.shouldAsk(Phase.VOTE, true, true, false));
        assertFalse(ModOfTheYear.shouldAsk(Phase.VOTE, true, true, true));
        assertFalse(ModOfTheYear.shouldAsk(Phase.NOMINATE, false, false, false));
        assertFalse(ModOfTheYear.shouldAsk(Phase.NONE, true, false, false));
    }
}
