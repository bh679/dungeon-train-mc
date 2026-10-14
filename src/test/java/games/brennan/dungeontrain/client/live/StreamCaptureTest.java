package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.StreamCapture.Point;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.StreamCapture.chatShown;
import static games.brennan.dungeontrain.client.live.StreamCapture.point;
import static games.brennan.dungeontrain.client.live.StreamCapture.previewPoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No grabbed frame may carry what the streamer's settings (or Kid mode) keep from viewers. */
class StreamCaptureTest {

    @Test
    void kidModeNeverShowsChat() {
        assertFalse(chatShown(true, true));
        assertFalse(chatShown(false, true));
        assertTrue(chatShown(true, false));
        assertFalse(chatShown(false, false));
    }

    @Test
    void guiAndChatOnInWorldSendsTheFinishedFrame() {
        assertEquals(Point.FRAME_END, point(true, true, false, false));
        assertEquals(Point.FRAME_END, point(true, true, false, true));
    }

    @Test
    void guiOffAlwaysReadsBeforeTheGui() {
        for (boolean chat : new boolean[]{true, false})
            for (boolean screen : new boolean[]{true, false})
                for (boolean hide : new boolean[]{true, false})
                    assertEquals(Point.BEFORE_GUI, point(false, chat, screen, hide));
    }

    @Test
    void chatOffInWorldReadsBeforeChatDraws() {
        assertEquals(Point.BEFORE_CHAT, point(true, false, false, false));
    }

    @Test
    void chatOffWithTheGuiHiddenInWorld() {
        // F1 hides the chat lines; with no screen open there is no chat box either.
        assertEquals(Point.FRAME_END, point(true, false, false, true));
    }

    /**
     * The chat box, a sign or book being written, /msg being typed, another mod's account screen:
     * whatever is open, the frame is read before it draws — whatever the chat switch says.
     */
    @Test
    void anOpenScreenIsNeverBroadcast() {
        for (boolean gui : new boolean[]{true, false})
            for (boolean chat : new boolean[]{true, false})
                for (boolean hide : new boolean[]{true, false}) {
                    Point p = point(gui, chat, true, hide);
                    assertNotEquals(Point.FRAME_END, p);
                    assertNotEquals(Point.SKIP, p);
                    assertEquals(previewPoint(gui, chat), p);
                }
        assertEquals(Point.AFTER_GUI, point(true, true, true, false));
        assertEquals(Point.BEFORE_CHAT, point(true, false, true, false));
    }

    @Test
    void previewIsTheNoMenuFrame() {
        assertEquals(Point.BEFORE_GUI, previewPoint(false, true));
        assertEquals(Point.BEFORE_GUI, previewPoint(false, false));
        assertEquals(Point.BEFORE_CHAT, previewPoint(true, false));
        assertEquals(Point.AFTER_GUI, previewPoint(true, true));
    }
}
