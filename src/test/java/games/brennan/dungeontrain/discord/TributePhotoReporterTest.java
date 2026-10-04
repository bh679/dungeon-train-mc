package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("Tribute #1 · 1 emerald\n🤲 Held by 1 passenger · was 9 views from fading",
                TributePhotoReporter.description(1, 1, 1, 10));
        assertEquals("Tribute #3 · 3 emeralds\n🤲 Held by 7 passengers · was 3 views from fading",
                TributePhotoReporter.description(3, 3, 7, 4));
        assertTrue(TributePhotoReporter.description(0, 2, 1, 10).startsWith("Tribute #1 · 2 emeralds\n"));
    }

    @Test
    @DisplayName("view line: how many hands, and how close it came to fading")
    void viewLine() {
        assertTrue(TributePhotoReporter.description(1, 1, 12, 2).endsWith("Held by 12 passengers · was 1 view from fading"));
        assertTrue(TributePhotoReporter.description(1, 1, 10, 1).endsWith("Held by 10 passengers · was on its last view"));
        assertTrue(TributePhotoReporter.description(1, 1, 0, 0).endsWith("Held by 1 passenger · was on its last view"));
    }

    @Test
    @DisplayName("own-photo description counts this life's Tribute and its cost")
    void ownDescription() {
        assertEquals("Tribute #1 this life · 3 emeralds", TributePhotoReporter.ownDescription(1, 3));
        assertEquals("Tribute #3 this life · 27 emeralds", TributePhotoReporter.ownDescription(3, 27));
        assertEquals("Tribute #1 this life · 1 emerald", TributePhotoReporter.ownDescription(0, 1));
    }

    @Test
    @DisplayName("own-photo title asks whether it was worth it")
    void ownTitle() {
        assertEquals("📸 Steve tributed their photo. Was it worth it?", TributePhotoReporter.ownTitle("Steve"));
    }
}
