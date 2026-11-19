package games.brennan.dungeontrain.train.id;

import games.brennan.dungeontrain.worldgen.BandFlavor;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Format, parse and typo detection for {@link CarriageId}. */
final class CarriageIdTest {

    /** Overworld, no band, the level its number gives, v0.1737. */
    private static final CarriageId.Full PLAIN =
        new CarriageId.Full(OptionalInt.empty(), 0, 0, 1737 % 1296, false, 0, false, OptionalInt.empty());
    /** Nether, BetterNether band. */
    private static final CarriageId.Full BETTER_NETHER =
        new CarriageId.Full(OptionalInt.empty(), 1, 0b1, 441, false, 0, false, OptionalInt.empty());
    private static final CarriageId.Full EVERY_EXTRA =
        new CarriageId.Full(OptionalInt.of(92), 29, 0b110, 1295, true, 0b101, true, OptionalInt.of(1234));

    @Test
    @DisplayName("a plain carriage reads mark, base-36 number, stage and version, and check")
    void plain_layout() {
        assertEquals("NOPI K 0C9-", new CarriageId("NOPI", 20, PLAIN).format().substring(0, 11));
        assertTrue(new CarriageId("NOPI", 20, PLAIN).format().matches("NOPI K 0C9-[0-9A-Z]"));
    }

    @Test
    @DisplayName("a styled band takes the stage character itself")
    void band_stageCharacter() {
        String text = new CarriageId("NOPI", 418, BETTER_NETHER).format();
        assertTrue(text.startsWith("NOPI BM UC9-"), text);
        assertEquals(BETTER_NETHER, CarriageId.parse(text).context());
    }

    @Test
    @DisplayName("band bits the stage character can't carry go in an N extra")
    void band_mismatchUsesExtra() {
        CarriageId.Full twoBands = new CarriageId.Full(OptionalInt.empty(), 1, 0b101, 0, false, 0, false, OptionalInt.empty());
        CarriageId.Full wrongPhase = new CarriageId.Full(OptionalInt.empty(), 0, 0b1, 0, false, 0, false, OptionalInt.empty());
        for (CarriageId.Full full : new CarriageId.Full[] {twoBands, wrongPhase}) {
            CarriageId id = new CarriageId("NOPI", 5, full);
            assertTrue(id.format().contains(" N"), id.format());
            assertEquals(id, CarriageId.parse(id.format()));
        }
    }

    @Test
    @DisplayName("an index behind the start gets a B extra and reads back negative")
    void negativeIndex_roundTrips() {
        CarriageId id = new CarriageId("ABCD", -12, PLAIN);
        assertTrue(id.format().matches("ABCD C 0C9 B-[0-9A-Z]"), id.format());
        assertEquals(id, CarriageId.parse(id.format()));
    }

    @Test
    @DisplayName("every context round-trips across the index range")
    void roundTrips() {
        for (CarriageId.Full full : new CarriageId.Full[] {PLAIN, BETTER_NETHER, EVERY_EXTRA}) {
            for (int pIdx : new int[] {0, 1, 35, 36, 128440, -1, -99999, Integer.MAX_VALUE, -Integer.MAX_VALUE}) {
                CarriageId id = new CarriageId("QXRT", pIdx, full);
                assertEquals(id, CarriageId.parse(id.format()), id.format());
            }
        }
    }

    @Test
    @DisplayName("extras appear in a fixed order after the stage")
    void extras_order() {
        String text = new CarriageId("QXRT", -7, EVERY_EXTRA).format();
        assertTrue(text.matches("QXRT 7 TZZ L2K N6 B P T5 F SYA-[0-9A-Z]"), text);
    }

    @Test
    @DisplayName("lower case and extra spaces are forgiven")
    void parse_forgiving() {
        CarriageId id = new CarriageId("QXRT", 4821, EVERY_EXTRA);
        String messy = "  " + id.format().toLowerCase().replace(" ", "   ") + " ";
        assertEquals(id, CarriageId.parse(messy));
    }

    @Test
    @DisplayName("every single-character substitution is rejected")
    void substitution_rejected() {
        String text = new CarriageId("QXRT", 4821, EVERY_EXTRA).format();
        for (int i = 0; i < text.length(); i++) {
            char original = text.charAt(i);
            if (original == ' ' || original == '-') continue;
            for (char c : CarriageId.ALPHABET.toCharArray()) {
                if (c == original) continue;
                assertRejected(text.substring(0, i) + c + text.substring(i + 1));
            }
        }
    }

    @Test
    @DisplayName("a space moved inside the id is rejected")
    void movedSpace_rejected() {
        String text = new CarriageId("QXRT", 4821, EVERY_EXTRA).format();
        for (int i = 1; i + 1 < text.length(); i++) {
            if (text.charAt(i) != ' ') continue;
            String joined = text.substring(0, i) + text.substring(i + 1);
            for (int j = 1; j < joined.length(); j++) {
                String moved = joined.substring(0, j) + ' ' + joined.substring(j);
                if (moved.equals(text) || moved.contains("  ") || moved.contains(" -")) continue;
                assertRejected(moved);
            }
        }
    }

    @Test
    @DisplayName("neighbouring swaps are caught all but one pair in 630 at each position")
    void transposition_rejected() {
        // One check character over a 36-symbol alphabet can't catch every swap (that needs a 37th
        // symbol); MOD 37,36 misses exactly one unordered pair of the 630 at each position.
        String payload = "4QXRT3" + "3TZZ3L2K";
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
        assertRejected("QXRT K");
        assertRejected("QXRT K-J");
        assertRejected("QXRT K 0C9");
        assertRejected("QXR K 0C9-J");
        assertRejected("QXRT 20-B 0VLS 1C9");
        String text = new CarriageId("QXRT", 4821, PLAIN).format();
        assertRejected(text.substring(0, text.length() - 1) + "_");
        assertRejected(text.replace("-", " Q-"));
    }

    @Test
    @DisplayName("out-of-range context values are refused when building an id")
    void full_rangeChecks() {
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageId.Full(OptionalInt.of(-1), 0, 0, 0, false, 0, false, OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageId.Full(OptionalInt.empty(), 30, 0, 0, false, 0, false, OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageId.Full(OptionalInt.empty(), 0, 64, 0, false, 0, false, OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageId.Full(OptionalInt.empty(), 0, 0, 1296, false, 0, false, OptionalInt.empty()));
    }

    @Test
    @DisplayName("the stage table matches the game's phases and bands")
    void stageTable_matchesEnums() {
        assertTrue(TrainPhase.values().length <= CarriageId.FIRST_BAND_CODE, "phases outgrew the stage characters");
        BandFlavor[] bands = BandFlavor.values();
        assertEquals(bands.length, CarriageId.BAND_PHASE.length);
        for (BandFlavor band : bands) {
            TrainPhase expected = band.name().contains("NETHER") ? TrainPhase.NETHER
                : band.name().contains("END") ? TrainPhase.END : TrainPhase.OVERWORLD;
            assertEquals(expected.ordinal(), CarriageId.BAND_PHASE[band.ordinal()], band.name());
        }
    }

    private static void assertRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> CarriageId.parse(text), text);
    }
}
