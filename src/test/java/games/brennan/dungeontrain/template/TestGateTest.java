package games.brennan.dungeontrain.template;

import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The test settings only ever reach a stamp inside a test scope, and Auto changes nothing. */
final class TestGateTest {

    @Test
    void outsideAScopeNothingIsOverridden() {
        assertNull(TestGate.current());
        assertEquals(7, TestGate.levelOr(7));
        assertEquals(TrainPhase.END, TestGate.phaseOr(TrainPhase.END));
        assertEquals("fallback", TestGate.stageInScopeOr("fallback"));
    }

    @Test
    void autoInScopeChangesNothing() {
        TestGate.run(TestGate.AUTO, () -> {
            assertEquals(7, TestGate.levelOr(7));
            assertEquals(TrainPhase.END, TestGate.phaseOr(TrainPhase.END));
            assertEquals("fallback", TestGate.stageInScopeOr("fallback"));
        });
    }

    @Test
    void pickedSettingsWinInsideTheScopeOnly() {
        TestGate gate = TestGate.AUTO.withLevel(120).withPhase(TrainPhase.NETHER).withStage("cave");
        TestGate.run(gate, () -> {
            assertEquals(120, TestGate.levelOr(7));
            assertEquals(TrainPhase.NETHER, TestGate.phaseOr(TrainPhase.OVERWORLD));
            assertEquals("cave", TestGate.stageInScopeOr("fallback"));
        });
        assertNull(TestGate.current());
    }

    @Test
    void nestedScopesRestoreTheOuterOne() {
        TestGate outer = TestGate.AUTO.withLevel(5);
        TestGate.run(outer, () -> {
            TestGate.run(TestGate.AUTO.withLevel(9), () -> assertEquals(9, TestGate.levelOr(0)));
            assertEquals(5, TestGate.levelOr(0));
        });
    }

    @Test
    void blankStageAndNegativeLevelAreAuto() {
        TestGate gate = new TestGate(" ", -4, null);
        assertTrue(gate.isAuto());
        assertEquals(TestGate.AUTO, gate);
    }
}
