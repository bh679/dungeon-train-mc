package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The death menu builds up one layer per story line, each held for its line's time. */
class MenuRevealTest {

    /** stars 3000, active 1500, centre 1800, perks 2500, select 1600 (after the lead-in). */
    private static final long[] BEATS = {3000, 1500, 1800, 2500, 1600};
    private static final long[] NO_ACTIVE = {3000, 0, 1800, 2500, 1600};
    private static final long L = MenuReveal.LEAD_IN_MS;

    @Test
    @DisplayName("each layer starts when the line before it has had its time")
    void startsFromLineTimes() {
        MenuReveal r = new MenuReveal(1000, BEATS, false);
        assertEquals(1000 + L, r.startOf(MenuReveal.Stage.STARS), "after the plain lead-in");
        assertEquals(4000 + L, r.startOf(MenuReveal.Stage.ACTIVE));
        assertEquals(5500 + L, r.startOf(MenuReveal.Stage.UNLOCKED));
        assertEquals(7300 + L, r.startOf(MenuReveal.Stage.CLASSES));
        assertEquals(9800 + L, r.beatStart(MenuReveal.BEAT_SELECT));
        assertEquals(9800 + L + 1600, r.startOf(MenuReveal.Stage.CLASS_LIST), "the list after the select line's time");
        assertEquals(9800 + L + 1600 + MenuReveal.STEP_MS, r.startOf(MenuReveal.Stage.POINTS));
    }

    @Test
    @DisplayName("no points this life: the active beat is skipped")
    void noActive() {
        MenuReveal r = new MenuReveal(0, NO_ACTIVE, false);
        assertEquals(3000 + L, r.startOf(MenuReveal.Stage.UNLOCKED));
        assertEquals(0.0, r.alpha(MenuReveal.Stage.ACTIVE, 60_000));
        assertEquals(MenuReveal.BEAT_STARS, r.lineAt(2999 + L));
        assertEquals(MenuReveal.BEAT_CENTRE, r.lineAt(3000 + L));
    }

    @Test
    @DisplayName("lines follow the beats; the select line stays until a perk is hovered, then the bar takes its place")
    void lines() {
        MenuReveal r = new MenuReveal(0, BEATS, false);
        assertEquals(-1, r.lineAt(0), "nothing during the lead-in");
        assertEquals(MenuReveal.BEAT_STARS, r.lineAt(L));
        assertEquals(MenuReveal.BEAT_ACTIVE, r.lineAt(3000 + L));
        assertEquals(MenuReveal.BEAT_PERKS, r.lineAt(7000 + L));
        assertEquals(MenuReveal.BEAT_SELECT, r.lineAt(60_000), "stays");
        assertTrue(r.lineAlpha(60_000) > 0.99);
        assertTrue(r.lineAlpha(3000 + L - 1) < 0.1, "a line fades out at the end of its beat");
        assertEquals(MenuReveal.NEVER, r.startOf(MenuReveal.Stage.BAR));
        r.onHover(4000);
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

    @Test
    @DisplayName("the stars fade in slowly, and their beat lasts at least that long")
    void starsSlow() {
        MenuReveal r = new MenuReveal(0, new long[] {500, 0, 1800, 2500, 1600}, false);
        assertEquals(L + MenuReveal.STAR_FADE_MS, r.startOf(MenuReveal.Stage.UNLOCKED));
        assertEquals(0.5, r.alpha(MenuReveal.Stage.STARS, L + MenuReveal.STAR_FADE_MS / 2), 1e-9);
    }

    @Test
    @DisplayName("first time: next moves on to the following line now, and stops at the select line")
    void firstTimeNext() {
        MenuReveal r = new MenuReveal(0, BEATS, true);
        assertTrue(r.canStepLine(100));
        r.next(100);
        assertEquals(100, r.beatStart(MenuReveal.BEAT_STARS), "the lead-in cut short");
        r.next(500);
        assertEquals(MenuReveal.BEAT_ACTIVE, r.lineAt(500));
        r.next(600);
        assertEquals(MenuReveal.BEAT_CENTRE, r.lineAt(600));
        r.next(700);
        assertEquals(MenuReveal.BEAT_PERKS, r.lineAt(700));
        assertEquals(700, r.startOf(MenuReveal.Stage.CLASSES));
        r.next(800);
        assertEquals(MenuReveal.BEAT_SELECT, r.lineAt(800));
        assertFalse(r.canStepLine(800), "the select line waits for a hover");
        r.next(900);
        assertEquals(MenuReveal.BEAT_SELECT, r.lineAt(60_000));
        assertEquals(1.0, r.alpha(MenuReveal.Stage.ACTIVE, 60_000), "the active perks still show after a quick step");
        MenuReveal returning = new MenuReveal(0, BEATS, false);
        assertFalse(returning.canStepLine(L + 10), "returning players skip to the end instead");
    }
}
