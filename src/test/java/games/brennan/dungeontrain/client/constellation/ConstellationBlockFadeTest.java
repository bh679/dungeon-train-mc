package games.brennan.dungeontrain.client.constellation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConstellationBlockFadeTest {

    private static final ConstellationOcclusion.Hidden A = new ConstellationOcclusion.Hidden(null, 1L);
    private static final ConstellationOcclusion.Hidden B = new ConstellationOcclusion.Hidden(null, 2L);

    @Test
    void hidesOverHalfASecond() {
        Map<ConstellationOcclusion.Hidden, Float> m = ConstellationBlockFade.step(Map.of(), List.of(A), 0.25f);
        assertEquals(0.5f, m.get(A), 1e-5f);
        m = ConstellationBlockFade.step(m, List.of(A), 0.25f);
        assertEquals(1f, m.get(A), 1e-5f);
        m = ConstellationBlockFade.step(m, List.of(A), 1f);
        assertEquals(1f, m.get(A), 1e-5f, "stays whole, never past");
    }

    @Test
    void showsAgainOverHalfASecondThenIsForgotten() {
        Map<ConstellationOcclusion.Hidden, Float> m = Map.of(A, 1f);
        m = ConstellationBlockFade.step(m, List.of(B), 0.1f);
        assertEquals(0.8f, m.get(A), 1e-5f);
        assertEquals(0.2f, m.get(B), 1e-5f);
        m = ConstellationBlockFade.step(m, List.of(B), 0.4f);
        assertFalse(m.containsKey(A));
    }

    @Test
    void aBlockTurningBackMidFadeCarriesOnFromWhereItIs() {
        Map<ConstellationOcclusion.Hidden, Float> m = Map.of(A, 0.4f);
        m = ConstellationBlockFade.step(m, List.of(A), 0.1f);
        assertEquals(0.6f, m.get(A), 1e-5f);
    }

    @Test
    void fadeStepsGroupByStrengthAndDropZero() {
        Map<Float, List<ConstellationOcclusion.Hidden>> steps =
            ConstellationSky.fadeSteps(Map.of(A, 1f, B, 0.5f, new ConstellationOcclusion.Hidden(null, 3L), 0.01f));
        assertEquals(2, steps.size());
        assertEquals(List.of(A), steps.get(1f));
        assertEquals(List.of(B), steps.get(0.5f));
    }
}
