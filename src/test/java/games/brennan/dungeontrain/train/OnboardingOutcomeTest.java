package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.difficulty.DifficultyProgression.OnboardingStage;
import games.brennan.dungeontrain.train.CarriageContentsPlacer.OnboardingOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The gentle-onboarding gate's decision table, without a level.
 *
 * <p>The case that motivated it: a Test-the-Carriage room is stamped with the author's zero carriages
 * travelled, which the ramp reads as the opening stretch of a run — so every hostile spawn-egg cell
 * in the build rolled, cleared its block to air and then placed nothing. {@code asAuthored} is the
 * stamp's way of saying "show me the room as built", and it has to win over every stage.</p>
 */
class OnboardingOutcomeTest {

    private static OnboardingOutcome decide(OnboardingStage stage, boolean hostile,
                                            boolean sentinel, boolean asAuthored) {
        return CarriageContentsPlacer.onboardingOutcome(stage, hostile, sentinel, asAuthored);
    }

    @Test
    @DisplayName("a hostile in play follows the stage: withheld, then a slime, then as authored")
    void hostileInPlayFollowsTheStage() {
        assertEquals(OnboardingOutcome.SUPPRESSED, decide(OnboardingStage.NO_HOSTILES, true, false, false));
        assertEquals(OnboardingOutcome.SUBSTITUTED, decide(OnboardingStage.EASY_MOBS, true, false, false));
        assertEquals(OnboardingOutcome.AS_AUTHORED, decide(OnboardingStage.NORMAL, true, false, false));
    }

    @ParameterizedTest
    @EnumSource(OnboardingStage.class)
    @DisplayName("a test carriage spawns its hostiles as authored in every stage")
    void testCarriageSpawnsAsAuthored(OnboardingStage stage) {
        assertEquals(OnboardingOutcome.AS_AUTHORED, decide(stage, true, false, true));
    }

    @ParameterizedTest
    @EnumSource(OnboardingStage.class)
    @DisplayName("an editor preview spawns its hostiles as authored in every stage")
    void editorPreviewSpawnsAsAuthored(OnboardingStage stage) {
        assertEquals(OnboardingOutcome.AS_AUTHORED, decide(stage, true, true, false));
    }

    @ParameterizedTest
    @EnumSource(OnboardingStage.class)
    @DisplayName("a passive mob is never gated, whatever the stage or the stamp")
    void passiveMobIsNeverGated(OnboardingStage stage) {
        assertEquals(OnboardingOutcome.AS_AUTHORED, decide(stage, false, false, false));
        assertEquals(OnboardingOutcome.AS_AUTHORED, decide(stage, false, false, true));
        assertEquals(OnboardingOutcome.AS_AUTHORED, decide(stage, false, true, false));
    }
}
