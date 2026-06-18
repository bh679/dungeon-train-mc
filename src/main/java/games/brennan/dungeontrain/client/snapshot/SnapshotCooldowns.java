package games.brennan.dungeontrain.client.snapshot;

import java.util.EnumMap;

/**
 * Per-category snapshot cadence.
 *
 * <p>The <b>first</b> shot of a tag fires as soon as its trigger occurs — no
 * wait. Taking it starts that category's cooldown; from then on a shot fires
 * only once BOTH gates have elapsed since the tag's last shot:</p>
 * <ul>
 *   <li><b>Time:</b> the cooldown grows one unit per shot — {@code 1 unit}
 *       before the 2nd shot, {@code 2 units} before the 3rd, … ({@code count ×
 *       unitTicks}; a unit is 1 min for context tags, the scenic interval for
 *       SCENIC).</li>
 *   <li><b>Carriage:</b> {@code X} carriages travelled, starting at 1 (the first
 *       cooldown) and growing {@code X ← X×1.5 + 2} from the shot after that
 *       (1, 4, 8, 13, 22 …; kept real-valued, ceiled only at the comparison).</li>
 * </ul>
 *
 * <p>{@code progress} is the live carriages-travelled counter; its delta is
 * clamped to {@code ≥ 0} so a mid-run counter reset just makes the gate wait
 * rather than fire early. Pure logic (no Minecraft types) so it is unit-testable;
 * the {@link RideSnapshotDirector} owns the single client-side instance.</p>
 */
public final class SnapshotCooldowns {

    /** Ticks in one minute — the context-tag cooldown unit. */
    public static final long ONE_MINUTE_TICKS = 20L * 60L;
    /** Carriage gate: X starts here, then {@code X = X×X_RATE + X_GROW} from the 2nd cooldown on. */
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

    public SnapshotCooldowns() {
        for (SnapshotTag tag : SnapshotTag.values()) states.put(tag, new State());
    }

    /**
     * Is a shot of {@code tag} allowed now? The first shot of a tag is always
     * allowed (it fires on its trigger); later shots require both the escalating
     * time cooldown and the escalating carriage cooldown since the last shot.
     * {@code unitTicks} is the tag's per-shot time unit.
     */
    public boolean due(SnapshotTag tag, long nowTick, int progress, long unitTicks) {
        State s = states.get(tag);
        if (!s.committed) return true; // first shot fires on its trigger; the cooldown starts after it
        long sinceTick = nowTick - s.lastTick;
        int sinceProgress = progress - s.lastProgress;
        if (sinceProgress < 0) sinceProgress = 0;
        long timeThreshold = (long) s.count * unitTicks;
        int groupThreshold = (int) Math.ceil(s.x);
        return sinceTick >= timeThreshold && sinceProgress >= groupThreshold;
    }

    /** Record a committed shot of {@code tag} at this clock + progress; grows the gates from the 2nd shot. */
    public void onCommitted(SnapshotTag tag, long nowTick, int progress) {
        State s = states.get(tag);
        if (s.committed) s.x = s.x * X_RATE + X_GROW; // 1st shot starts the cooldown at X_SEED; grow only after that
        s.lastTick = nowTick;
        s.lastProgress = progress;
        s.count++;
        s.committed = true;
    }

    /** Committed shots of {@code tag} this run (for the chat read-out). */
    public int count(SnapshotTag tag) {
        return states.get(tag).count;
    }

    /** Clear all per-tag state (world leave / run start). */
    public void reset() {
        for (State s : states.values()) {
            s.lastTick = 0L;
            s.lastProgress = 0;
            s.count = 0;
            s.x = X_SEED;
            s.committed = false;
        }
    }
}
