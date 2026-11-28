package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CarriageContentsPlacer#EDITOR_SENTINEL_PIDX} marks "editor preview, not a train carriage".
 * It used to be {@code -1}, the real carriage directly behind spawn, so that carriage rolled its
 * contents ungated (an {@code evoker2} room, gated to Level 30, at Level 0) and skipped onboarding.
 * These tests pin that no real carriage index can ever be the sentinel again.
 */
final class EditorSentinelPidxTest {

    @BeforeEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
        CarriageContentsGroupStore.clearCache();
    }

    @AfterEach
    void restoreEmpty() {
        CarriageContentsRegistry.clear();
        CarriageContentsGroupStore.clearCache();
    }

    @Test
    @DisplayName("sentinel lies outside every index a real carriage can have")
    void sentinelIsNotAReachableCarriageIndex() {
        int max = DifficultyProgression.MAX_PLAUSIBLE_POSITION_INDEX;
        int s = CarriageContentsPlacer.EDITOR_SENTINEL_PIDX;
        assertTrue(s < -max || s > max, "sentinel " + s + " is inside the real carriage range");
        // The carriages either side of spawn, on every starting train.
        for (int pIdx = -6; pIdx <= 6; pIdx++) assertNotEquals(s, pIdx);
    }

    @Test
    @DisplayName("onboarding still suppresses a baked hostile in the carriage behind spawn")
    void onboardingAppliesBehindSpawn() {
        int behindSpawn = -1;
        CarriageContentsPlacer.OnboardingOutcome outcome = CarriageContentsPlacer.onboardingOutcome(
            DifficultyProgression.OnboardingStage.NO_HOSTILES, true,
            behindSpawn == CarriageContentsPlacer.EDITOR_SENTINEL_PIDX, false);
        assertEquals(CarriageContentsPlacer.OnboardingOutcome.SUPPRESSED, outcome);
    }

    @Test
    @DisplayName("carriage -1 at Level 0 never draws a group member gated to Level 30")
    void levelZeroGroupNeverDrawsHighLevelMember() {
        for (String id : new String[]{"enemies_pillagers", "pillager", "evoker2"}) {
            CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom(id));
        }
        CarriageContentsGroupStore.injectForTesting("enemies_pillagers", new CarriageContentsGroup(0, List.of(
            new CarriageContentsGroup.Member("pillager", 4, TemplateGate.ofLevels(2, TemplateGate.ALL), List.of()),
            new CarriageContentsGroup.Member("evoker2", 3, TemplateGate.ofLevels(30, TemplateGate.ALL), List.of()))));

        // What CarriagePlacer.applyContents now builds for pIdx -1 (it used to pass null).
        GateContext levelZero = new GateContext(0, TrainPhase.OVERWORLD);
        for (long seed = 0; seed < 500; seed++) {
            CarriageContents drawn = CarriageContentsRegistry.pick(seed, -1, CarriageContentsAllowList.EMPTY, levelZero);
            assertNotEquals("evoker2", drawn.id(), "seed " + seed);
            assertNotEquals("pillager", drawn.id(), "seed " + seed);
        }
    }
}
