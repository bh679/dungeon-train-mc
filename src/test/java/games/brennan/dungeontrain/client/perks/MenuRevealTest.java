package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The death menu's layers come in one at a time, in order. */
class MenuRevealTest {

    @Test
    @DisplayName("layers start one step apart in order: stars, active, unlocked, classes, list, bar, points")
    void inOrder() {
        MenuReveal r = new MenuReveal(1000, true);
        long prev = -1;
        for (MenuReveal.Stage s : MenuReveal.Stage.values()) {
            assertTrue(r.startOf(s) > prev, s.name());
            prev = r.startOf(s);
        }
        assertEquals(1000, r.startOf(MenuReveal.Stage.STARS));
        assertEquals(1000 + MenuReveal.STEP_MS, r.startOf(MenuReveal.Stage.ACTIVE));
        assertEquals(1000 + 6 * MenuReveal.STEP_MS, r.startOf(MenuReveal.Stage.POINTS));
    }

    @Test
    @DisplayName("each layer fades in over FADE_MS from its start")
    void fades() {
        MenuReveal r = new MenuReveal(0, true);
        long start = r.startOf(MenuReveal.Stage.UNLOCKED);
        assertEquals(0.0, r.alpha(MenuReveal.Stage.UNLOCKED, start));
        assertEquals(0.5, r.alpha(MenuReveal.Stage.UNLOCKED, start + MenuReveal.FADE_MS / 2), 1e-9);
        assertEquals(1.0, r.alpha(MenuReveal.Stage.UNLOCKED, start + MenuReveal.FADE_MS));
        assertEquals(0.0, r.alpha(MenuReveal.Stage.POINTS, start + MenuReveal.FADE_MS), "later layers wait their turn");
    }

    @Test
    @DisplayName("no points this life: the active layer is skipped and the rest move up a step")
    void noActive() {
        MenuReveal r = new MenuReveal(0, false);
        assertEquals(MenuReveal.STEP_MS, r.startOf(MenuReveal.Stage.UNLOCKED));
        assertEquals(0.0, r.alpha(MenuReveal.Stage.ACTIVE, 60_000));
    }

    @Test
    @DisplayName("skip brings every layer up at once; the menu in play starts all up")
    void skipAndAllUp() {
        MenuReveal r = new MenuReveal(0, true);
        assertFalse(r.done(100));
        r.skip(100);
        assertTrue(r.done(100));
        for (MenuReveal.Stage s : MenuReveal.Stage.values()) assertEquals(1.0, r.alpha(s, 100), s.name());
        MenuReveal play = MenuReveal.allUp();
        assertTrue(play.done(0));
        for (MenuReveal.Stage s : MenuReveal.Stage.values()) assertEquals(1.0, play.alpha(s, 0), s.name());
    }
}
