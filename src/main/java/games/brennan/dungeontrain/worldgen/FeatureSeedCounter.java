package games.brennan.dungeontrain.worldgen;

/**
 * A per-thread copy of BCLib's feature-seed counter, so decoration seeds don't depend on which other
 * thread happens to be decorating at the same time.
 *
 * <p><b>What BCLib does.</b> BCLib's {@code ChunkGeneratorMixin} rewrites every
 * {@code WorldgenRandom.setFeatureSeed} seed inside {@code ChunkGenerator.applyBiomeDecoration} to
 * {@code Long.rotateRight(seed, counter++)}. The counter resets to 0 at the start of each call, but it is
 * one {@code int} field per generator <i>instance</i>, shared by every thread decorating with that generator.
 *
 * <p><b>Why that breaks here.</b> Vanilla decorates one chunk at a time per level (the {@code "worldgen"}
 * mailbox), so live generation never overlaps. DT's offline samplers ({@link EndBandSampler},
 * {@link ForeignSphereSampler}, the dimensional-carriage {@code PortalChunkTerrain}) decorate with the
 * dimension's <i>live</i> generator from their own thread pools. Two samplers at once reset and bump the
 * same counter, so a feature gets another call's rotation, and the End band and foreign spheres come out
 * differently depending on thread timing (measured 2026-09-25: up to 64 corrupted calls per run).
 *
 * <p><b>The fix.</b> {@code BclibFeatureSeedCounterMixin} replaces BCLib's rotated seed with the same
 * formula driven by this per-thread state. Where no race happened the result is bit-identical to BCLib's.
 * Pure (no Minecraft types) so it is unit-testable.
 */
public final class FeatureSeedCounter {

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private FeatureSeedCounter() {}

    /** Start of an {@code applyBiomeDecoration} call on this thread: counter back to 0. */
    public static void begin() {
        State s = STATE.get();
        s.seed = 0L;
        s.counter = 0;
    }

    /** The call's decoration seed, before any feature rotation (what BCLib rotates). */
    public static void captureSeed(long decorationSeed) {
        STATE.get().seed = decorationSeed;
    }

    /** The feature seed BCLib would pass on a single thread: {@code rotateRight(seed, counter++)}. */
    public static long next() {
        State s = STATE.get();
        return Long.rotateRight(s.seed, s.counter++);
    }

    private static final class State {
        long seed;
        int counter;
    }
}
