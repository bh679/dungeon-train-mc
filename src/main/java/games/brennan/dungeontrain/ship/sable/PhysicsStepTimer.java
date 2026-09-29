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
 * update a mined or placed block on a carriage triggers) bumps {@link #blockChanges}; {@code ColliderBatch}
 * reports the per-block updates it deferred over a carriage stamp ({@link #batchedBlockChanges}) and
 * the whole-section uploads it replaced them with ({@link #colliderRebuilds}). Drained once
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
    private static final LongAdder reanchors = new LongAdder();
    private static final LongAdder colliderRebuilds = new LongAdder();
    private static final LongAdder batchedBlockChanges = new LongAdder();

    private PhysicsStepTimer() {}

    /**
     * Called by {@code TrainTransformProvider} on every {@code [reanchor]} — a carriage that
     * rejoined its siblings by extrapolation after a tick gap. A pair re-anchoring every 160 ticks
     * ran alongside 100–200 ms physics steps in a player log of 28 Sep 2026; counting it per
     * window puts that next to {@code physMs=} without grepping two log lines together.
     */
    public static void countReanchor() {
        reanchors.increment();
    }

    /** Called by the timing mixin at the end of every {@code physicsTick}. */
    public static void addStepNanos(long nanos) {
        if (nanos > 0) stepNanos.add(nanos);
    }

    /**
     * Called by the timing mixin on every {@code handleBlockChange} that reached the pipeline — the
     * un-batched per-block updates (mining, placing, redstone, explosions). Block changes a
     * {@link ColliderBatch} scope deferred are counted by {@link #addBatchedBlockChanges} instead.
     */
    public static void countBlockChange() {
        blockChanges.increment();
    }

    /** Called by {@link ColliderBatch} once per chunk section it re-uploaded whole. */
    public static void countColliderRebuild() {
        colliderRebuilds.increment();
    }

    /** Called by {@link ColliderBatch} at scope exit with how many per-block updates it skipped. */
    public static void addBatchedBlockChanges(long n) {
        if (n > 0) batchedBlockChanges.add(n);
    }

    /** One drained {@code [mspt]} window. */
    public record Window(long stepNanos, long blockChanges, long reanchors,
                         long colliderRebuilds, long batchedBlockChanges) {
        /** Mean native-step wall time per tick over a window of {@code ticks} ticks, in ms. */
        public double avgStepMs(int ticks) {
            return ticks <= 0 ? 0.0 : stepNanos / 1_000_000.0 / ticks;
        }
    }

    /** Read and reset every counter — call from exactly one place per window. */
    public static Window drain() {
        return new Window(stepNanos.sumThenReset(), blockChanges.sumThenReset(), reanchors.sumThenReset(),
            colliderRebuilds.sumThenReset(), batchedBlockChanges.sumThenReset());
    }
}
