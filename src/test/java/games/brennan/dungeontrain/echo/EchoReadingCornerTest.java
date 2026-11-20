package games.brennan.dungeontrain.echo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EchoReadingCornerTest {

    @Test
    @DisplayName("an echo that never read or wrote scores 0")
    void none() {
        assertEquals(0, EchoSummaryReader.readingCorner(0, 0, 0));
    }

    @Test
    @DisplayName("how often and how long the player read count equally")
    void readingAverages() {
        assertEquals(10, EchoSummaryReader.readingCorner(10, 10, 0));
        assertEquals(5, EchoSummaryReader.readingCorner(10, 0, 0));
        assertEquals(7, EchoSummaryReader.readingCorner(8, 5, 0));
    }

    @Test
    @DisplayName("writing alone reaches 5; it tops reading up, capped at 10")
    void writingContributes() {
        assertEquals(5, EchoSummaryReader.readingCorner(0, 0, 10));
        assertEquals(9, EchoSummaryReader.readingCorner(6, 6, 6));
        assertEquals(10, EchoSummaryReader.readingCorner(10, 10, 10));
    }

    @Test
    @DisplayName("out-of-range scores clamp before blending")
    void clamps() {
        assertEquals(10, EchoSummaryReader.readingCorner(40, 40, 40));
        assertEquals(0, EchoSummaryReader.readingCorner(-5, -5, -5));
    }
}
