package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The death menu builds up one thing at a time: a line, read on its own, then its layer, then a settle. */
class MenuRevealTest {

    /** Reading times: stars 4000, earned (none here), active 2500, centre 2800, perks 3500, select 1600. */
    private static final long[] BEATS = {4000, 0, 2500, 2800, 3500, 1600};
    private static final long[] NO_ACTIVE = {4000, 0, 0, 2800, 3500, 1600};
    private static final long L = MenuReveal.LEAD_IN_MS;
    private static final long F = MenuReveal.FADE_MS;
    private static final long SETTLE = MenuReveal.SETTLE_MS;
    /** Each beat's whole time: read, its layer's fade, the settle. */
    private static final long S = 4000 + MenuReveal.STAR_FADE_MS + SETTLE;
    private static final long A = 2500 + F + SETTLE, C = 2800 + F + SETTLE, P = 3500 + F + SETTLE;

    @Test
    @DisplayName("each layer comes in once its line has been read; the next line once that layer is up")
    void readThenLayer() {
        MenuReveal r = new MenuReveal(1000, BEATS, false);
        long t0 = 1000 + L;
        assertEquals(t0, r.beatStart(MenuReveal.BEAT_STARS), "the first line after the plain lead-in");
        assertEquals(t0 + 4000, r.startOf(MenuReveal.Stage.STARS), "the stars once the line has been read");
        assertEquals(t0 + S, r.beatStart(MenuReveal.BEAT_ACTIVE), "the next line once the stars are up and settled");
        assertEquals(t0 + S + 2500, r.startOf(MenuReveal.Stage.ACTIVE));
        assertEquals(t0 + S + A + 2800, r.startOf(MenuReveal.Stage.UNLOCKED));
        assertEquals(t0 + S + A + C + 3500, r.startOf(MenuReveal.Stage.CLASSES));
        long sel = t0 + S + A + C + P;
        assertEquals(sel, r.beatStart(MenuReveal.BEAT_SELECT));
        assertEquals(sel + 1600, r.startOf(MenuReveal.Stage.CLASS_LIST), "the list after the select line's time");
        assertEquals(sel + 1600 + MenuReveal.STEP_MS, r.startOf(MenuReveal.Stage.POINTS));
    }

    @Test
    @DisplayName("a line is never followed by its layer before it has faded in")
    void shortLine() {
        MenuReveal r = new MenuReveal(0, new long[] {300, 0, 0, 300, 300, 1600}, false);
        assertEquals(L + F, r.startOf(MenuReveal.Stage.STARS));
        assertEquals(0.5, r.alpha(MenuReveal.Stage.STARS, L + F + MenuReveal.STAR_FADE_MS / 2), 1e-9, "the stars fade in slowly");
    }

    @Test
    @DisplayName("no points this life: the active beat is skipped")
    void noActive() {
        MenuReveal r = new MenuReveal(0, NO_ACTIVE, false);
        assertEquals(L + S, r.beatStart(MenuReveal.BEAT_CENTRE));
        assertEquals(0.0, r.alpha(MenuReveal.Stage.ACTIVE, 60_000));
        assertEquals(MenuReveal.BEAT_STARS, r.lineAt(L + S - 1));
        assertEquals(MenuReveal.BEAT_CENTRE, r.lineAt(L + S));
    }

    @Test
    @DisplayName("lines follow the beats; the select line stays until a perk is hovered, then the bar takes its place")
    void lines() {
        MenuReveal r = new MenuReveal(0, BEATS, false);
        assertEquals(-1, r.lineAt(0), "nothing during the lead-in");
        assertEquals(MenuReveal.BEAT_STARS, r.lineAt(L));
        assertEquals(MenuReveal.BEAT_ACTIVE, r.lineAt(L + S));
        assertEquals(MenuReveal.BEAT_PERKS, r.lineAt(L + S + A + C));
        assertEquals(MenuReveal.BEAT_SELECT, r.lineAt(600_000), "stays");
        assertTrue(r.lineAlpha(600_000) > 0.99);
        assertTrue(r.lineAlpha(L + S - 1) < 0.1, "a line fades out at the end of its beat");
        assertEquals(MenuReveal.NEVER, r.startOf(MenuReveal.Stage.BAR));
        r.onHover(L + 10);
        assertEquals(MenuReveal.NEVER, r.startOf(MenuReveal.Stage.BAR), "a hover before the select line does not count");
        r.onHover(610_000);
        assertEquals(610_000, r.startOf(MenuReveal.Stage.BAR));
        assertEquals(-1, r.lineAt(610_000), "the bar has the line's place");
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
        assertEquals(1.0, r.alpha(MenuReveal.Stage.BAR, 200 + F));
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
        r.onUnlock(900_000);
        assertEquals(900_000, r.startOf(MenuReveal.Stage.CLASS_LIST));
        assertEquals(900_000 + MenuReveal.STEP_MS, r.startOf(MenuReveal.Stage.POINTS));
        assertTrue(r.done(900_000 + MenuReveal.STEP_MS + F));
    }

    @Test
    @DisplayName("first time: next brings the layer in while its line is read, and the next line once it is in")
    void firstTimeNext() {
        MenuReveal r = new MenuReveal(0, BEATS, true);
        assertTrue(r.canStepLine(100));
        r.next(100);
        assertEquals(100, r.beatStart(MenuReveal.BEAT_STARS), "the lead-in cut short");
        r.next(100 + F + 200);
        assertEquals(100 + F + 200, r.startOf(MenuReveal.Stage.STARS), "reading cut: the stars come in now");
        long starsUp = 100 + F + 200 + MenuReveal.STAR_FADE_MS;
        assertEquals(starsUp + SETTLE, r.beatStart(MenuReveal.BEAT_ACTIVE), "the next line once they are up");
        r.next(starsUp + 50);
        assertEquals(starsUp + 50, r.beatStart(MenuReveal.BEAT_ACTIVE), "layer in: the next line now");
        long a = starsUp + 50;
        r.next(a + 100);
        assertEquals(a + F, r.startOf(MenuReveal.Stage.ACTIVE), "never before the line has faded in");
        assertEquals(MenuReveal.BEAT_SELECT, MenuReveal.BEAT_SELECT);
        long t = a + 10_000;
        for (int k = 0; k < 8; k++) {
            r.next(t);
            t += 10_000;
        }
        assertEquals(MenuReveal.BEAT_SELECT, r.lineAt(t), "steps end on the select line");
        assertFalse(r.canStepLine(t), "the select line waits for a hover");
        assertEquals(1.0, r.alpha(MenuReveal.Stage.ACTIVE, t), "the active perks still show after quick steps");
        MenuReveal returning = new MenuReveal(0, BEATS, false);
        assertFalse(returning.canStepLine(L + 10), "returning players skip to the end instead");
    }

    @Test
    @DisplayName("the earned line is read on its own: nothing new comes in with it")
    void earnedLineOnItsOwn() {
        MenuReveal r = new MenuReveal(0, new long[] {4000, 2000, 0, 2800, 3500, 1600}, false);
        long earned = r.beatStart(MenuReveal.BEAT_EARNED);
        assertEquals(L + S, earned, "after the stars are up");
        assertEquals(MenuReveal.BEAT_EARNED, r.lineAt(earned));
        assertEquals(earned + 2000 + SETTLE, r.beatStart(MenuReveal.BEAT_CENTRE), "its reading time and a settle, no fade");
        assertEquals(0.0, r.alpha(MenuReveal.Stage.UNLOCKED, earned + 1999), "the centre waits for its own line");
    }
}
