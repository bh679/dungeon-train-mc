package games.brennan.dungeontrain.ship.sable;

import java.util.concurrent.atomic.LongAdder;

/**
 * Accumulates the cost of Sable's native physics step so the {@code [mspt]} line can attribute it
 * directly ({@code physMs=}) instead of the jstack sampling that found it: on 2026-09-18 ~70% of
 * the single-player server thread sat inside {@code Rapier3D.step} while a survival rider's digs
 * landed seconds late, and nothing in the log said so.
 *
 * <p>Fed by {@code RapierPipelineTimingMixin}: {@code physicsTick} (one native step per substep)
 * adds its wall time to {@link #stepNanos}; {@code handleBlockChange} (the per-block voxel-collider
 * update a mined or placed block on a carriage triggers) bumps {@link #blockChanges}. Drained once
 * per {@code [mspt]} window by {@code TrainTickEvents}, so the reported step time is the mean per
 * tick over that window. Both counters are process-wide — Sable holds one pipeline per level, but
 * only the train dimension steps anything, so the window is the train's.</p>
 *
 * <p>{@link LongAdder}: the writers are on the server thread and the reader is too, but the
 * command thread may read a status snapshot; an adder keeps that a plain, lock-free read.</p>
 */
public final class PhysicsStepTimer {

    private static final LongAdder stepNanos = new LongAdder();
    private static final LongAdder blockChanges = new LongAdder();

    private PhysicsStepTimer() {}

    /** Called by the timing mixin at the end of every {@code physicsTick}. */
    public static void addStepNanos(long nanos) {
        if (nanos > 0) stepNanos.add(nanos);
    }

    /** Called by the timing mixin on every {@code handleBlockChange}. */
    public static void countBlockChange() {
        blockChanges.increment();
    }

    /** One drained {@code [mspt]} window. */
    public record Window(long stepNanos, long blockChanges) {
        /** Mean native-step wall time per tick over a window of {@code ticks} ticks, in ms. */
        public double avgStepMs(int ticks) {
            return ticks <= 0 ? 0.0 : stepNanos / 1_000_000.0 / ticks;
        }
    }

    /** Read and reset both counters — call from exactly one place per window. */
    public static Window drain() {
        return new Window(stepNanos.sumThenReset(), blockChanges.sumThenReset());
    }
}
