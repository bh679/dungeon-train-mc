package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Deaths;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertNull(SharedCarriageMessage.deathLine(Deaths.EMPTY, rng()));
        assertNull(SharedCarriageMessage.deathLine(null, rng()));
        // Names but a zero total can't happen from the relay, and must still not invent a line.
        assertNull(SharedCarriageMessage.deathLine(new Deaths(List.of("Ann"), 0), rng()));
    }

    @Test
    void oneDeathNamesTheTravellerWithTheSingularPhrasing() {
        Component line = SharedCarriageMessage.deathLine(new Deaths(List.of("Ann"), 1), rng());
        assertNotNull(line);
        TranslatableContents t = contents(line);
        assertTrue(t.getKey().startsWith("chat.dungeontrain.shared_carriage.deaths.one."),
                "one death, one name → the singular family, got " + t.getKey());
        assertEquals(1, t.getArgs().length);
    }

    @Test
    void oneNameButSeveralDeathsUsesThePluralPhrasing() {
        // The others were unconsented, so they cannot be named — but "X died in this one" would be a lie
        // about how many people did.
        Component line = SharedCarriageMessage.deathLine(new Deaths(List.of("Ann"), 4), rng());
        TranslatableContents t = contents(assertNotNullAndReturn(line));
        assertTrue(t.getKey().startsWith("chat.dungeontrain.shared_carriage.deaths.few."), t.getKey());
        assertEquals(4, t.getArgs()[0], "the line reports the relay's total, not the name count");
    }

    @Test
    void deathsWithNobodyToNameFallBackToTheCountAlone() {
        Component line = SharedCarriageMessage.deathLine(new Deaths(List.of(), 3), rng());
        TranslatableContents t = contents(assertNotNullAndReturn(line));
        assertTrue(t.getKey().startsWith("chat.dungeontrain.shared_carriage.deaths.unnamed."), t.getKey());
        assertEquals(3, t.getArgs()[0]);
    }

    @Test
    void aTotalBeyondTheNamedListCollapsesIntoAndNOthers() {
        List<String> named = List.of("Ann", "Bo", "Cai", "Dee", "Eli");
        Component line = SharedCarriageMessage.deathLine(new Deaths(named, 12), rng());
        TranslatableContents t = contents(assertNotNullAndReturn(line));
        assertEquals(12, t.getArgs()[0]);
        // The name argument is itself the "…, and N others besides" wrapper, with N = total - named.
        TranslatableContents more = contents((Component) t.getArgs()[1]);
        assertEquals("chat.dungeontrain.shared_carriage.credit.more", more.getKey());
        assertEquals(7, more.getArgs()[1]);
    }

    @Test
    void aTotalMatchingTheNamedListNamesThemAllWithNoRemainder() {
        Component line = SharedCarriageMessage.deathLine(new Deaths(List.of("Ann", "Bo"), 2), rng());
        TranslatableContents t = contents(assertNotNullAndReturn(line));
        assertEquals(2, t.getArgs()[0]);
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
