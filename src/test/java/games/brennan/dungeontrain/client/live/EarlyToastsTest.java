package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The early toast draw skips exactly one end-of-frame draw, and never a later frame's. */
class EarlyToastsTest {

    @Test
    void markedFrameSkipsTheEndOfFrameDrawOnce() {
        EarlyToasts.consume();
        EarlyToasts.mark();
        assertTrue(EarlyToasts.consume(), "toasts drawn early: skip the end-of-frame draw");
        assertFalse(EarlyToasts.consume(), "the next frame draws toasts as usual");
    }

    @Test
    void unmarkedFrameDrawsToastsAsUsual() {
        EarlyToasts.consume();
        assertFalse(EarlyToasts.consume());
    }
}
