package games.brennan.dungeontrain.client.snapshot;

import java.util.EnumMap;

/**
 * Per-category snapshot cadence. Each {@link SnapshotTag} is gated by BOTH an
 * escalating wall-clock cooldown AND an escalating carriage-progress cooldown,
 * and a tag is only "due" once both have elapsed since that tag's last shot
 * (or, for its first shot, since the run baseline).
 *
 * <p><b>Time gate:</b> the {@code count}-th shot of a tag (0-based) needs
 * {@code seedTicks + count × 1 min} to have passed — i.e. it starts at the
 * tag's seed (1 min for context tags, the scenic interval for SCENIC) and adds
 * a minute after every shot.</p>
 *
 * <p><b>Carriage gate:</b> the threshold {@code X} starts at 1 carriage and
 * grows {@code X ← X×1.5 + 2} after each shot (1, 4, 8, 13, 22 …; kept
 * real-valued, ceiled only at the comparison). {@code progress} is the live
 * carriages-travelled counter; its delta is clamped to {@code ≥ 0} so a mid-run
 * counter reset just makes the gate wait rather than fire early.</p>
 *
 * <p>Pure logic (no Minecraft types) so it is unit-testable; the
 * {@link RideSnapshotDirector} owns the single client-side instance.</p>
 */
public final class SnapshotCooldowns {

    /** Ticks in one minute — the per-shot time increment. */
    public static final long ONE_MINUTE_TICKS = 20L * 60L;
    /** Carriage gate: X starts here, then {@code X = X×X_RATE + X_GROW} per shot. */
    static final double X_SEED = 1.0;
    static final double X_RATE = 1.5;
    static final double X_GROW = 2.0;

    private static final class State {
        long lastTick;
        int lastProgress;
        int count;
        double x = X_SEED;
        boolean committed;
    }

    private final EnumMap<SnapshotTag, State> states = new EnumMap<>(SnapshotTag.class);
    private long baseTick;
    private int baseProgress;
    private boolean based;

    public SnapshotCooldowns() {
        for (SnapshotTag tag : SnapshotTag.values()) states.put(tag, new State());
    }

    /** Anchor the run baseline (first eligible tick) once; later calls are no-ops. */
    public void baseline(long nowTick, int progress) {
        if (!based) {
            baseTick = nowTick;
            baseProgress = progress;
            based = true;
        }
    }

    /**
     * Both gates clear since this tag's last shot (or the run baseline for its
     * first shot). {@code seedTicks} is the tag's first-shot time threshold.
     */
    public boolean due(SnapshotTag tag, long nowTick, int progress, long seedTicks) {
        baseline(nowTick, progress);
        State s = states.get(tag);
        long sinceTick = nowTick - (s.committed ? s.lastTick : baseTick);
        int sinceProgress = progress - (s.committed ? s.lastProgress : baseProgress);
        if (sinceProgress < 0) sinceProgress = 0;
        long timeThreshold = seedTicks + (long) s.count * ONE_MINUTE_TICKS;
        int groupThreshold = (int) Math.ceil(s.x);
        return sinceTick >= timeThreshold && sinceProgress >= groupThreshold;
    }

    /** Record a committed shot of {@code tag} at this clock + progress; grows both gates. */
    public void onCommitted(SnapshotTag tag, long nowTick, int progress) {
        baseline(nowTick, progress);
        State s = states.get(tag);
        s.lastTick = nowTick;
        s.lastProgress = progress;
        s.count++;
        s.x = s.x * X_RATE + X_GROW;
        s.committed = true;
    }

    /** Committed shots of {@code tag} this run (for the chat read-out). */
    public int count(SnapshotTag tag) {
        return states.get(tag).count;
    }

    /** Clear all per-tag state and the run baseline (world leave / run start). */
    public void reset() {
        for (State s : states.values()) {
            s.lastTick = 0L;
            s.lastProgress = 0;
            s.count = 0;
            s.x = X_SEED;
            s.committed = false;
        }
        based = false;
        baseTick = 0L;
        baseProgress = 0;
    }
}
