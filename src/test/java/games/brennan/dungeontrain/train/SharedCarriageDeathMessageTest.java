package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Deaths;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The death line's contract — chiefly that a carriage nobody has died in produces NO line, since that
 * is what keeps a clean carriage reading exactly as it did before the death log existed.
 *
 * <p>Assertions read the translation key + arguments rather than rendered text: no language is loaded
 * in unit tests, so {@code getString()} would only ever return the key back.</p>
 */
class SharedCarriageDeathMessageTest {

    private static RandomSource rng() {
        return RandomSource.create(1234L);
    }

    private static TranslatableContents contents(Component c) {
        assertTrue(c.getContents() instanceof TranslatableContents, "expected a translatable line");
        return (TranslatableContents) c.getContents();
    }

    @Test
    void aCarriageWithNoDeathsProducesNoLineAtAll() {
        assertNull(SharedCarriageMessage.deathLine("en_us", Deaths.EMPTY, rng()));
        assertNull(SharedCarriageMessage.deathLine("en_us", null, rng()));
        // Names but a zero total can't happen from the relay, and must still not invent a line.
        assertNull(SharedCarriageMessage.deathLine("en_us", new Deaths(List.of("Ann"), 0), rng()));
    }

    @Test
    void oneDeathNamesTheTravellerWithTheSingularPhrasing() {
        Component line = SharedCarriageMessage.deathLine("en_us", new Deaths(List.of("Ann"), 1), rng());
        assertNotNull(line);
        TranslatableContents t = contents(line);
        assertTrue(t.getKey().startsWith("chat.dungeontrain.shared_carriage.deaths.one."),
                "one death, one name → the singular family, got " + t.getKey());
        assertEquals(1, t.getArgs().length);
    }

    /**
     * Assert a count argument is the nested "N travellers" clause in the expected grammatical-number
     * form. The count lives in the clause rather than the sentence so a language that declines its
     * nouns can decline this one — see {@link games.brennan.dungeontrain.narrative.PluralRules}.
     */
    private static void assertCount(Object arg, String noun, String category, int n, String why) {
        TranslatableContents c = contents(assertInstanceOf(Component.class, arg, why));
        assertEquals("chat.dungeontrain.shared_carriage.deaths.count." + noun + "." + category,
                c.getKey(), why);
        // A long: PluralRules.clause takes the count as one, so the boxed arg is a Long, not an Integer.
        assertEquals((long) n, c.getArgs()[0], why);
    }

    @Test
    void oneNameButSeveralDeathsUsesThePluralPhrasing() {
        // The others were unconsented, so they cannot be named — but "X died in this one" would be a lie
        // about how many people did.
        Component line = SharedCarriageMessage.deathLine("en_us", new Deaths(List.of("Ann"), 4), rng());
        TranslatableContents t = contents(assertNotNullAndReturn(line));
        assertTrue(t.getKey().startsWith("chat.dungeontrain.shared_carriage.deaths.few."), t.getKey());
        assertCount(t.getArgs()[0], "travellers", "other", 4,
                "the line reports the relay's total, not the name count");
    }

    /**
     * The whole point of the nested clause: a Russian reader gets the count in the form Russian wants
     * — 2 путника (few) but 5 путников (many) — where English has only one plural to offer.
     */
    @Test
    void theCountClauseIsDeclinedForTheReadersLanguage() {
        assertCount(contents(assertNotNullAndReturn(
                SharedCarriageMessage.deathLine("ru_ru", new Deaths(List.of("Ann"), 2), rng())))
                .getArgs()[0], "travellers", "few", 2, "2 путника");
        assertCount(contents(assertNotNullAndReturn(
                SharedCarriageMessage.deathLine("ru_ru", new Deaths(List.of("Ann"), 5), rng())))
                .getArgs()[0], "travellers", "many", 5, "5 путников");
        assertCount(contents(assertNotNullAndReturn(
                SharedCarriageMessage.deathLine("ja_jp", new Deaths(List.of("Ann"), 5), rng())))
                .getArgs()[0], "travellers", "other", 5, "Japanese has one form for every count");
    }

    @Test
    void aSingleUnnameableDeathIsSaidInWordsNotAsACountOfOne() {
        // The plural line substitutes the number and would read "1 travellers have died in here".
        Component line = SharedCarriageMessage.deathLine("en_us", new Deaths(List.of(), 1), rng());
        TranslatableContents t = contents(assertNotNullAndReturn(line));
        assertTrue(t.getKey().startsWith("chat.dungeontrain.shared_carriage.deaths.unnamed_one."), t.getKey());
        assertEquals(0, t.getArgs().length, "nothing to substitute — the count is in the words");
    }

    @Test
    void deathsWithNobodyToNameFallBackToTheCountAlone() {
        Component line = SharedCarriageMessage.deathLine("en_us", new Deaths(List.of(), 3), rng());
        TranslatableContents t = contents(assertNotNullAndReturn(line));
        assertTrue(t.getKey().startsWith("chat.dungeontrain.shared_carriage.deaths.unnamed."), t.getKey());
        // Each of the three phrasings counts a different noun, so the clause is whichever that line names.
        String noun = t.getKey().endsWith(".2") ? "runs" : t.getKey().endsWith(".3") ? "times" : "travellers";
        assertCount(t.getArgs()[0], noun, "other", 3, "the count is in the nested clause");
    }

    @Test
    void aTotalBeyondTheNamedListCollapsesIntoAndNOthers() {
        List<String> named = List.of("Ann", "Bo", "Cai", "Dee", "Eli");
        Component line = SharedCarriageMessage.deathLine("en_us", new Deaths(named, 12), rng());
        TranslatableContents t = contents(assertNotNullAndReturn(line));
        assertCount(t.getArgs()[0], "travellers", "other", 12, "the line reports the relay's total");
        // The name argument is itself the "…, and N besides" wrapper, with N = total - named.
        TranslatableContents more = contents((Component) t.getArgs()[1]);
        assertEquals("chat.dungeontrain.shared_carriage.credit.more", more.getKey());
        TranslatableContents others = contents((Component) more.getArgs()[1]);
        assertEquals("chat.dungeontrain.shared_carriage.credit.more.count.other", others.getKey());
        assertEquals(7L, others.getArgs()[0]);
    }

    @Test
    void aTotalMatchingTheNamedListNamesThemAllWithNoRemainder() {
        Component line = SharedCarriageMessage.deathLine("en_us", new Deaths(List.of("Ann", "Bo"), 2), rng());
        TranslatableContents t = contents(assertNotNullAndReturn(line));
        assertCount(t.getArgs()[0], "travellers", "other", 2, "the line reports the relay's total");
        Component names = (Component) t.getArgs()[1];
        // No "and N others" wrapper — the whole list is named, so the argument is the plain join.
        assertTrue(!(names.getContents() instanceof TranslatableContents tc)
                        || !tc.getKey().equals("chat.dungeontrain.shared_carriage.credit.more"),
                "everyone is named, so nothing should collapse into 'and N others'");
    }

    private static Component assertNotNullAndReturn(Component c) {
        assertNotNull(c);
        return c;
    }
}
