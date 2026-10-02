package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of {@link WholeGroupSelection}: a run asks for a whole group when its Group-carriage
 * draw landed on the Whole Group template, the way a slot asks for a whole room when it drew
 * {@code whole}. The only cadence left is the session's forced one, a test aid.
 */
final class WholeGroupLotteryTest {

    private static final int GROUP = 3;

    @Test
    @DisplayName("only the Whole Group template stands for a whole group")
    void wholeGroupVariant() {
        assertTrue(WholeGroupSelection.isWholeGroupVariant(CarriageVariant.custom(WholeGroupSelection.VARIANT_ID)));
        assertFalse(WholeGroupSelection.isWholeGroupVariant(CarriageVariant.custom("cargo")));
        assertFalse(WholeGroupSelection.isWholeGroupVariant(CarriageVariant.custom(WholeCarriageSelection.VARIANT_ID)),
            "the whole-room template is a different template");
        assertFalse(WholeGroupSelection.isWholeGroupVariant(null), "a run that drew no Group carriage");
    }

    @Test
    @DisplayName("the forced cadence is off at 0 and takes every Nth group ordinal otherwise")
    void forcedCadence() {
        for (int anchor = -300; anchor <= 300; anchor += GROUP) {
            assertFalse(WholeGroupSelection.isForced(anchor, GROUP, WholeGroupSettings.OFF));
            assertTrue(WholeGroupSelection.isForced(anchor, GROUP, 1));
        }
        int hits = 0;
        for (int g = 0; g < 400; g++) {
            if (WholeGroupSelection.isForced(g * GROUP, GROUP, 4)) hits++;
        }
        assertEquals(100, hits, "every 4th group of 400");
    }

    @Test
    @DisplayName("every carriage of a group reads the same forced verdict, behind the origin too")
    void wholeGroupAgrees() {
        for (int anchor = -300; anchor <= 300; anchor += GROUP) {
            boolean expected = WholeGroupSelection.isForced(anchor, GROUP, 5);
            for (int slot = 1; slot < GROUP; slot++) {
                assertEquals(expected, WholeGroupSelection.isForced(anchor + slot, GROUP, 5));
            }
        }
    }

    @Test
    @DisplayName("the Whole Group template ships in the Group row with a weight and a shape")
    void shipped() throws Exception {
        try (InputStream in = WholeGroupLotteryTest.class.getResourceAsStream(
                "/data/dungeontrain/templates/group/" + WholeGroupSelection.VARIANT_ID + ".nbt")) {
            assertNotNull(in, "group/wholegroup.nbt is the fallback Group carriage");
        }
        try (InputStream in = WholeGroupLotteryTest.class.getResourceAsStream("/data/dungeontrain/templates/weights.json")) {
            assertNotNull(in);
            String weights = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(weights.contains("\"" + WholeGroupSelection.VARIANT_ID + "\""),
                "its weight is the whole-group rate, so it must have an entry");
        }
        try (InputStream in = WholeGroupLotteryTest.class.getResourceAsStream("/data/dungeontrain/templates/group/customs.json")) {
            assertNotNull(in);
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains(WholeGroupSelection.VARIANT_ID));
        }
    }
}
