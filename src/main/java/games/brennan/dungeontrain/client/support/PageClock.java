package games.brennan.dungeontrain.client.support;

/**
 * How long a page was really in front of the player: wall time since it opened, less any spans it
 * was paused for (a side trip to another screen that returns here, such as the Community Rewards
 * page opened from Contribute). Pure arithmetic on caller-supplied millis, so it can be unit tested.
 * Immutable: every call returns a new clock.
 */
record PageClock(long openedAtMs, long pausedAtMs, long pausedTotalMs) {

    private static final long NOT_PAUSED = -1;

    /** A clock started at {@code nowMs}. */
    static PageClock open(long nowMs) {
        return new PageClock(nowMs, NOT_PAUSED, 0);
    }

    boolean paused() {
        return pausedAtMs != NOT_PAUSED;
    }

    /** Stop counting from {@code nowMs}; a clock already paused is returned as is. */
    PageClock pause(long nowMs) {
        return paused() ? this : new PageClock(openedAtMs, nowMs, pausedTotalMs);
    }

    /** Count again from {@code nowMs}; a clock not paused is returned as is. */
    PageClock resume(long nowMs) {
        if (!paused()) return this;
        return new PageClock(openedAtMs, NOT_PAUSED, pausedTotalMs + Math.max(0, nowMs - pausedAtMs));
    }

    /** Time on the page up to {@code nowMs}, excluding paused spans (an open pause counts to now). */
    long elapsedMs(long nowMs) {
        long paused = pausedTotalMs + (paused() ? Math.max(0, nowMs - pausedAtMs) : 0);
        return Math.max(0, nowMs - openedAtMs - paused);
    }
}
