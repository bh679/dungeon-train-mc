package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the passenger log says about a tributed photo. */
class TributePhotoReporterTest {

    @Test
    @DisplayName("title names who paid and whose photo it is")
    void titleNamesBoth() {
        assertEquals("📸 Steve paid tribute to a photo by Alex", TributePhotoReporter.title("Steve", "Alex"));
    }

    @Test
    @DisplayName("an unknown photographer is a fellow passenger, never a blank")
    void unknownPhotographer() {
        assertEquals("📸 Steve paid tribute to a photo by a fellow passenger", TributePhotoReporter.title("Steve", ""));
        assertEquals("📸 Steve paid tribute to a photo by a fellow passenger", TributePhotoReporter.title("Steve", null));
    }

    @Test
    @DisplayName("paying tribute to your own photo reads as that")
    void ownPhoto() {
        assertEquals("📸 Steve paid tribute to their own photo", TributePhotoReporter.title("Steve", "Steve"));
    }

    @Test
    @DisplayName("description counts the tribute and its emerald cost")
    void descriptionShape() {
        assertEquals("Tribute #1 · 1 emerald", TributePhotoReporter.description(1, 1));
        assertEquals("Tribute #3 · 3 emeralds", TributePhotoReporter.description(3, 3));
        assertEquals("Tribute #1 · 2 emeralds", TributePhotoReporter.description(0, 2));
    }
}
