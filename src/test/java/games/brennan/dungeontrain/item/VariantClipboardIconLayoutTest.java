package games.brennan.dungeontrain.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Order of the mini block grid on a variant clipboard's slot icon. */
final class VariantClipboardIconLayoutTest {

    @Test
    @DisplayName("first row runs left to right along the bottom of the icon")
    void bottomRowFirst() {
        assertArrayEquals(new int[] {0, 14}, VariantClipboardIconLayout.cellOrigin(0));
        assertArrayEquals(new int[] {14, 14}, VariantClipboardIconLayout.cellOrigin(7));
    }

    @Test
    @DisplayName("ninth entry starts the next row up")
    void rowsStackUpward() {
        assertArrayEquals(new int[] {0, 12}, VariantClipboardIconLayout.cellOrigin(8));
        assertArrayEquals(new int[] {14, 8}, VariantClipboardIconLayout.cellOrigin(31));
    }

    @Test
    @DisplayName("entries past the bottom half are not drawn")
    void cappedAtFourRows() {
        assertNull(VariantClipboardIconLayout.cellOrigin(32));
        assertNull(VariantClipboardIconLayout.cellOrigin(-1));
    }
}
