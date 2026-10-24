package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveDeathChatTest {

    @Test
    void showsOnlyOnADeathScreenWhileStillOnAirAndInTheRoom() {
        assertTrue(LiveDeathChat.active(true, true, true));
        assertFalse(LiveDeathChat.active(false, true, true), "stream ended or taken over");
        assertFalse(LiveDeathChat.active(true, false, true), "not in the chat room");
        assertFalse(LiveDeathChat.active(true, true, false), "some other screen");
    }

    @Test
    void chatKeyLeavesTheSurveyCommentBoxAlone() {
        assertTrue(LiveDeathChat.mayOpen(true, false, false));
        assertFalse(LiveDeathChat.mayOpen(true, false, true), "the page's own text field has the keyboard");
        assertFalse(LiveDeathChat.mayOpen(true, true, false), "already typing");
        assertFalse(LiveDeathChat.mayOpen(false, false, false));
    }
}
