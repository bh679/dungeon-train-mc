package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.StreamCapture.Point;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.StreamCapture.chatShown;
import static games.brennan.dungeontrain.client.live.StreamCapture.point;
import static games.brennan.dungeontrain.client.live.StreamCapture.previewPoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void guiAndChatOnSendsTheFinishedFrame() {
        assertEquals(Point.FRAME_END, point(true, true, false, false, false));
        assertEquals(Point.FRAME_END, point(true, true, true, true, false));
        assertEquals(Point.FRAME_END, point(true, true, true, false, true));
    }

    @Test
    void guiOffAlwaysReadsBeforeTheGui() {
        for (boolean chat : new boolean[]{true, false})
            for (boolean screen : new boolean[]{true, false})
                for (boolean chatScreen : new boolean[]{true, false})
                    for (boolean hide : new boolean[]{true, false})
                        assertEquals(Point.BEFORE_GUI, point(false, chat, screen, chatScreen, hide));
    }

    @Test
    void chatOffInWorldOrTypingReadsBeforeChatDraws() {
        assertEquals(Point.BEFORE_CHAT, point(true, false, false, false, false));
        assertEquals(Point.BEFORE_CHAT, point(true, false, true, true, false));
    }

    @Test
    void chatOffWithAMenuOpenHidesTheChatBehindIt() {
        assertEquals(Point.FRAME_END_HIDE_CHAT, point(true, false, true, false, false));
    }

    @Test
    void chatOffWithTheGuiHidden() {
        assertEquals(Point.FRAME_END, point(true, false, false, false, true));
        assertEquals(Point.FRAME_END, point(true, false, true, false, true));
        // F1 hides the chat lines but not the chat box being typed in.
        assertEquals(Point.SKIP, point(true, false, true, true, true));
    }

    @Test
    void previewIsTheNoMenuFrame() {
        assertEquals(Point.BEFORE_GUI, previewPoint(false, true));
        assertEquals(Point.BEFORE_GUI, previewPoint(false, false));
        assertEquals(Point.BEFORE_CHAT, previewPoint(true, false));
        assertEquals(Point.AFTER_GUI, previewPoint(true, true));
    }
}
