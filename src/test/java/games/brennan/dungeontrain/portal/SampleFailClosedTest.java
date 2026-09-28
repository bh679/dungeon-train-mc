package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dimensional carriage named for one look never shows another because something failed: a stretch
 * check that throws rejects the site, and a vanilla room whose own generator is missing gets none
 * rather than the live, modded one.
 */
class SampleFailClosedTest {

    @Test
    @DisplayName("a stretch check that throws rejects the site")
    void throwingStretchCheckRejects() {
        assertFalse(PortalChunkTerrain.stretchAccepts(() -> {
            throw new IllegalStateException("cycle unavailable");
        }));
    }

    @Test
    @DisplayName("a stretch check that answers is passed through")
    void answeringStretchCheckPassesThrough() {
        assertTrue(PortalChunkTerrain.stretchAccepts(() -> true));
        assertFalse(PortalChunkTerrain.stretchAccepts(() -> false));
    }

    @Test
    @DisplayName("a vanilla room without its own generator gets none, never the live one")
    void vanillaRoomNeverFallsBackToLive() {
        Object live = new Object();
        assertNull(SampleGenerators.pick(true, null, live));
    }

    @Test
    @DisplayName("a vanilla room with its own generator uses it; other rooms use the live one")
    void pickPrefersOwnForVanillaOnly() {
        Object own = new Object();
        Object live = new Object();
        assertSame(own, SampleGenerators.pick(true, own, live));
        assertSame(live, SampleGenerators.pick(false, own, live));
    }
}
