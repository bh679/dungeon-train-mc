package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.client.live.KidStreamChat.Mode;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.KidStreamChat.mode;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** For a Kid-mode streamer no grabbed frame may carry chat lines or the chat box. */
class KidStreamChatTest {

    @Test
    void adultsAlwaysGrabTheFinishedFrame() {
        assertEquals(Mode.GRAB_AT_END, mode(false, false, false, false));
        assertEquals(Mode.GRAB_AT_END, mode(false, true, true, false));
        assertEquals(Mode.GRAB_AT_END, mode(false, true, false, false));
        assertEquals(Mode.GRAB_AT_END, mode(false, true, true, true));
    }

    @Test
    void kidInWorldOrTypingIsGrabbedBeforeChatDraws() {
        assertEquals(Mode.GRAB_BEFORE_CHAT, mode(true, false, false, false));
        assertEquals(Mode.GRAB_BEFORE_CHAT, mode(true, true, true, false));
    }

    @Test
    void kidWithAMenuOpenHidesTheChatBehindIt() {
        assertEquals(Mode.HIDE_CHAT_GRAB_AT_END, mode(true, true, false, false));
    }

    @Test
    void kidWithTheGuiHidden() {
        assertEquals(Mode.GRAB_AT_END, mode(true, false, false, true));
        assertEquals(Mode.GRAB_AT_END, mode(true, true, false, true));
        // F1 hides the chat lines but not the chat box being typed in.
        assertEquals(Mode.SKIP, mode(true, true, true, true));
    }
}
