package games.brennan.dungeontrain.client.moty;

import games.brennan.dungeontrain.client.moty.ModOfTheYear.Ask;
import games.brennan.dungeontrain.client.moty.ModOfTheYear.Done;
import games.brennan.dungeontrain.client.moty.ModOfTheYear.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

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
        assertFalse(ModOfTheYear.MODPACK_IS_FINALIST);
        assertFalse(ModOfTheYear.MOD_IS_FINALIST);
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

    private static Done done(boolean packNom, boolean modNom, boolean packVote, boolean modVote) {
        return new Done(packNom, modNom, packVote, modVote);
    }

    @Test
    @DisplayName("nominations ask for the modpack first, then the mod, then nothing")
    void nominationOrder() {
        assertEquals(Optional.of(Ask.NOMINATE_MODPACK),
                ModOfTheYear.nextAsk(Phase.NOMINATE, true, done(false, false, false, false), false, false));
        assertEquals(Optional.of(Ask.NOMINATE_MOD),
                ModOfTheYear.nextAsk(Phase.NOMINATE, true, done(true, false, false, false), false, false));
        assertEquals(Optional.empty(),
                ModOfTheYear.nextAsk(Phase.NOMINATE, true, done(true, true, false, false), false, false));
    }

    @Test
    @DisplayName("voting asks only for finalists, modpack before mod")
    void votingOrder() {
        Done none = done(true, true, false, false);
        assertEquals(Optional.of(Ask.VOTE_MODPACK), ModOfTheYear.nextAsk(Phase.VOTE, true, none, true, true));
        assertEquals(Optional.of(Ask.VOTE_MOD), ModOfTheYear.nextAsk(Phase.VOTE, true, none, false, true));
        assertEquals(Optional.of(Ask.VOTE_MOD),
                ModOfTheYear.nextAsk(Phase.VOTE, true, done(true, true, true, false), true, true));
        assertEquals(Optional.empty(),
                ModOfTheYear.nextAsk(Phase.VOTE, true, done(true, true, true, true), true, true));
        assertEquals(Optional.empty(), ModOfTheYear.nextAsk(Phase.VOTE, true, none, false, false));
    }

    @Test
    @DisplayName("nothing is asked of a player who never recommended, or outside the contest")
    void nothingWithoutQualifyingOrPhase() {
        Done none = done(false, false, false, false);
        assertEquals(Optional.empty(), ModOfTheYear.nextAsk(Phase.NOMINATE, false, none, true, true));
        assertEquals(Optional.empty(), ModOfTheYear.nextAsk(Phase.NONE, true, none, true, true));
    }

    @Test
    @DisplayName("each ask links its own listing")
    void projectLinks() {
        assertEquals(ModOfTheYear.MODPACK_URL, Ask.NOMINATE_MODPACK.projectUrl());
        assertEquals(ModOfTheYear.MOD_URL, Ask.NOMINATE_MOD.projectUrl());
        assertEquals(ModOfTheYear.VOTE_URL, Ask.VOTE_MOD.contestUrl());
        assertEquals(ModOfTheYear.NOMINATION_URL, Ask.NOMINATE_MOD.contestUrl());
    }
}
