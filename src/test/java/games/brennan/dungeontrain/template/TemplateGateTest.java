package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests for the per-template spawn gate (min/max Diff-Level band + phase set). */
final class TemplateGateTest {

    @Test
    @DisplayName("DEFAULT is eligible at every level and phase")
    void defaultEligibleEverywhere() {
        TemplateGate g = TemplateGate.DEFAULT;
        assertTrue(g.isDefault());
        assertTrue(g.eligible(0, TrainPhase.OVERWORLD));
        assertTrue(g.eligible(999, TrainPhase.END));
        assertTrue(g.eligible(7, TrainPhase.NETHER));
    }

    @Test
    @DisplayName("min/max level band gates the level, ALL is unbounded above")
    void levelBand() {
        TemplateGate g = TemplateGate.ofLevels(3, 10);
        assertFalse(g.levelEligible(2));
        assertTrue(g.levelEligible(3));
        assertTrue(g.levelEligible(10));
        assertFalse(g.levelEligible(11));

        TemplateGate open = TemplateGate.ofLevels(5, TemplateGate.ALL);
        assertFalse(open.levelEligible(4));
        assertTrue(open.levelEligible(5));
        assertTrue(open.levelEligible(10_000));
        assertFalse(open.isDefault(), "a non-zero min is not the default");
    }

    @Test
    @DisplayName("constructor clamps and enforces min<=max")
    void clamps() {
        assertEquals(0, new TemplateGate(-5, 10, null).minLevel());
        assertEquals(TemplateGate.MAX_LEVEL, new TemplateGate(200, TemplateGate.ALL, null).minLevel());
        // min > finite max collapses min down to max (mirrors VariantDifficulty)
        TemplateGate g = new TemplateGate(20, 10, null);
        assertEquals(10, g.minLevel());
        assertEquals(10, g.maxLevel());
    }

    @Test
    @DisplayName("phase set gates the phase; empty/null normalises to all phases")
    void phaseGate() {
        TemplateGate nether = new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER));
        assertTrue(nether.eligible(0, TrainPhase.NETHER));
        assertFalse(nether.eligible(0, TrainPhase.OVERWORLD));
        assertFalse(nether.isDefault());

        // null phases == all phases (back-compat default)
        assertEquals(TemplateGate.ALL_PHASES, new TemplateGate(0, TemplateGate.ALL, null).phases());
        // empty phases also normalises to all (can't gate to "no phase")
        assertEquals(TemplateGate.ALL_PHASES,
            new TemplateGate(0, TemplateGate.ALL, EnumSet.noneOf(TrainPhase.class)).phases());
    }

    @Test
    @DisplayName("incMaxLevel cycles ALL→0→…→MAX→ALL; decMaxLevel reverses")
    void maxLevelCycle() {
        // inc: ALL → 0, mid-range steps up, MAX wraps back to ALL
        assertEquals(0, TemplateGate.DEFAULT.incMaxLevel().maxLevel());
        assertEquals(6, TemplateGate.ofLevels(0, 5).incMaxLevel().maxLevel());
        assertEquals(TemplateGate.ALL, TemplateGate.ofLevels(0, TemplateGate.MAX_LEVEL).incMaxLevel().maxLevel());
        // dec: ALL → MAX, mid-range steps down, 0 wraps back to ALL
        assertEquals(TemplateGate.MAX_LEVEL, TemplateGate.DEFAULT.decMaxLevel().maxLevel());
        assertEquals(4, TemplateGate.ofLevels(0, 5).decMaxLevel().maxLevel());
        assertEquals(TemplateGate.ALL, TemplateGate.ofLevels(0, 0).decMaxLevel().maxLevel());
    }

    @Test
    @DisplayName("withPhase toggles; removing the last phase normalises back to all")
    void withPhase() {
        TemplateGate g = TemplateGate.DEFAULT.withPhase(TrainPhase.OVERWORLD, false);
        assertFalse(g.phases().contains(TrainPhase.OVERWORLD));
        assertTrue(g.phases().contains(TrainPhase.NETHER));

        // turn every phase off one by one — final removal normalises to all phases
        TemplateGate only = new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.END));
        TemplateGate cleared = only.withPhase(TrainPhase.END, false);
        assertEquals(TemplateGate.ALL_PHASES, cleared.phases());
        assertTrue(cleared.isDefault());
    }

    @Test
    @DisplayName("toggleOtherPhases flips all but the kept dimension; solos from all-on, restores from solo, keeps the level band")
    void toggleOtherPhasesBehaviour() {
        // all-on → solo the kept one
        assertEquals(EnumSet.of(TrainPhase.NETHER),
            TemplateGate.DEFAULT.toggleOtherPhases(TrainPhase.NETHER).phases());
        // solo → restore all (a second shift-click on the same letter)
        TemplateGate solo = new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER));
        assertEquals(TemplateGate.ALL_PHASES, solo.toggleOtherPhases(TrainPhase.NETHER).phases());
        // mixed: the kept dimension stays, every other flips
        TemplateGate mixed = new TemplateGate(0, TemplateGate.ALL,
            EnumSet.of(TrainPhase.OVERWORLD, TrainPhase.NETHER));
        assertEquals(EnumSet.of(TrainPhase.NETHER, TrainPhase.VOID, TrainPhase.END),
            mixed.toggleOtherPhases(TrainPhase.NETHER).phases());
        // the Diff-Level band is untouched
        TemplateGate banded = new TemplateGate(3, 9, EnumSet.of(TrainPhase.OVERWORLD));
        TemplateGate after = banded.toggleOtherPhases(TrainPhase.OVERWORLD);
        assertEquals(3, after.minLevel());
        assertEquals(9, after.maxLevel());
        assertEquals(TemplateGate.ALL_PHASES, after.phases(), "OVERWORLD kept + others flipped on = all");
    }
}
