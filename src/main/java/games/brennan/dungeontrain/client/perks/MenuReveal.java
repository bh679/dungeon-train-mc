package games.brennan.dungeontrain.client.perks;

/**
 * The perks menu opened on death builds up one layer at a time ({@link Stage}, in order) while a line
 * of story ({@link DeathStory}) tells the player what each is. Each story beat holds for its line's
 * time ({@code LoadingStories.lineMs}): the stars, the perks that held points this life (skipped
 * without any), the centre, the rest of the tree, then "select a perk", which stays until they hover
 * one. That hover brings the details bar in where the line was. After the select line has had its
 * time the classes list comes in, then the unlock points one {@link #STEP_MS} later.
 * {@link #skip} brings everything up at once. The menu opened in play uses {@link #allUp()}.
 *
 * <p>It opens on {@link #LEAD_IN_MS} of plain night blue, so the stars start only once the death
 * scene's fade into it has settled. Then one thing at a time: each beat's line fades in first, and only
 * once it is up does its layer fade in ({@link #STAR_FADE_MS} for the stars, {@link #FADE_MS} for the
 * rest); the next line waits until that layer is up.</p>
 *
 * <p>A player who has never unlocked a perk gets a guided first time: no skip to the end, but a click
 * or Space moves on to the next line ({@link #next}); the classes list (then the unlock points) waits
 * for their first unlock ({@link #onUnlock}).</p>
 *
 * <p>Pure but for the skip, hover and unlock times; times are {@code Util.getMillis()}.</p>
 */
final class MenuReveal {

    enum Stage { STARS, ACTIVE, UNLOCKED, CLASSES, CLASS_LIST, BAR, POINTS }

    /** The story beats, in order; each is one line. */
    static final int BEAT_STARS = 0, BEAT_ACTIVE = 1, BEAT_CENTRE = 2, BEAT_PERKS = 3, BEAT_SELECT = 4, BEATS = 5;

    /** The steps after the story: the classes list, then the unlock points. */
    static final long STEP_MS = 1000;
    /** Plain night blue before anything comes in. */
    static final long LEAD_IN_MS = 1000;
    /** Each layer, and each line, fades in over this long... */
    static final long FADE_MS = 1000;
    /** ...but the stars take longer. */
    static final long STAR_FADE_MS = 2500;
    /** A line fades out over this long at the end of its beat. */
    static final long LINE_OUT_MS = 300;
    /** A layer still waiting on the player: it has no start yet. */
    static final long NEVER = Long.MAX_VALUE;

    private final long openedAt;
    /** Each beat's time on screen, ms; {@link #BEAT_ACTIVE} is 0 when there were no points this life. Cut short by {@link #next}. */
    private final long[] beats;
    private final boolean hasActive;
    private final boolean firstTime;
    /** The lead-in, cut short when the first time moves on during it. */
    private long leadIn = LEAD_IN_MS;
    private long skippedAt = -1;
    private long hoverAt = NEVER;
    private long unlockAt = NEVER;

    MenuReveal(long openedAt, long[] beats, boolean firstTime) {
        if (beats.length != BEATS) throw new IllegalArgumentException("expected " + BEATS + " beats");
        this.openedAt = openedAt;
        this.beats = beats.clone();
        // one thing at a time: a beat lasts at least its line's fade-in plus its layer's
        for (int i = BEAT_STARS; i < BEAT_SELECT; i++) {
            if (this.beats[i] > 0) this.beats[i] = Math.max(this.beats[i], FADE_MS + layerFadeMs(i));
        }
        this.hasActive = beats[BEAT_ACTIVE] > 0;
        this.firstTime = firstTime;
    }

    /** Every layer already up and no story: the menu opened in play. */
    static MenuReveal allUp() {
        MenuReveal r = new MenuReveal(Long.MIN_VALUE / 2, new long[] {1, 1, 1, 1, 1}, false);
        r.skippedAt = Long.MIN_VALUE / 2;
        r.hoverAt = Long.MIN_VALUE / 2;
        return r;
    }

    /** How long beat {@code i}'s layer takes to fade in. */
    private static long layerFadeMs(int i) {
        return i == BEAT_STARS ? STAR_FADE_MS : FADE_MS;
    }

    boolean firstTime() {
        return firstTime;
    }

    private boolean hasActive() {
        return hasActive;
    }

    /** When beat {@code i} starts. */
    long beatStart(int i) {
        long t = openedAt + leadIn;
        for (int b = 0; b < i; b++) t += beats[b];
        return t;
    }

    /** When {@code s} starts to fade in, or {@link #NEVER} while it waits on the player. */
    long startOf(Stage s) {
        return switch (s) {
            // each layer once its line has faded in
            case STARS -> beatStart(BEAT_STARS) + FADE_MS;
            case ACTIVE -> beatStart(BEAT_ACTIVE) + FADE_MS;
            case UNLOCKED -> beatStart(BEAT_CENTRE) + FADE_MS;
            case CLASSES -> beatStart(BEAT_PERKS) + FADE_MS;
            case CLASS_LIST -> {
                long afterSelect = beatStart(BEAT_SELECT) + beats[BEAT_SELECT];
                if (!firstTime) yield afterSelect;
                yield unlockAt == NEVER ? NEVER : Math.max(unlockAt, afterSelect);
            }
            case BAR -> hoverAt;
            case POINTS -> {
                long list = startOf(Stage.CLASS_LIST);
                yield list == NEVER ? NEVER : list + STEP_MS;
            }
        };
    }

    /** How far {@code s} has faded in, 0..1. With no points this life, {@link Stage#ACTIVE} has nothing to show. */
    double alpha(Stage s, long now) {
        if (s == Stage.ACTIVE && !hasActive()) return 0;
        if (skippedAt >= 0 && s != Stage.BAR) return 1;
        long start = startOf(s);
        if (start == NEVER || now <= start) return 0;
        long t = now - start;
        long fadeMs = s == Stage.STARS ? STAR_FADE_MS : FADE_MS;
        return t >= fadeMs ? 1 : t / (double) fadeMs;
    }

    /** The story beat whose line shows now, or -1 once the details bar has taken its place. */
    int lineAt(long now) {
        if (hoverAt != NEVER && now >= hoverAt) return -1;
        if (skippedAt >= 0) return BEAT_SELECT;
        if (now < beatStart(BEAT_STARS)) return -1; // the lead-in: nothing yet
        for (int i = BEATS - 1; i >= 0; i--) {
            if (beats[i] > 0 && now >= beatStart(i)) return i;
        }
        return BEAT_STARS;
    }

    /** The current line's alpha: in over {@link #FADE_MS}, out over {@link #LINE_OUT_MS} at its beat's end. */
    double lineAlpha(long now) {
        int i = lineAt(now);
        if (i < 0) return 0;
        if (skippedAt >= 0) return 1;
        long start = beatStart(i);
        double in = Math.min(1, (now - start) / (double) FADE_MS);
        if (i == BEAT_SELECT) return in; // stays until they hover a perk
        double out = Math.min(1, (start + beats[i] - now) / (double) LINE_OUT_MS);
        return Math.max(0, Math.min(in, out));
    }

    /** When the last layer is fully up (or the moment it was skipped); {@link #NEVER} while one waits. */
    long doneAt() {
        if (skippedAt >= 0) return skippedAt;
        long last = startOf(Stage.POINTS);
        return last == NEVER ? NEVER : last + FADE_MS;
    }

    boolean done(long now) {
        return now >= doneAt();
    }

    /** The menu takes input: once all is up, or on the first time once the tree is there to hover. */
    boolean live(long now) {
        return done(now) || firstTime && now >= startOf(Stage.CLASSES);
    }

    /** A click or key during the build-up: everything comes up now. Never on the first time. */
    void skip(long now) {
        if (!firstTime && !done(now)) skippedAt = now;
    }

    /**
     * First time: a click or Space moves on to the next line now (cutting the current beat, or the
     * lead-in, short). Nothing past the select line, which waits for a hover.
     */
    void next(long now) {
        if (!firstTime) return;
        if (now < beatStart(BEAT_STARS)) {
            leadIn = Math.max(0, now - openedAt);
            return;
        }
        int i = lineAt(now);
        if (i < 0 || i >= BEAT_SELECT) return;
        beats[i] = Math.max(1, now - beatStart(i));
    }

    /** First time and a line before "select a perk" is up (or still to come): {@link #next} has somewhere to go. */
    boolean canStepLine(long now) {
        if (!firstTime || hoverAt != NEVER && now >= hoverAt) return false;
        int i = lineAt(now);
        return i < BEAT_SELECT;
    }

    /**
     * The player hovered a perk. Counts once the select line is up (or the build-up was skipped): the
     * details bar comes in where the line was.
     */
    void onHover(long now) {
        if (hoverAt != NEVER) return;
        if (skippedAt >= 0 || now >= beatStart(BEAT_SELECT)) hoverAt = now;
    }

    /** First time: the player unlocked their first perk; the classes list comes in, then the points. */
    void onUnlock(long now) {
        if (unlockAt == NEVER) unlockAt = now;
    }
}
