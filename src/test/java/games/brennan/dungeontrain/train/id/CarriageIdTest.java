package games.brennan.dungeontrain.train.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Format, parse and typo detection for {@link CarriageId}. */
final class CarriageIdTest {

    private static final CarriageId.Full PLAIN =
        new CarriageId.Full(42, 3, 0b100001, 1720, false, 0, false, OptionalInt.empty());
    private static final CarriageId.Full EVERY_FLAG =
        new CarriageId.Full(255, 31, 63, 46655, true, 0b101, true, OptionalInt.of(1234));

    @Test
    @DisplayName("short form reads mark, number and check character")
    void shortForm_layout() {
        String text = CarriageId.shortForm("QXRT", 4821).format();
        assertTrue(text.matches("QXRT 4821-[0-9A-Z]"), text);
    }

    @Test
    @DisplayName("a negative index is written with an R and reads back negative")
    void negativeIndex_roundTrips() {
        CarriageId id = CarriageId.shortForm("ABCD", -4821);
        assertTrue(id.format().startsWith("ABCD R4821-"), id.format());
        assertEquals(id, CarriageId.parse(id.format()));
    }

    @Test
    @DisplayName("full form round-trips with and without every flag")
    void fullForm_roundTrips() {
        for (CarriageId.Full full : new CarriageId.Full[] {PLAIN, EVERY_FLAG}) {
            for (int pIdx : new int[] {0, 1, 37, 128440, -1, -99999, Integer.MAX_VALUE, -Integer.MAX_VALUE}) {
                CarriageId id = CarriageId.fullForm("QXRT", pIdx, full);
                assertEquals(id, CarriageId.parse(id.format()), id.format());
            }
        }
    }

    @Test
    @DisplayName("flags appear in order after the context and version")
    void flags_order() {
        String text = CarriageId.fullForm("QXRT", 7, EVERY_FLAG).format();
        assertTrue(text.endsWith(" P T5 F S1234"), text);
        assertEquals(8, text.split(" ").length, text);
    }

    @Test
    @DisplayName("lower case and extra spaces are forgiven")
    void parse_forgiving() {
        CarriageId id = CarriageId.fullForm("QXRT", 4821, PLAIN);
        String messy = "  " + id.format().toLowerCase().replace(" ", "   ") + " ";
        assertEquals(id, CarriageId.parse(messy));
    }

    @Test
    @DisplayName("toShort drops the context and keeps the carriage")
    void toShort_dropsContext() {
        CarriageId full = CarriageId.fullForm("QXRT", 4821, PLAIN);
        assertEquals(CarriageId.shortForm("QXRT", 4821), full.toShort());
    }

    @Test
    @DisplayName("every single-character substitution is rejected")
    void substitution_rejected() {
        String text = CarriageId.fullForm("QXRT", 4821, EVERY_FLAG).format();
        for (int i = 0; i < text.length(); i++) {
            char original = text.charAt(i);
            if (original == ' ') continue;
            for (char c : CarriageId.ALPHABET.toCharArray()) {
                if (c == original) continue;
                String typo = text.substring(0, i) + c + text.substring(i + 1);
                assertRejected(typo);
            }
        }
    }

    @Test
    @DisplayName("neighbouring swaps are caught all but one pair in 630 at each position")
    void transposition_rejected() {
        // One check character over a 36-symbol alphabet can't catch every swap (that needs a 37th
        // symbol); MOD 37,36 misses exactly one unordered pair of the 630 at each position.
        String payload = "QXRT4821" + "7B2C1BSPT5FS1234";
        for (int i = 0; i + 1 < payload.length(); i++) {
            int missed = 0;
            for (char a : CarriageId.ALPHABET.toCharArray()) {
                for (char b : CarriageId.ALPHABET.toCharArray()) {
                    if (a == b) continue;
                    String ab = payload.substring(0, i) + a + b + payload.substring(i + 2);
                    String ba = payload.substring(0, i) + b + a + payload.substring(i + 2);
                    if (CarriageId.checkChar(ab) == CarriageId.checkChar(ba)) missed++;
                }
            }
            assertTrue(missed <= 2, missed + " ordered swaps missed at position " + i);
        }
    }

    @Test
    @DisplayName("malformed ids explain what is wrong")
    void malformed_rejected() {
        assertRejected("");
        assertRejected("QXRT");
        assertRejected("QXR 4821-K");
        assertRejected("QXRT 4821");
        assertRejected("QXRT 99999999999-K");
        String full = CarriageId.fullForm("QXRT", 4821, PLAIN).format();
        assertRejected(full.substring(0, full.lastIndexOf(' ')));
        assertRejected(full + " Z");
    }

    @Test
    @DisplayName("out-of-range context values are refused when building an id")
    void full_rangeChecks() {
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageId.Full(256, 0, 0, 0, false, 0, false, OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageId.Full(0, 32, 0, 0, false, 0, false, OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageId.Full(0, 0, 64, 0, false, 0, false, OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageId.Full(0, 0, 0, 46656, false, 0, false, OptionalInt.empty()));
    }

    private static void assertRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> CarriageId.parse(text), text);
    }
}
