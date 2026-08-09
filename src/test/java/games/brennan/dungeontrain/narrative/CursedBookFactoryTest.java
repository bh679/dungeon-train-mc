package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.CursedStoryPool.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the pure half of {@link CursedBookFactory}: which pool an ending routes to, and the
 * token substitution that turns one authored template into each player's own story. The rolling half
 * needs the registry (and therefore a loaded resource pack), so it is exercised in-game at Gate 2.
 */
final class CursedBookFactoryTest {

    private static Story story(String target, int carriage, long signedTs, long landedTs, String outcome) {
        return new Story(1, target, carriage, signedTs, landedTs, outcome, null, NoteKind.DEATH);
    }

    @Test
    @DisplayName("contextFor: each reported ending routes to its own folder")
    void outcomeRouting() {
        assertEquals(StartingBookContext.CURSED_FULFILLED,
            CursedBookFactory.contextFor(NoteKind.DEATH, "echo_killed_target"));
        assertEquals(StartingBookContext.CURSED_DEFIED,
            CursedBookFactory.contextFor(NoteKind.DEATH, "target_killed_echo"));
    }

    @Test
    @DisplayName("contextFor: no ending reported → the plain cursed pool, never a crash")
    void unknownOutcomeFallsBackToCursed() {
        assertEquals(StartingBookContext.CURSED, CursedBookFactory.contextFor(NoteKind.DEATH, ""));
        assertEquals(StartingBookContext.CURSED, CursedBookFactory.contextFor(NoteKind.DEATH, null));
        // A future relay outcome this build doesn't know about must degrade, not explode.
        assertEquals(StartingBookContext.CURSED, CursedBookFactory.contextFor(NoteKind.DEATH, "target_fled"));
    }

    @Test
    @DisplayName("contextFor: a Love Note never routes to curse prose")
    void loveRoutesToItsOwnFolders() {
        // The whole point of the kind: before it existed, a landed Love Note would have handed its
        // author a book saying the target was dead and they had finished what they started.
        assertEquals(StartingBookContext.LOVED_TURNED,
            CursedBookFactory.contextFor(NoteKind.LOVE, "echo_killed_target"));
        assertEquals(StartingBookContext.LOVED_BETRAYED,
            CursedBookFactory.contextFor(NoteKind.LOVE, "target_killed_echo"));
        assertEquals(StartingBookContext.LOVED,
            CursedBookFactory.contextFor(NoteKind.LOVE, ""));
        assertEquals(StartingBookContext.LOVED,
            CursedBookFactory.contextFor(NoteKind.LOVE, null));
        assertEquals(StartingBookContext.LOVED,
            CursedBookFactory.contextFor(NoteKind.LOVE, "target_fled"));
    }

    @Test
    @DisplayName("contextFor: the same ending means opposite things to the two kinds")
    void identicalOutcomesNeverShareAFolder() {
        // echo_killed_target is a curse running its course, but for a Love Note it can only happen
        // when the target attacked first — so no outcome may ever resolve to the same folder.
        for (String outcome : new String[] {"echo_killed_target", "target_killed_echo", "", null}) {
            StartingBookContext death = CursedBookFactory.contextFor(NoteKind.DEATH, outcome);
            StartingBookContext love = CursedBookFactory.contextFor(NoteKind.LOVE, outcome);
            assertNotEquals(death, love, "outcome " + outcome + " routed both kinds to " + death);
            assertTrue(death.isCursed(), outcome + " → " + death + " is not a cursed pool");
            assertTrue(love.isLoved(), outcome + " → " + love + " is not a loved pool");
        }
    }

    @Test
    @DisplayName("contextFor: a null kind is the curse — what every landed note used to be")
    void nullKindDegradesToTheCurse() {
        assertEquals(StartingBookContext.CURSED, CursedBookFactory.contextFor(null, ""));
        assertEquals(StartingBookContext.CURSED_FULFILLED,
            CursedBookFactory.contextFor(null, "echo_killed_target"));
    }

    @Test
    @DisplayName("fill: substitutes target, carriage and the days the echo waited")
    void fillsTokens() {
        long day = TimeUnit.DAYS.toMillis(1);
        Story s = story("Steve", 42, 1_000_000L, 1_000_000L + 3 * day, "echo_killed_target");
        assertEquals("Steve waited at carriage 42 for 3 days",
            CursedBookFactory.fill("%TARGET% waited at carriage %CARRIAGE% for %DAYS% days", s));
    }

    @Test
    @DisplayName("fill: every occurrence is replaced, and a blank target gets a stand-in name")
    void fillsRepeatsAndMissingTarget() {
        Story s = story("  ", -3, 0L, 0L, "");
        assertEquals("someone. someone. carriage -3",
            CursedBookFactory.fill("%TARGET%. %TARGET%. carriage %CARRIAGE%", s));
    }

    @Test
    @DisplayName("fill: null / empty templates are empty strings, not nulls")
    void fillHandlesEmptyTemplates() {
        Story s = story("Steve", 1, 0L, 0L, "");
        assertEquals("", CursedBookFactory.fill(null, s));
        assertEquals("", CursedBookFactory.fill("", s));
    }

    @Test
    @DisplayName("fill: %STORY%/%GEAR%/%ENDING% render the encounter in second person")
    void fillsEncounterTokens() {
        Story s = new Story(1, "Alex", 20, 0L, 0L, "target_killed_echo",
            new CursedStoryPool.Encounter(List.of("SPAWNED", "MET", "PLAYER_STRUCK_ECHO"),
                List.of("a netherite axe"), List.of(), "ECHO_SLAIN_BY_YOU", 61L), NoteKind.DEATH);
        assertEquals("It still carried a netherite axe. They crossed paths. Alex struck first. Alex killed it.",
            CursedBookFactory.fill("%STORY%", s));
        assertEquals("It still carried a netherite axe.", CursedBookFactory.fill("%GEAR%", s));
        assertEquals("Alex killed it.", CursedBookFactory.fill("%ENDING%", s));
    }

    @Test
    @DisplayName("fill: with no journal the encounter tokens vanish without leaving debris")
    void fillsEncounterTokensWithoutAJournal() {
        // An older relay (or a fight nobody journaled) must not leave "%STORY%" or a hole full of
        // spaces in the middle of a page — a variant has to still read.
        Story s = story("Alex", 20, 0L, 0L, "");
        assertEquals("", CursedBookFactory.fill("%STORY%", s));
        assertEquals("They found each other at carriage 20.",
            CursedBookFactory.fill("They found each other %STORY% at carriage %CARRIAGE%. %ENDING%", s));
        // The ending still falls back to the bare reported outcome when that is all there is.
        assertEquals("Alex killed it.",
            CursedBookFactory.fill("%ENDING%", story("Alex", 20, 0L, 0L, "target_killed_echo")));
    }

    @Test
    @DisplayName("fill: page breaks and line breaks survive an empty token")
    void fillPreservesAuthoredLayout() {
        Story s = story("Alex", 3, 0L, 0L, "");
        // \n\n is a page break in a starting book — tidying whitespace must never eat one.
        assertEquals("page one\n\nline\nline\n\npage three",
            CursedBookFactory.fill("page one\n\nline %STORY%\nline\n\npage three", s));
    }

    @Test
    @DisplayName("waitedDays: whole days only, and never negative on missing or crossed timestamps")
    void waitedDaysClamps() {
        long day = TimeUnit.DAYS.toMillis(1);
        assertEquals(2, CursedBookFactory.waitedDays(story("S", 1, 0L + day, 3 * day, "")));
        assertEquals(0, CursedBookFactory.waitedDays(story("S", 1, day, day + 1000L, ""))); // same day
        assertEquals(0, CursedBookFactory.waitedDays(story("S", 1, 0L, 3 * day, "")));      // no sign time
        assertEquals(0, CursedBookFactory.waitedDays(story("S", 1, 3 * day, 0L, "")));      // never landed?
        assertEquals(0, CursedBookFactory.waitedDays(story("S", 1, 5 * day, 3 * day, ""))); // clocks disagree
    }
}
