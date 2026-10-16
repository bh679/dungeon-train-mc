package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.function.Supplier;

/**
 * Where a test pretends to be: the stage, Diff-Level and band an author picked on the editor's
 * test settings rows, any of which may be left on Auto.
 *
 * <p>A test copy stands at {@code CarriageTestSession.TEST_INDEX} in the basement, which is no
 * place on the line — so on its own it is always gated as the start of a run in the Overworld. These
 * settings let the author see their template where it would really turn up, without touching the
 * template itself.</p>
 *
 * <p>Applied as a thread scope, like {@link games.brennan.dungeontrain.train.StagePlacementScope}:
 * only the test commands open one, around their stamps, so the train never sees an override.
 * {@link DifficultyProgression#positionTier} and {@link GateContext}'s factories read it.</p>
 *
 * @param stageId the stage to paint with, or {@code null} for Auto
 * @param level   the Diff-Level to gate at, or {@link #AUTO_LEVEL} for Auto
 * @param phase   the band to gate in, or {@code null} for Auto
 */
public record TestGate(String stageId, int level, TrainPhase phase) {

    public static final int AUTO_LEVEL = -1;
    /** Every setting on Auto: the test stands up exactly as it did before these settings existed. */
    public static final TestGate AUTO = new TestGate(null, AUTO_LEVEL, null);

    public TestGate {
        if (stageId != null && stageId.isBlank()) stageId = null;
        if (level < 0) level = AUTO_LEVEL;
    }

    public boolean levelSet() { return level != AUTO_LEVEL; }
    public boolean phaseSet() { return phase != null; }
    public boolean isAuto() { return stageId == null && !levelSet() && !phaseSet(); }

    public TestGate withStage(String id) { return new TestGate(id, level, phase); }
    public TestGate withLevel(int l) { return new TestGate(stageId, l, phase); }
    public TestGate withPhase(TrainPhase p) { return new TestGate(stageId, level, p); }

    /**
     * The stage a test stamp paints with: the picked one; else, once the level or band has been
     * moved, the stage play would choose there; else {@code fallback} — what the test used before.
     */
    public String stageOr(String fallback) {
        if (stageId != null) return stageId;
        if (!levelSet() && !phaseSet()) return fallback;
        return StageResolver.stageIdFor(new GateContext(
            levelSet() ? level : DifficultyProgression.positionTier(0),
            phaseSet() ? phase : TrainPhase.OVERWORLD));
    }

    // ------------------------------------------------------------------
    // Thread scope
    // ------------------------------------------------------------------

    private static final ThreadLocal<TestGate> CURRENT = new ThreadLocal<>();

    /** The settings in scope on this thread, or {@code null} outside a test stamp. */
    public static TestGate current() {
        return CURRENT.get();
    }

    /** Run {@code body} with {@code gate} in scope; nested scopes restore the outer one after. */
    public static <T> T with(TestGate gate, Supplier<T> body) {
        TestGate prev = CURRENT.get();
        CURRENT.set(gate == null ? AUTO : gate);
        try {
            return body.get();
        } finally {
            if (prev == null) CURRENT.remove(); else CURRENT.set(prev);
        }
    }

    public static void run(TestGate gate, Runnable body) {
        with(gate, () -> { body.run(); return null; });
    }

    /** The in-scope Diff-Level override, or {@code computed} when there is none. */
    public static int levelOr(int computed) {
        TestGate g = CURRENT.get();
        return g != null && g.levelSet() ? g.level : computed;
    }

    /** The in-scope band override, or {@code computed} when there is none. */
    public static TrainPhase phaseOr(TrainPhase computed) {
        TestGate g = CURRENT.get();
        return g != null && g.phaseSet() ? g.phase : computed;
    }

    /** The stage a test stamp paints with, given what it would have used: see {@link #stageOr}. */
    public static String stageInScopeOr(String fallback) {
        TestGate g = CURRENT.get();
        return g == null ? fallback : g.stageOr(fallback);
    }
}
