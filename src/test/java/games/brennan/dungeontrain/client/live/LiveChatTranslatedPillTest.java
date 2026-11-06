package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveChatTranslatedPillTest {

    @Test
    @DisplayName("the label fades as vanilla's newest chat line does: full, then out over its last 20 ticks")
    void fade() {
        assertEquals(1f, LiveChatTranslatedPill.fade(0));
        assertEquals(1f, LiveChatTranslatedPill.fade(180));
        assertEquals(0.25f, LiveChatTranslatedPill.fade(190), 0.001f);
        assertEquals(0f, LiveChatTranslatedPill.fade(200));
        assertEquals(0f, LiveChatTranslatedPill.fade(500));
    }

    @Test
    @DisplayName("a closed chat counts only lines still showing, newest first, up to its page")
    void linesStillShowing() {
        assertEquals(0, LiveChatTranslatedPill.linesStillShowing(List.of(), 1000, 10));
        assertEquals(2, LiveChatTranslatedPill.linesStillShowing(List.of(990, 900, 700), 1000, 10));
        assertEquals(0, LiveChatTranslatedPill.linesStillShowing(List.of(800), 1000, 10));
        assertEquals(1, LiveChatTranslatedPill.linesStillShowing(List.of(990, 985), 1000, 1));
    }
}
