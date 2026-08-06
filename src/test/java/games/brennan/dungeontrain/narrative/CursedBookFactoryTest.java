package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.CursedStoryPool.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks down the pure half of {@link CursedBookFactory}: which pool an ending routes to, and the
 * token substitution that turns one authored template into each player's own story. The rolling half
 * needs the registry (and therefore a loaded resource pack), so it is exercised in-game at Gate 2.
 */
final class CursedBookFactoryTest {

    private static Story story(String target, int carriage, long signedTs, long landedTs, String outcome) {
        return new Story(1, target, carriage, signedTs, landedTs, outcome, null);
    }

    @Test
    @DisplayName("contextFor: each reported ending routes to its own folder")
    void outcomeRouting() {
        assertEquals(StartingBookContext.CURSED_FULFILLED,
            CursedBookFactory.contextFor("echo_killed_target"));
        assertEquals(StartingBookContext.CURSED_DEFIED,
            CursedBookFactory.contextFor("target_killed_echo"));
    }

    @Test
    @DisplayName("contextFor: no ending reported → the plain cursed pool, never a crash")
    void unknownOutcomeFallsBackToCursed() {
        assertEquals(StartingBookContext.CURSED, CursedBookFactory.contextFor(""));
        assertEquals(StartingBookContext.CURSED, CursedBookFactory.contextFor(null));
        // A future relay outcome this build doesn't know about must degrade, not explode.
        assertEquals(StartingBookContext.CURSED, CursedBookFactory.contextFor("target_fled"));
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
                List.of("a netherite axe"), List.of(), "ECHO_SLAIN_BY_YOU", 61L));
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
