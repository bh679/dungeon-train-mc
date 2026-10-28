package games.brennan.dungeontrain.client.perks;

/**
 * The perks menu opened on death builds up one layer at a time ({@link Stage}, in order): the stars,
 * the perks that held points this life, the unlocked perks, the rest of the drawn tree, the classes
 * list, the details bar, then the unlock points. Each layer starts {@link #STEP_MS} after the one
 * before and fades in over {@link #FADE_MS}; a life with no points skips {@link Stage#ACTIVE}.
 * {@link #skip} brings everything up at once. The menu opened in play uses {@link #allUp()}.
 *
 * <p>A player who has never unlocked a perk gets a guided first time: layers {@link #FIRST_STEP_MS}
 * apart, no skipping, and a pause after the tree. The classes list waits for their first unlock
 * ({@link #onUnlock}), the unlock points follow it; the details bar comes in as soon as they hover a
 * perk ({@link #onHover}), so they can unlock one, else in its turn after the list.</p>
 *
 * <p>Pure but for the skip, hover and unlock times; times are {@code Util.getMillis()}.</p>
 */
final class MenuReveal {

    enum Stage { STARS, ACTIVE, UNLOCKED, CLASSES, CLASS_LIST, BAR, POINTS }

    static final long STEP_MS = 1000;
    static final long FIRST_STEP_MS = 2000;
    static final long FADE_MS = 600;
    /** A layer still waiting on the player: it has no start yet. */
    static final long NEVER = Long.MAX_VALUE;

    private final long openedAt;
    private final boolean hasActive;
    private final boolean firstTime;
    private long skippedAt = -1;
    private long hoverAt = NEVER;
    private long unlockAt = NEVER;

    MenuReveal(long openedAt, boolean hasActive) {
        this(openedAt, hasActive, false);
    }

    MenuReveal(long openedAt, boolean hasActive, boolean firstTime) {
        this.openedAt = openedAt;
        this.hasActive = hasActive && !firstTime;
        this.firstTime = firstTime;
    }

    /** Every layer already up: the menu opened in play. */
    static MenuReveal allUp() {
        return new MenuReveal(Long.MIN_VALUE / 2, true);
    }

    boolean firstTime() {
        return firstTime;
    }

    private long step() {
        return firstTime ? FIRST_STEP_MS : STEP_MS;
    }

    /** When {@code s} starts to fade in, or {@link #NEVER} while it waits on the player. */
    long startOf(Stage s) {
        if (firstTime) {
            switch (s) {
                case CLASS_LIST -> {
                    return unlockAt == NEVER ? NEVER : Math.max(unlockAt, after(startOf(Stage.CLASSES)));
                }
                case BAR -> {
                    return Math.min(hoverAt == NEVER ? NEVER : Math.max(hoverAt, startOf(Stage.CLASSES)),
                        after(startOf(Stage.CLASS_LIST)));
                }
                case POINTS -> {
                    return after(Math.max(startOf(Stage.CLASS_LIST), startOf(Stage.BAR)));
                }
                default -> { }
            }
        }
        int index = s.ordinal();
        if (!hasActive && s.ordinal() > Stage.ACTIVE.ordinal()) index--;
        return openedAt + index * step();
    }

    /** One step after {@code t}; still {@link #NEVER} when {@code t} is. */
    private long after(long t) {
        return t == NEVER ? NEVER : t + step();
    }

    /** How far {@code s} has faded in, 0..1. With no points this life, {@link Stage#ACTIVE} has nothing to show. */
    double alpha(Stage s, long now) {
        if (s == Stage.ACTIVE && !hasActive) return 0;
        if (skippedAt >= 0) return 1;
        long start = startOf(s);
        if (start == NEVER || now <= start) return 0;
        long t = now - start;
        return t >= FADE_MS ? 1 : t / (double) FADE_MS;
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

    /** First time: the player hovered a perk; the details bar comes in. */
    void onHover(long now) {
        if (hoverAt == NEVER) hoverAt = now;
    }

    /** First time: the player unlocked their first perk; the classes list comes in, then the points. */
    void onUnlock(long now) {
        if (unlockAt == NEVER) unlockAt = now;
    }
}
