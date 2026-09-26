package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.CycleLayout.Style;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Theme laps end to end on the shipped layout: {@code :t1} / {@code :t2} groups, the per-world
 * {@link LapThemePlan} deciding once and in order, and {@link WorldGenCycle} resolving each slot's style
 * from its lap's theme.
 */
final class LapThemesTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    private static int x(long u, int k) {
        return (int) (START + CycleLayout.runStart(k, P) + (u << k));
    }

    @AfterEach
    void clear() {
        LapThemes.clear();
    }

    /** A plan whose laps are already decided as {@code themes} (lap 0, 1, 2 …). */
    private static LapThemePlan fixed(LapTheme... themes) {
        Map<Long, LapTheme> decided = new java.util.HashMap<>();
        for (int i = 0; i < themes.length; i++) decided.put((long) i, themes[i]);
        return new LapThemePlan(1L, decided, Map::of, (n, t, p) -> {});
    }

    @Test
    @DisplayName("the shipped order has two theme groups: Lap 1 (t1) and Lap 2 (t2), four slots each")
    void groups() {
        assertEquals(2, LAYOUT.themeGroupCount());
        assertEquals(LapThemePicker.Kind.LAP1, LAYOUT.themeGroupKind(0));
        assertEquals(LapThemePicker.Kind.LAP2, LAYOUT.themeGroupKind(1));
        assertEquals(0L, LAYOUT.themeGroupStart(0));
        assertEquals(LAYOUT.start(3) + LAYOUT.length(3), LAYOUT.themeGroupEnd(0));   // ends with the Lap 1 End band
        assertEquals(LAYOUT.start(5), LAYOUT.themeGroupStart(1));
        assertEquals(LAYOUT.start(8) + LAYOUT.length(8), LAYOUT.themeGroupEnd(1));   // ends with the Lap 2 End band
        for (int i : new int[] {0, 1, 2, 3}) assertEquals(0, LAYOUT.slot(i).themeGroup());
        for (int i : new int[] {5, 6, 7, 8}) assertEquals(1, LAYOUT.slot(i).themeGroup());
        assertEquals(-1, LAYOUT.slot(4).themeGroup());                                // upside-down is not themed
    }

    @Test
    @DisplayName("with no plan published, laps look as they did before themes: Lap 1 vanilla, Lap 2 Better")
    void fallback() {
        assertEquals(Style.VANILLA, C.netherStyleAt(x(LAYOUT.start(1) + 1000, 0)));
        assertEquals(Style.BETTER, C.netherStyleAt(x(LAYOUT.start(6) + 1000, 0)));
        assertEquals(Style.WWOO, C.overworldStyleAt(x(LAYOUT.start(5) + 10, 0)));
        assertEquals(Style.WWOO, C.overworldStyleAt(x(LAYOUT.start(7) + 10, 0)));
    }

    @Test
    @DisplayName("a BoP lap is BoP on both overworld stretches, the Nether and the End")
    void bopLap() {
        LapThemes.publish(fixed(LapTheme.VANILLA, LapTheme.BOP, LapTheme.BETTER, LapTheme.BOP));
        assertEquals(Style.BOP, C.overworldStyleAt(x(LAYOUT.start(5) + 10, 0)));
        assertEquals(Style.BOP, C.netherStyleAt(x(LAYOUT.start(6) + 1000, 0)));
        assertEquals(Style.BOP, C.overworldStyleAt(x(LAYOUT.start(7) + 10, 0)));
        assertEquals(Style.BOP, C.endStyleAt(x(LAYOUT.start(8) + 1000, 0)));
        assertEquals(SecondLapOverworld.Stretch.BOP, SecondLapOverworld.at(C, x(LAYOUT.start(5) + 10, 0)));
        assertTrue(C.isBopNetherAt(x(LAYOUT.start(6) + 1000, 0)));
        assertTrue(C.isBopEndAt(x(LAYOUT.start(8) + 1000, 0)));
        // run 1: Lap 1 is lap 2 (BETTER) — WWOO + BetterNether + BetterEnd; Lap 2 is lap 3 (BOP)
        assertEquals(Style.WWOO, C.overworldStyleAt(x(LAYOUT.start(0) + 10, 1)));
        assertTrue(C.isBetterNetherAt(x(LAYOUT.start(1) + 1000, 1)));
        assertTrue(C.isBetterEndAt(x(LAYOUT.start(3) + 1000, 1)));
        assertEquals(Style.BOP, C.netherStyleAt(x(LAYOUT.start(6) + 1000, 1)));
        // passes: Nether pass 1 = run 0 Lap 2, pass 2 = run 1 Lap 1
        assertEquals(Style.BOP, C.netherStyleOfPass(1));
        assertEquals(Style.BETTER, C.netherStyleOfPass(2));
        assertEquals(Style.VANILLA, C.endStyleOfPass(0));
    }

    @Test
    @DisplayName("the modded look bleeds into band transitions from the themed gap it borders")
    void bleed() {
        LapThemes.publish(fixed(LapTheme.VANILLA, LapTheme.BOP));
        long nether2 = LAYOUT.start(6);
        assertEquals(Style.BOP, C.bleedingOverworldStyleAt(x(nether2 + 10, 0)));
        assertEquals(Style.BOP, C.bleedingOverworldStyleAt(x(nether2 + LAYOUT.length(6) - 10, 0)));
    }

    @Test
    @DisplayName("theme lap index and progress: fraction of the lap's own span, the same in stretched runs")
    void indexAndProgress() {
        assertEquals(0L, C.themeLapIndexAt(x(0, 0)));
        assertEquals(-1L, C.themeLapIndexAt(x(LAYOUT.start(4) + 10, 0)));            // upside-down
        assertEquals(1L, C.themeLapIndexAt(x(LAYOUT.start(5), 0)));
        assertEquals(3L, C.themeLapIndexAt(x(LAYOUT.start(5), 1)));
        assertEquals(-1L, C.themeLapIndexAt((int) START - 5));
        long g1 = LAYOUT.themeGroupStart(1);
        long len = LAYOUT.themeGroupEnd(1) - g1;
        assertEquals(0.5, C.themeLapProgressAt(x(g1 + len / 2, 0)), 1e-4);
        assertEquals(0.5, C.themeLapProgressAt(x(g1 + len / 2, 1)), 1e-4);
        assertArrayEquals(new long[] {x(g1, 1), x(LAYOUT.themeGroupEnd(1), 1)}, C.themeLapRange(3));
    }

    @Test
    @DisplayName("the plan decides each lap once, in order, from one progress snapshot, and never changes it")
    void planDecidesOnce() {
        AtomicInteger snapshots = new AtomicInteger();
        List<Long> decidedOrder = new ArrayList<>();
        LapThemePlan plan = new LapThemePlan(99L, Map.of(),
                () -> { snapshots.incrementAndGet(); return Map.of(LapTheme.BETTER, 1.0); },
                (n, t, p) -> decidedOrder.add(n));
        List<LapThemePicker.Kind> kinds = LAYOUT.themeKinds();
        assertNull(plan.peek(3));
        LapTheme lap3 = plan.resolve(3, kinds);
        assertEquals(List.of(0L, 1L, 2L, 3L), decidedOrder);
        assertEquals(1, snapshots.get());
        assertEquals(LapTheme.VANILLA, plan.peek(0));
        assertEquals(LapTheme.BOP, plan.peek(1));      // Better is 100%, BoP 0% → BoP
        assertEquals(lap3, plan.resolve(3, kinds));
        assertEquals(4, decidedOrder.size());
        for (long n = 1; n <= 3; n++) {
            assertTrue(plan.peek(n) != plan.peek(n - 1), "lap " + n + " repeats lap " + (n - 1));
        }
    }
}
