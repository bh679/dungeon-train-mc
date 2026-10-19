package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.TransitionLandmarks.Landmark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The transition landmarks against the shipped order, on the {@link CycleLayoutTest} fixture. */
final class TransitionLandmarksTest {

    /** Shipped spheres progression: boost 3250 → 6250, so the peak sits at core offset 4750. */
    static final SpheresSegments SPHERES = SpheresSegments.of(1000, 1750, 2500, 3250, 6250, 0.08, 5.0, 20.0, 1, 1, 1);

    /** Shipped Nether rise: a 32-block beach and five 40-block mountain stages (riseLen 232). */
    static List<Landmark> run(int k) {
        return TransitionLandmarks.forRun(CycleLayoutTest.shipped(), 0L, k, 5, 40, 32, SPHERES);
    }

    static Map<String, Landmark> byName(List<Landmark> landmarks) {
        return landmarks.stream().collect(Collectors.toMap(Landmark::name, l -> l));
    }

    @Test
    @DisplayName("run 0 lands every transition on the shipped geometry")
    void shippedRunZero() {
        Map<String, Landmark> lm = byName(run(0));
        // The fixture gives every era a 480 fade (Lost City's real one is 750), so from the Lost City slot
        // on these sit 270 blocks earlier than a live world's.
        assertEquals(2_782L, lm.get("nether_rise").worldX());
        assertEquals(3_282L, lm.get("nether_core_0").worldX());
        assertEquals(26_226L, lm.get("nether_core_1").worldX());
        assertEquals(9_814L, lm.get("end_void_0").worldX());
        assertEquals(10_554L, lm.get("end_islands_0").worldX());
        assertEquals(15_094L, lm.get("upside_down").worldX());
        assertEquals(19_094L, lm.get("reassembly_mid").worldX());
        assertEquals(35_488L - 270L, lm.get("end_void_1").worldX());
        assertEquals(36_228L - 270L, lm.get("end_islands_1").worldX());
        assertEquals(47_468L - 270L, lm.get("spheres_peak").worldX());
        assertEquals(49_268L - 270L, lm.get("amplified").worldX());     // the ow:sunk approach
        assertEquals(18, lm.size());
    }

    @Test
    @DisplayName("the legacy-run landmarks sit on their eras' cores")
    void legacyLandmarks() {
        CycleLayout l = CycleLayoutTest.shipped();
        Map<String, Landmark> lm = byName(run(0));
        long far = TransitionLandmarks.eraCoreStart(l, games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind.FAR_LANDS);
        assertEquals(far, lm.get("far_lands").worldX());
        assertEquals(far + 2160L, lm.get("far_lands_mid").worldX());
        assertEquals(far + 4320L, lm.get("far_lands_exit").worldX());
        assertEquals(TransitionLandmarks.FAR_LANDS_APPROACH_COUNT, lm.get("far_lands").count());
        assertEquals(TransitionLandmarks.eraCoreStart(l, games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind.SUPERFLAT),
                lm.get("superflat").worldX());
        long infdevCore = TransitionLandmarks.eraCoreStart(l, games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind.INFDEV);
        // 60% of [480 fade][2000 core][480 fade] = 1776 from the fade's start
        assertEquals(infdevCore - 480L + 1776L, lm.get("pyramid").worldX());
        assertEquals(l.start(l.firstIndexOf(CycleLayout.Type.CHUNCKS)), lm.get("chuncks").worldX());
        assertEquals(l.start(l.firstIndexOf(CycleLayout.Type.STACKS)), lm.get("stacks").worldX());
    }

    @Test
    @DisplayName("run 1 doubles every offset from the run start and ids carry the run")
    void doubling() {
        long period = CycleLayoutTest.shipped().period();
        Map<String, Landmark> r1 = byName(run(1));
        assertEquals(period + 2L * 3_282L, r1.get("nether_core_0").worldX());
        assertEquals("nether_core_0@r1", r1.get("nether_core_0").id());
        List<Landmark> sorted = run(1);
        for (int i = 1; i < sorted.size(); i++) assertTrue(sorted.get(i - 1).worldX() <= sorted.get(i).worldX());
    }

    @Test
    @DisplayName("a band missing from the order has no landmark")
    void disabledBand() {
        List<String> warnings = new ArrayList<>();
        CycleLayout noUd = CycleLayout.parse(CycleLayout.DEFAULT_ORDER, CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(),
                t -> t != CycleLayout.Type.UPSIDE_DOWN && t != CycleLayout.Type.STACKS, warnings::add);
        Map<String, Landmark> lm = byName(TransitionLandmarks.forRun(noUd, 0L, 0, 5, 40, 32, SPHERES));
        assertFalse(lm.containsKey("upside_down"));
        assertFalse(lm.containsKey("reassembly_mid"));
        assertFalse(lm.containsKey("stacks"));
        assertTrue(lm.containsKey("nether_rise"));
    }
}
