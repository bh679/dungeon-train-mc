package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the weighted draw behind the X menu's Reroll carriage: it never hands back the shell already shown. */
final class ContentsShellPickerDrawTest {

    private static final Map<String, Integer> WEIGHTS = Map.of("standard", 20, "glass", 5, "farm", 1, "off", 0);

    @Test
    @DisplayName("a reroll never returns the excluded carriage, whatever the seed")
    void excludesCurrent() {
        List<String> all = List.of("standard", "glass", "farm");
        for (long seed = 0; seed < 500; seed++) {
            Optional<String> next = ContentsShellPicker.drawExcluding(all, "standard", WEIGHTS::get, seed);
            assertTrue(next.isPresent());
            assertNotEquals("standard", next.get());
        }
    }

    @Test
    @DisplayName("with only the current carriage eligible there is nothing to reroll to")
    void nothingElse() {
        assertEquals(Optional.empty(),
            ContentsShellPicker.drawExcluding(List.of("standard"), "standard", WEIGHTS::get, 7L));
        assertEquals(Optional.empty(), ContentsShellPicker.drawByWeight(List.<String>of(), WEIGHTS::get, 7L));
    }

    @Test
    @DisplayName("the draw follows weight and never picks a weight-0 candidate while others weigh something")
    void followsWeight() {
        List<String> all = List.of("standard", "glass", "farm", "off");
        int standard = 0;
        for (long seed = 0; seed < 2600; seed++) {
            String got = ContentsShellPicker.drawByWeight(all, WEIGHTS::get, seed).orElseThrow();
            assertNotEquals("off", got);
            if (got.equals("standard")) standard++;
        }
        assertTrue(standard > 1700 && standard < 2300, "standard drawn " + standard + "/2600, expected ~2000");
    }
}
