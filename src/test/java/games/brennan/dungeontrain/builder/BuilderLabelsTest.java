package games.brennan.dungeontrain.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ids are filenames; buttons are for reading. */
final class BuilderLabelsTest {

    @Test
    @DisplayName("Underscores and hyphens become spaces, words are capitalised")
    void separatorsBecomeWords() {
        assertEquals("Deep Nether", BuilderLabels.pretty("deep_nether"));
        assertEquals("Car 2", BuilderLabels.pretty("car-2"));
        assertEquals("Standard", BuilderLabels.pretty("standard"));
    }

    @Test
    @DisplayName("A digit starting a word doesn't capitalise the letter after it")
    void digitsDontStartAWord() {
        // `car_2b` must not come out as "Car 2B" — the word already started at the digit.
        assertEquals("Car 2b", BuilderLabels.pretty("car_2b"));
    }

    @Test
    @DisplayName("Already-capitalised input is left alone rather than lower-cased")
    void existingCapitalsSurvive() {
        assertEquals("NPC Car", BuilderLabels.pretty("NPC_Car"));
    }

    @Test
    @DisplayName("Nothing in, nothing out — an empty picker looks empty, not broken")
    void emptyAndNull() {
        assertEquals("", BuilderLabels.pretty(""));
        assertEquals("", BuilderLabels.pretty(null));
        assertEquals("   ", BuilderLabels.pretty("   "), "blank is left as-is");
    }
}
