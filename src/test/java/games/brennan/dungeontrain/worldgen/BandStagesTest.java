package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BandStagesTest {

    /** riseLen 232 = beach 32 + 5 stages × 40, so the Nether splits cleanly. */
    private static final CycleLayout.Fades FADES =
        new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);
    /** The shipped spheres progression: End sky 1000, Nether 1750, End 2500, boost 3250–6250 (core 6550). */
    private static final SpheresSegments SPHERES =
        SpheresSegments.of(1000, 1750, 2500, 3250, 6250, 0.1, 5, 20, 1, 1, 1);

    private static CycleLayout defaultLayout() {
        List<LegacySpan> eras = new ArrayList<>();
        for (LegacyBandKind k : LegacyBandKind.values()) eras.add(new LegacySpan(k, 3000, 480, 6000));
        return CycleLayout.parse(CycleLayout.DEFAULT_ORDER, FADES, eras.toArray(new LegacySpan[0]),
            t -> true, w -> {});
    }

    private static List<BandStages.Stage> stagesOf(CycleLayout layout, int i) {
        return BandStages.of(layout, i, 5, 40, 32, SPHERES, 550, 100);
    }

    @Test
    void everyDefaultSlotsStagesCoverTheWholeSlot() {
        CycleLayout layout = defaultLayout();
        for (int i = 0; i < layout.count(); i++) {
            long sum = stagesOf(layout, i).stream().mapToLong(BandStages.Stage::length).sum();
            assertEquals(layout.length(i), sum, "slot " + i + " " + layout.slot(i));
        }
    }

    @Test
    void spheresHasTransitionThenProgressionThenThinningAndVoid() {
        CycleLayout layout = defaultLayout();
        List<BandStages.Stage> s = stagesOf(layout, layout.firstIndexOf(CycleLayout.Type.SPHERES));
        assertEquals(List.of("Transition in", "Overworld sky", "End sky", "Nether spheres join",
                "End spheres join", "Structure boost", "Thinning out", "Closing void"),
            s.stream().map(BandStages.Stage::name).toList());
        assertEquals(List.of(1500L, 1000L, 750L, 750L, 750L, 2650L, 550L, 100L),
            s.stream().map(BandStages.Stage::length).toList());
    }

    @Test
    void spheresWithoutExitStretchEndsWithBoostOver() {
        CycleLayout layout = defaultLayout();
        List<String> names = BandStages.of(layout, layout.firstIndexOf(CycleLayout.Type.SPHERES), 5, 40, 32, SPHERES)
            .stream().map(BandStages.Stage::name).toList();
        assertEquals("Boost over", names.get(names.size() - 1));
    }

    @Test
    void netherIsMirrored() {
        CycleLayout layout = defaultLayout();
        List<String> names = stagesOf(layout, layout.firstIndexOf(CycleLayout.Type.NETHER))
            .stream().map(BandStages.Stage::name).toList();
        assertEquals("Beach", names.get(0));
        assertEquals("Beach", names.get(names.size() - 1));
        assertTrue(names.contains("Core"));
        assertEquals(15, names.size());
    }

    @Test
    void locateReportsIndexAndFraction() {
        List<BandStages.Stage> s = List.of(new BandStages.Stage("A", 100), new BandStages.Stage("B", 200));
        BandStages.Position p = BandStages.locate(s, 150);
        assertNotNull(p);
        assertEquals("2/2 B (25%)", p.describe());
        assertEquals("1/2 A (0%)", BandStages.locate(s, 0).describe());
        assertNull(BandStages.locate(s, -1));
        assertNull(BandStages.locate(List.of(), 5));
    }

    @Test
    void prettyNamesEraTokens() {
        assertEquals("Far Lands", BandStages.pretty("far_lands"));
        assertEquals("Caves Of Chaos", BandStages.pretty("caves_of_chaos"));
    }
}
