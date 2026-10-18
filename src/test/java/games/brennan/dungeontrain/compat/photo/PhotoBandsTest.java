package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.worldgen.BandStages;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which transitions a band photo may credit, and how far it looks for the band. */
final class PhotoBandsTest {

    /** The legacy run's tail: Superflat, its crossfade, the closing void, and the fade back out. */
    private static final List<BandStages.Stage> LEGACY_TAIL = List.of(
            new BandStages.Stage("Superflat", 1000),
            new BandStages.Stage("Superflat → Void", 480),
            new BandStages.Stage("Void", 200),
            new BandStages.Stage("Void → Overworld", 480));

    @Test
    @DisplayName("a transition looks only as far as the stages either side of it")
    void stageWindow() {
        assertArrayEquals(new long[] {0, 1680}, PhotoBands.stageWindow(LEGACY_TAIL, 1), "crossfade reaches Superflat");
        assertArrayEquals(new long[] {1480, 2160}, PhotoBands.stageWindow(LEGACY_TAIL, 3),
                "the fade out of the void can't reach back to Superflat");
        assertArrayEquals(new long[] {0, 1480}, PhotoBands.stageWindow(LEGACY_TAIL, 0), "clamped at the slot's start");
    }

    @Test
    @DisplayName("the Nether's run-in never counts")
    void netherExcluded() {
        assertEquals(Set.of("reached_lost_city"),
                PhotoBands.excludeNether(Set.of("reached_better_nether", "reached_lost_city", "reached_nether")));
    }

    @Test
    @DisplayName("overworld gaps, the mix zone and the Nether hold no photo transitions")
    void slotTypes() {
        assertFalse(PhotoBands.hasTransitions(CycleLayout.Type.OVERWORLD));
        assertFalse(PhotoBands.hasTransitions(CycleLayout.Type.MIX));
        assertFalse(PhotoBands.hasTransitions(CycleLayout.Type.NETHER));
        for (CycleLayout.Type t : List.of(CycleLayout.Type.END, CycleLayout.Type.UPSIDE_DOWN, CycleLayout.Type.CHUNCKS,
                CycleLayout.Type.SPHERES, CycleLayout.Type.STACKS, CycleLayout.Type.LEGACY_RUN)) {
            assertTrue(PhotoBands.hasTransitions(t), t.name());
        }
    }
}
