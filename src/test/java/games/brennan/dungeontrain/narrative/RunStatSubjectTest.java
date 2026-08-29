package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.player.PlayerRunState;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The subjects a Faulthurst stat book can be about, and the sentences that say them.
 *
 * <p>Two properties carry the feature. The first is that <b>no subject is ever offered at a number
 * that isn't worth a sentence</b> — "You've opened 0 chests. Why?" is a worse book than no book, and
 * the floors are the only thing standing between the corpus and that line. The second is that there
 * is <b>always something to say</b>: a player who finds a book in their first seconds aboard must
 * still get a true sentence, which is why {@link RunStatSubject#PLAYTIME} is the unconditional
 * fallback.</p>
 *
 * <p>The lang-key test is the same guard {@code AdvancementLangKeysTest} applies to advancements,
 * for the same reason: en_us is the one locale nothing else validates, so a missing English line
 * here would ship a book rendering a raw {@code book.dungeontrain.statbook.…} key.</p>
 */
class RunStatSubjectTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final Path EN_US = RepoPaths.langFile("en_us");

    @Test
    @DisplayName("Every subject reads a counter and declares a floor above zero")
    void everySubjectIsWellFormed() {
        for (RunStatSubject s : RunStatSubject.values()) {
            assertNotNull(s.id(), "id");
            assertNotNull(s.format(), s.id() + " format");
            assertTrue(s.floor() >= 1, s.id() + " must not be offered at zero");
            assertEquals(s, RunStatSubject.byId(s.id()).orElse(null), "byId round-trip for " + s.id());
            // The extractor must survive a fresh run — every counter is zero there, and nothing
            // should throw on the state a player has one tick after respawning.
            assertEquals(0L, s.value(new PlayerRunState()), s.id() + " on a fresh run");
        }
    }

    @Test
    @DisplayName("A fresh run offers playtime and nothing else")
    void freshRunFallsBackToPlaytime() {
        List<RunStatSubject> eligible = RunStatSubject.eligible(new PlayerRunState());
        assertEquals(List.of(RunStatSubject.PLAYTIME), eligible,
            "a run that has done nothing has exactly one true thing to say about it");
    }

    @Test
    @DisplayName("A subject is offered only once its counter clears its floor")
    void floorsGateEligibility() {
        PlayerRunState run = new PlayerRunState();
        assertFalse(RunStatSubject.CHESTS.clearsFloor(run), "no chests opened");

        run.openedLootContainer();
        assertTrue(RunStatSubject.CHESTS.clearsFloor(run), "one chest is worth noticing");
        assertTrue(RunStatSubject.eligible(run).contains(RunStatSubject.CHESTS));

        // A floor above 1 must actually hold: two carriages of restraint is not yet a feat.
        run.recordCartMovement(2);
        assertFalse(RunStatSubject.NO_CHEST.clearsFloor(run), "floor of 3 not yet cleared");
        run.recordCartMovement(1);
        assertTrue(RunStatSubject.NO_CHEST.clearsFloor(run), "floor of 3 cleared");
    }

    @Test
    @DisplayName("Null and negative counters never reach a sentence")
    void valuesAreClamped() {
        PlayerRunState run = new PlayerRunState();
        run.advanceTravelled(-7); // walked backwards past the start
        assertEquals(7L, RunStatSubject.CARRIAGE.value(run), "distance from the start, whichever way");
        for (RunStatSubject s : RunStatSubject.values()) {
            assertTrue(s.value(null) >= 0, s.id() + " on a null run");
        }
    }

    @Test
    @DisplayName("Playtime renders time ABOARD as a duration, not a tick count")
    void playtimeRendersAsDuration() {
        PlayerRunState run = new PlayerRunState();
        // The subject reads time on the train — what the "Longest Aboard" boards report — not
        // wall-clock since spawn. Seeding runTicks here would render 0s.
        run.addTrainTimeTicks(20L * (2 * 3600 + 14 * 60)); // 2h 14m
        assertEquals("2h 14m", RunStatSubject.PLAYTIME.rendered(RunStatSubject.PLAYTIME.value(run)));
    }

    @Test
    @DisplayName("Every stat line English needs is in en_us.json")
    void everyStatLineExistsInEnglish() throws IOException {
        assertTrue(Files.isRegularFile(EN_US), "missing " + EN_US);
        JsonObject lang = JsonParser.parseString(
            Files.readString(EN_US, StandardCharsets.UTF_8)).getAsJsonObject();

        List<String> missing = new ArrayList<>();
        for (RunStatSubject s : RunStatSubject.values()) {
            if (s.format() == RunStatSubject.Format.COUNT) {
                // en_us is a one/other language — PluralRules will never ask it for anything else.
                for (String category : PluralRules.categoriesOf("en_us")) {
                    require(lang, s.key() + "." + category, missing);
                }
            } else {
                require(lang, s.key(), missing);
            }
        }
        for (int i = 0; i < RunStatBookFactory.OPENER_COUNT; i++) {
            require(lang, RunStatSubject.KEY_ROOT + "open." + i, missing);
        }
        for (int i = 0; i < RunStatBookFactory.TAIL_COUNT; i++) {
            require(lang, RunStatSubject.KEY_ROOT + "tail." + i, missing);
        }
        assertTrue(missing.isEmpty(), "missing English lines: " + missing);
    }

    @Test
    @DisplayName("Every stat line carries the %s the number goes in")
    void everyStatLineTakesItsNumber() throws IOException {
        JsonObject lang = JsonParser.parseString(
            Files.readString(EN_US, StandardCharsets.UTF_8)).getAsJsonObject();
        for (RunStatSubject s : RunStatSubject.values()) {
            List<String> keys = s.format() == RunStatSubject.Format.COUNT
                ? PluralRules.categoriesOf("en_us").stream().map(c -> s.key() + "." + c).toList()
                : List.of(s.key());
            for (String key : keys) {
                if (!lang.has(key)) continue; // reported by the test above
                assertTrue(lang.get(key).getAsString().contains("%s"),
                    key + " must have somewhere to put the number");
            }
        }
    }

    @Test
    @DisplayName("Every run-scoped leaderboard board has a subject that reports the reader's own number")
    void everyRunBoardIsCovered() {
        List<String> uncovered = new ArrayList<>();
        for (LeaderboardCategory board : LeaderboardCategory.values()) {
            if (board.scope() != LeaderboardCategory.Scope.RUN) continue;
            boolean covered = false;
            for (RunStatSubject s : RunStatSubject.values()) {
                if (board.base().equals(s.boardBase())) { covered = true; break; }
            }
            if (!covered) uncovered.add(board.id());
        }
        // A board ranks players against each other; this book tells one player where they stand on
        // their own. Adding the first without the second leaves a measured thing a player can be
        // ranked on but never told about.
        assertTrue(uncovered.isEmpty(),
            "run-scoped boards with no stat-book subject: " + uncovered);
    }

    @Test
    @DisplayName("No two subjects claim the same board")
    void boardClaimsAreUnique() {
        List<String> claimed = new ArrayList<>();
        for (RunStatSubject s : RunStatSubject.values()) {
            if (s.boardBase() == null) continue;
            assertFalse(claimed.contains(s.boardBase()),
                s.id() + " re-claims the board " + s.boardBase());
            claimed.add(s.boardBase());
            assertTrue(LeaderboardCategory.byId(s.boardBase()).isPresent()
                    || anyBoardHasBase(s.boardBase()),
                s.id() + " claims a board that does not exist: " + s.boardBase());
        }
    }

    private static boolean anyBoardHasBase(String base) {
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            if (c.base().equals(base)) return true;
        }
        return false;
    }

    @Test
    @DisplayName("The worst-case English page still fits one written-book page")
    void englishFitsOnOnePage() throws IOException {
        JsonObject lang = JsonParser.parseString(
            Files.readString(EN_US, StandardCharsets.UTF_8)).getAsJsonObject();

        int longestOpener = 0;
        for (int i = 0; i < RunStatBookFactory.OPENER_COUNT; i++) {
            longestOpener = Math.max(longestOpener, length(lang, RunStatSubject.KEY_ROOT + "open." + i));
        }
        int longestTail = 0;
        for (int i = 0; i < RunStatBookFactory.TAIL_COUNT; i++) {
            longestTail = Math.max(longestTail, length(lang, RunStatSubject.KEY_ROOT + "tail." + i));
        }
        int longestStat = 0;
        for (RunStatSubject sub : RunStatSubject.values()) {
            if (sub.format() == RunStatSubject.Format.COUNT) {
                for (String c : PluralRules.categoriesOf("en_us")) {
                    longestStat = Math.max(longestStat, length(lang, sub.key() + "." + c));
                }
            } else {
                longestStat = Math.max(longestStat, length(lang, sub.key()));
            }
        }

        // Worst case: the longest of each, the two blank-line separators, and a generous number
        // standing in for the "%s" every line carries.
        int worst = longestOpener + longestStat + longestTail + 4 + 12;
        assertTrue(worst <= BookFactory.MAX_CHARS_PER_PAGE,
            "worst-case English page is " + worst + " chars, over the "
                + BookFactory.MAX_CHARS_PER_PAGE + " budget — it would spill onto a second page");
    }

    private static int length(JsonObject lang, String key) {
        return lang.has(key) ? lang.get(key).getAsString().length() : 0;
    }

    private static void require(JsonObject lang, String key, List<String> missing) {
        if (!lang.has(key) || lang.get(key).getAsString().isBlank()) missing.add(key);
    }
}
