package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    @DisplayName("only a translated line on screen counts, and the newest one sets the fade")
    void newestTranslated() {
        assertEquals(980, LiveChatTranslatedPill.newestTranslated(List.of(990, 980, 970), Set.of(970, 980)));
        assertNull(LiveChatTranslatedPill.newestTranslated(List.of(990, 985), Set.of(970, 980)), "scrolled or aged off screen");
        assertNull(LiveChatTranslatedPill.newestTranslated(List.of(), Set.of(970)));
    }

    @Test
    @DisplayName("one line of Google's on screen owes the credit, even if it isn't the newest")
    void googleOnScreen() {
        Map<Integer, Boolean> ticks = Map.of(970, true, 980, false);
        assertTrue(LiveChatTranslatedPill.googleOnScreen(List.of(980, 970), ticks));
        assertFalse(LiveChatTranslatedPill.googleOnScreen(List.of(990, 980), ticks));
    }
}
