package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The death menu builds up one layer per story line, each held for its line's time. */
class MenuRevealTest {

    /** stars 2000, active 1500, centre 1800, perks 2500, select 1600. */
    private static final long[] BEATS = {2000, 1500, 1800, 2500, 1600};
    private static final long[] NO_ACTIVE = {2000, 0, 1800, 2500, 1600};

    @Test
    @DisplayName("each layer starts when the line before it has had its time")
    void startsFromLineTimes() {
        MenuReveal r = new MenuReveal(1000, BEATS, false);
        assertEquals(1000, r.startOf(MenuReveal.Stage.STARS));
        assertEquals(3000, r.startOf(MenuReveal.Stage.ACTIVE));
        assertEquals(4500, r.startOf(MenuReveal.Stage.UNLOCKED));
        assertEquals(6300, r.startOf(MenuReveal.Stage.CLASSES));
        assertEquals(8800, r.beatStart(MenuReveal.BEAT_SELECT));
        assertEquals(8800 + 1600, r.startOf(MenuReveal.Stage.CLASS_LIST), "the list after the select line's time");
        assertEquals(8800 + 1600 + MenuReveal.STEP_MS, r.startOf(MenuReveal.Stage.POINTS));
    }

    @Test
    @DisplayName("no points this life: the active beat is skipped")
    void noActive() {
        MenuReveal r = new MenuReveal(0, NO_ACTIVE, false);
        assertEquals(2000, r.startOf(MenuReveal.Stage.UNLOCKED));
        assertEquals(0.0, r.alpha(MenuReveal.Stage.ACTIVE, 60_000));
        assertEquals(MenuReveal.BEAT_STARS, r.lineAt(1999));
        assertEquals(MenuReveal.BEAT_CENTRE, r.lineAt(2000));
    }

    @Test
    @DisplayName("lines follow the beats; the select line stays until a perk is hovered, then the bar takes its place")
    void lines() {
        MenuReveal r = new MenuReveal(0, BEATS, false);
        assertEquals(MenuReveal.BEAT_STARS, r.lineAt(0));
        assertEquals(MenuReveal.BEAT_ACTIVE, r.lineAt(2000));
        assertEquals(MenuReveal.BEAT_PERKS, r.lineAt(6000));
        assertEquals(MenuReveal.BEAT_SELECT, r.lineAt(60_000), "stays");
        assertTrue(r.lineAlpha(60_000) > 0.99);
        assertTrue(r.lineAlpha(2000 - 1) < 0.1, "a line fades out at the end of its beat");
        assertEquals(MenuReveal.NEVER, r.startOf(MenuReveal.Stage.BAR));
        r.onHover(3000);
        assertEquals(MenuReveal.NEVER, r.startOf(MenuReveal.Stage.BAR), "a hover before the select line does not count");
        r.onHover(61_000);
        assertEquals(61_000, r.startOf(MenuReveal.Stage.BAR));
        assertEquals(-1, r.lineAt(61_000), "the bar has the line's place");
    }

    @Test
    @DisplayName("skip brings every layer up at once and leaves the select line until a hover")
    void skip() {
        MenuReveal r = new MenuReveal(0, BEATS, false);
        assertFalse(r.done(100));
        r.skip(100);
        assertTrue(r.done(100));
        for (MenuReveal.Stage s : MenuReveal.Stage.values()) {
            if (s != MenuReveal.Stage.BAR) assertEquals(1.0, r.alpha(s, 100), s.name());
        }
        assertEquals(MenuReveal.BEAT_SELECT, r.lineAt(100));
        r.onHover(200);
        assertEquals(1.0, r.alpha(MenuReveal.Stage.BAR, 200 + MenuReveal.FADE_MS));
    }

    @Test
    @DisplayName("the menu in play starts all up, bar included, with no story")
    void allUp() {
        MenuReveal play = MenuReveal.allUp();
        assertTrue(play.done(0));
        for (MenuReveal.Stage s : MenuReveal.Stage.values()) assertEquals(1.0, play.alpha(s, 0), s.name());
        assertEquals(-1, play.lineAt(0));
    }

    @Test
    @DisplayName("first time: no skip; the list and points wait for the first unlock")
    void firstTime() {
        MenuReveal r = new MenuReveal(0, NO_ACTIVE, true);
        r.skip(100);
        assertEquals(0.0, r.alpha(MenuReveal.Stage.CLASSES, 100), "a click does not skip the first time");
        assertEquals(MenuReveal.NEVER, r.startOf(MenuReveal.Stage.CLASS_LIST));
        assertEquals(MenuReveal.NEVER, r.startOf(MenuReveal.Stage.POINTS));
        assertFalse(r.done(600_000));
        assertFalse(r.live(r.startOf(MenuReveal.Stage.CLASSES) - 1));
        assertTrue(r.live(r.startOf(MenuReveal.Stage.CLASSES)), "input once the tree is there to hover");
        r.onUnlock(1);
        long afterSelect = r.beatStart(MenuReveal.BEAT_SELECT) + NO_ACTIVE[MenuReveal.BEAT_SELECT];
        assertEquals(afterSelect, r.startOf(MenuReveal.Stage.CLASS_LIST), "not before the select line has had its time");
        r = new MenuReveal(0, NO_ACTIVE, true);
        r.onUnlock(90_000);
        assertEquals(90_000, r.startOf(MenuReveal.Stage.CLASS_LIST));
        assertEquals(90_000 + MenuReveal.STEP_MS, r.startOf(MenuReveal.Stage.POINTS));
        assertTrue(r.done(90_000 + MenuReveal.STEP_MS + MenuReveal.FADE_MS));
    }
}
