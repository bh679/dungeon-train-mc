package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.player.PlayerRunState;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composition of the Faulthurst stat book: which three sentences a seed produces, and what a book
 * says before it has met anyone.
 *
 * <p>The properties worth pinning are the ones a reader would notice breaking. A given book's
 * WORDING must never move — a note that opened with "I see." yesterday cannot open with a question
 * today, because it is a physical object the player is carrying. And a book baked by a container has
 * no reader yet, so it must say nothing about a run it cannot have measured.</p>
 *
 * <p>Text is asserted through {@link Component#getString()}, which with no language loaded returns
 * the translation key. That is exactly what these tests want to see: they are about WHICH line is
 * chosen, not about the English in it (that is {@code RunStatSubjectTest}'s job).</p>
 */
class RunStatBookFactoryTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String READER = "Brennan";

    private static String render(long seed, RunStatSubject subject, long value) {
        return render(seed, subject, value, READER);
    }

    private static String render(long seed, RunStatSubject subject, long value, String name) {
        List<Component> pages = RunStatBookFactory.pages(seed, subject, "en_us", value, name);
        assertEquals(1, pages.size(), "a stat book is one page");
        return pages.get(0).getString();
    }

    @Test
    @DisplayName("A book with a subject says opener, stat and follow-up")
    void composesThreeSentences() {
        String page = render(1L, RunStatSubject.CHESTS, 14L);
        String[] parts = page.split("\n\n");
        assertEquals(3, parts.length, "opener, stat, follow-up: " + page);
        assertTrue(parts[0].startsWith(RunStatSubject.KEY_ROOT + "open."), parts[0]);
        assertTrue(parts[1].startsWith(RunStatSubject.CHESTS.key()), parts[1]);
        assertTrue(parts[2].startsWith(RunStatSubject.KEY_ROOT + "tail."), parts[2]);
    }

    @Test
    @DisplayName("A book baked before it meets a reader greets nobody and claims no number")
    void unresolvedBookIsTheFollowUpAlone() {
        for (long seed = 0; seed < 200; seed++) {
            String page = render(seed, null, 0L, null);
            assertFalse(page.contains(RunStatSubject.KEY_ROOT + "stat."),
                "a container has no reader to have done anything: " + page);
            // Every opener names someone. With nobody to name there is no opener either — greeting
            // a chest by the wrong name would be worse than not greeting at all.
            assertFalse(page.contains(RunStatSubject.KEY_ROOT + "open."),
                "a container has no reader to greet: " + page);
            assertTrue(page.contains(RunStatSubject.KEY_ROOT + "tail."), page);
        }
    }

    @Test
    @DisplayName("The same seed always produces the same wording")
    void wordingIsFixedBySeed() {
        for (long seed : new long[] {0L, 1L, -1L, 42L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            String first = render(seed, RunStatSubject.CARRIAGE, 3L);
            for (int i = 0; i < 5; i++) {
                assertEquals(first, render(seed, RunStatSubject.CARRIAGE, 3L),
                    "a book the player is carrying cannot re-word itself");
            }
        }
    }

    @Test
    @DisplayName("The number moves without disturbing the wording around it")
    void onlyTheNumberIsLive() {
        long seed = 1L;
        // A flat-format subject uses one key whatever its number, so the sentence around the number
        // cannot shift as the run goes on. (A COUNT subject legitimately changes key at 1 -> 2; that
        // is the plural rule doing its job, and is covered in RunStatSubjectTest.)
        assertEquals(RunStatSubject.Format.PLAIN, RunStatSubject.CARRIAGE.format());
        assertEquals(keyOf(RunStatSubject.CARRIAGE.line("en_us", 3L)),
                     keyOf(RunStatSubject.CARRIAGE.line("en_us", 90L)),
                     "the key is the same line; only its argument changed");
        assertTrue(render(seed, RunStatSubject.CARRIAGE, 90L).contains(RunStatSubject.CARRIAGE.key()));
    }

    @Test
    @DisplayName("Every opener names the reader, and the bare salutation is the common one")
    void everyOpenerNamesTheReader() {
        int plain = 0;
        Set<String> seen = new HashSet<>();
        int samples = 6000;
        String plainKey = RunStatSubject.KEY_ROOT + "open." + RunStatBookFactory.OPENER_PLAIN;
        for (long seed = 0; seed < samples; seed++) {
            Component opener = RunStatBookFactory.opener(seed, READER);
            String key = keyOf(opener);
            seen.add(key);
            if (key.equals(plainKey)) plain++;
            // The name is the argument, on every one of them without exception.
            assertEquals(List.of(READER),
                List.of(((TranslatableContents) opener.getContents()).getArgs()),
                key + " must be addressed to the reader");
        }
        assertEquals(RunStatBookFactory.OPENER_COUNT, seen.size(),
            "every opener must be reachable: " + seen);
        double plainShare = plain / (double) samples;
        assertTrue(plainShare > 0.4 && plainShare < 0.6,
            "the bare salutation should be the common start, was " + plainShare);
    }

    @Test
    @DisplayName("Every follow-up is reachable")
    void everyFollowUpIsReachable() {
        Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 6000; seed++) seen.add(RunStatBookFactory.tail(seed).getString());
        assertEquals(RunStatBookFactory.TAIL_COUNT, seen.size(), "unreachable follow-ups: " + seen);
    }

    @Test
    @DisplayName("A fresh run is given playtime; a lived-in one is given something it has done")
    void subjectIsChosenFromWhatTheReaderHasDone() {
        assertEquals(RunStatSubject.PLAYTIME, RunStatBookFactory.chooseSubject(7L, new PlayerRunState()),
            "nothing else is true yet");

        PlayerRunState run = new PlayerRunState();
        run.addRunTicks(20L * 600);
        for (int i = 0; i < 30; i++) run.openedLootContainer();
        run.advanceTravelled(12);
        run.recordCartMovement(12);

        for (long seed = 0; seed < 500; seed++) {
            RunStatSubject chosen = RunStatBookFactory.chooseSubject(seed, run);
            assertTrue(chosen.clearsFloor(run),
                "chose " + chosen.id() + " at " + chosen.value(run) + ", below its floor " + chosen.floor());
        }
    }

    /** The translation key behind a line, ignoring the number handed to it. */
    private static String keyOf(Component line) {
        assertTrue(line.getContents() instanceof TranslatableContents, "expected a translatable line");
        return ((TranslatableContents) line.getContents()).getKey();
    }

}
