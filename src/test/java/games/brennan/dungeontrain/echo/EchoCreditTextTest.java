package games.brennan.dungeontrain.echo;

import games.brennan.dungeontrain.echo.EchoCreditText.Cause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wording of an echo's credit line: which phrase groups each cause may draw from, and the shape. */
final class EchoCreditTextTest {

    /** Always returns {@code value}, clamped into the bound. */
    private static IntUnaryOperator fixed(int value) {
        return bound -> Math.min(value, bound - 1);
    }

    @Test
    @DisplayName("first phrase and first name form compose a readable line")
    void composesLine() {
        assertEquals("Carried by the echo of Steve", EchoCreditText.compose(Cause.SWAP, "Steve", fixed(0)));
    }

    @Test
    @DisplayName("every name form carries the player's name")
    void nameForms() {
        for (String form : EchoCreditText.NAME_FORMS) {
            assertTrue(form.replace("%s", "Steve").contains("Steve"), form);
        }
        assertEquals(5, EchoCreditText.NAME_FORMS.size());
    }

    @Test
    @DisplayName("a swap only ever draws Plain + Heroic")
    void swapIsPlainOrHeroic() {
        assertEquals(EchoCreditText.ALWAYS, EchoCreditText.phrasePool(Cause.SWAP, fixed(1)));
        assertEquals(EchoCreditText.ALWAYS, EchoCreditText.phrasePool(Cause.SWAP, fixed(0)));
    }

    @Test
    @DisplayName("each cause adds its own group: player kill Grim, other death Wistful, gift Gift")
    void situationalGroups() {
        assertEquals(EchoCreditText.GRIM, EchoCreditText.phrasePool(Cause.KILLED_BY_PLAYER, fixed(1)));
        assertEquals(EchoCreditText.WISTFUL, EchoCreditText.phrasePool(Cause.DIED, fixed(1)));
        assertEquals(EchoCreditText.GIFT, EchoCreditText.phrasePool(Cause.GIFT, fixed(1)));
        assertEquals(EchoCreditText.ALWAYS, EchoCreditText.phrasePool(Cause.GIFT, fixed(0)));
    }

    @Test
    @DisplayName("a player kill never words the line as a gift or a fond memory")
    void killNeverUsesOtherGroups() {
        Random rng = new Random(7);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String line = EchoCreditText.compose(Cause.KILLED_BY_PLAYER, "Steve", rng::nextInt);
            seen.add(openingOf(line));
        }
        for (String phrase : seen) {
            assertTrue(EchoCreditText.ALWAYS.contains(phrase) || EchoCreditText.GRIM.contains(phrase), phrase);
        }
        assertTrue(seen.containsAll(EchoCreditText.GRIM), "every Grim phrase should turn up");
    }

    /** The opening phrase — the longest known phrase the line starts with. */
    private static String openingOf(String line) {
        List<List<String>> groups = List.of(EchoCreditText.PLAIN, EchoCreditText.HEROIC, EchoCreditText.GRIM,
                EchoCreditText.WISTFUL, EchoCreditText.GIFT);
        String best = "";
        for (List<String> group : groups) {
            for (String phrase : group) {
                if (line.startsWith(phrase + " ") && phrase.length() > best.length()) best = phrase;
            }
        }
        return best;
    }
}
