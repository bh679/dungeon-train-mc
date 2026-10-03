package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** {@link EditorLayout#rowStarts}: plots laid end to end at their own lengths, a gap apart. */
final class EditorLayoutRowStartsTest {

    @Test
    @DisplayName("each plot starts its predecessor's own length plus the gap later — a Room is not spaced as a Group")
    void ownLengths() {
        int g = EditorLayout.GAP;
        // Room, Room, Half, Group, Room at nine-long carriages.
        assertArrayEquals(new int[] {0, 9 + g, 18 + 2 * g, 31 + 3 * g, 58 + 4 * g, 67 + 5 * g},
            EditorLayout.rowStarts(0, new int[] {9, 9, 13, 27, 9}));
    }

    @Test
    @DisplayName("an empty row's only entry is where its first plot would go")
    void empty() {
        assertArrayEquals(new int[] {7}, EditorLayout.rowStarts(7, new int[0]));
    }
}
