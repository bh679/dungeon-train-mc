package games.brennan.dungeontrain.client.perks;

/**
 * The perks menu opened on death builds up one layer at a time ({@link Stage}, in order): the stars,
 * the perks that held points this life, the unlocked perks, the rest of the drawn tree, the classes
 * list, the details bar, then the unlock points. Each layer starts {@link #STEP_MS} after the one
 * before and fades in over {@link #FADE_MS}; a life with no points skips {@link Stage#ACTIVE}.
 * {@link #skip} brings everything up at once. The menu opened in play uses {@link #allUp()}.
 * Pure but for the skip time; times are {@code Util.getMillis()}.
 */
final class MenuReveal {

    enum Stage { STARS, ACTIVE, UNLOCKED, CLASSES, CLASS_LIST, BAR, POINTS }

    static final long STEP_MS = 450;
    static final long FADE_MS = 450;

    private final long openedAt;
    private final boolean hasActive;
    private long skippedAt = -1;

    MenuReveal(long openedAt, boolean hasActive) {
        this.openedAt = openedAt;
        this.hasActive = hasActive;
    }

    /** Every layer already up: the menu opened in play. */
    static MenuReveal allUp() {
        return new MenuReveal(Long.MIN_VALUE / 2, true);
    }

    /** When {@code s} starts to fade in. */
    long startOf(Stage s) {
        int index = s.ordinal();
        if (!hasActive && s.ordinal() > Stage.ACTIVE.ordinal()) index--;
        return openedAt + index * STEP_MS;
    }

    /** How far {@code s} has faded in, 0..1. With no points this life, {@link Stage#ACTIVE} has nothing to show. */
    double alpha(Stage s, long now) {
        if (s == Stage.ACTIVE && !hasActive) return 0;
        if (skippedAt >= 0) return 1;
        long t = now - startOf(s);
        if (t <= 0) return 0;
        return t >= FADE_MS ? 1 : t / (double) FADE_MS;
    }

    /** When the last layer is fully up (or the moment it was skipped). */
    long doneAt() {
        return skippedAt >= 0 ? skippedAt : startOf(Stage.POINTS) + FADE_MS;
    }

    boolean done(long now) {
        return now >= doneAt();
    }

    /** A click or key during the build-up: everything comes up now. */
    void skip(long now) {
        if (!done(now)) skippedAt = now;
    }
}
