package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The spec bands that choose a catch-up pacing for a machine.
 *
 * <p>Every case here drives the pure {@link CatchUpBurstAuto#resolve} directly, so the bands are
 * pinned without a JVM that happens to have the specs under test — which is the only way to cover a
 * band this build machine is not in.</p>
 *
 * <p>The thresholds themselves are a reasoned starting point rather than a measured one (see the
 * class javadoc). These tests exist to stop them moving by accident, not to claim they are the
 * right numbers.</p>
 */
class CatchUpBurstAutoTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    /** Comfortably above every threshold, for cases isolating one axis. */
    private static final int AMPLE_CORES = 16;
    private static final long AMPLE_HEAP = 16 * GIB;
    private static final long AMPLE_RAM = 32 * GIB;

    @Test
    @DisplayName("a capable machine gets FILL")
    void capableMachine_getsFill() {
        assertEquals(CatchUpBurstMode.FILL,
                CatchUpBurstAuto.resolve(AMPLE_CORES, AMPLE_HEAP, AMPLE_RAM));
    }

    @Test
    @DisplayName("a thin machine gets OFF — the pre-feature cadence")
    void thinMachine_getsOff() {
        assertEquals(CatchUpBurstMode.OFF,
                CatchUpBurstAuto.resolve(2, 2 * GIB, 4 * GIB));
    }

    @Test
    @DisplayName("the core bands land exactly on their boundaries")
    void coreBands_atTheBoundary() {
        assertEquals(CatchUpBurstMode.FILL,
                CatchUpBurstAuto.resolve(CatchUpBurstAuto.CORES_FOR_FILL, AMPLE_HEAP, AMPLE_RAM));
        assertEquals(CatchUpBurstMode.BURST_TWO,
                CatchUpBurstAuto.resolve(CatchUpBurstAuto.CORES_FOR_FILL - 1, AMPLE_HEAP, AMPLE_RAM));
        assertEquals(CatchUpBurstMode.BURST_TWO,
                CatchUpBurstAuto.resolve(CatchUpBurstAuto.CORES_FOR_BURST, AMPLE_HEAP, AMPLE_RAM));
        assertEquals(CatchUpBurstMode.OFF,
                CatchUpBurstAuto.resolve(CatchUpBurstAuto.CORES_FOR_BURST - 1, AMPLE_HEAP, AMPLE_RAM));
    }

    /**
     * The case that drove the band design. CurseForge and Modrinth commonly launch Minecraft with
     * {@code -Xmx4G}; gating FILL on a bigger heap than that would put the ordinary modpack player
     * on BURST_TWO, which is the pacing measured NOT to close a deficit. A 4 GB heap must not cost
     * a capable CPU its FILL.
     */
    @Test
    @DisplayName("the ordinary modpack player — 8 cores, -Xmx4G — still gets FILL")
    void typicalModpackAllocation_keepsFill() {
        assertEquals(CatchUpBurstMode.FILL,
                CatchUpBurstAuto.resolve(8, 4 * GIB, 16 * GIB));
    }

    @Test
    @DisplayName("the heap floors land exactly on their boundaries")
    void heapFloors_atTheBoundary() {
        assertEquals(CatchUpBurstMode.FILL,
                CatchUpBurstAuto.resolve(AMPLE_CORES, CatchUpBurstAuto.HEAP_FLOOR_BURST, AMPLE_RAM));
        assertEquals(CatchUpBurstMode.BURST_TWO,
                CatchUpBurstAuto.resolve(AMPLE_CORES, CatchUpBurstAuto.HEAP_FLOOR_BURST - 1, AMPLE_RAM),
                "a heap under the burst floor steps a capable CPU down one");
        assertEquals(CatchUpBurstMode.BURST_TWO,
                CatchUpBurstAuto.resolve(AMPLE_CORES, CatchUpBurstAuto.HEAP_FLOOR_OFF, AMPLE_RAM));
        assertEquals(CatchUpBurstMode.OFF,
                CatchUpBurstAuto.resolve(AMPLE_CORES, CatchUpBurstAuto.HEAP_FLOOR_OFF - 1, AMPLE_RAM),
                "a starved heap goes to OFF however many cores there are");
    }

    /** Heap and RAM may lower the pacing cores chose; they may never raise it. */
    @Test
    @DisplayName("a weak CPU is not rescued by a large heap")
    void largeHeap_doesNotRescueAWeakCpu() {
        assertEquals(CatchUpBurstMode.OFF,
                CatchUpBurstAuto.resolve(2, AMPLE_HEAP, AMPLE_RAM),
                "a large heap must not buy FILL for a CPU that cannot spawn inside the tick");
        assertEquals(CatchUpBurstMode.BURST_TWO,
                CatchUpBurstAuto.resolve(4, AMPLE_HEAP, AMPLE_RAM));
    }

    /**
     * A launcher will hand out a heap the machine cannot physically back; that heap buys swapping,
     * not tick headroom.
     */
    @Test
    @DisplayName("physical RAM below the bar caps an otherwise-FILL machine at BURST_TWO")
    void lowPhysicalRam_capsFill() {
        assertEquals(CatchUpBurstMode.BURST_TWO,
                CatchUpBurstAuto.resolve(AMPLE_CORES, AMPLE_HEAP, CatchUpBurstAuto.RAM_FOR_UNCAPPED - 1));
        assertEquals(CatchUpBurstMode.FILL,
                CatchUpBurstAuto.resolve(AMPLE_CORES, AMPLE_HEAP, CatchUpBurstAuto.RAM_FOR_UNCAPPED));
    }

    @Test
    @DisplayName("the RAM cap never promotes a weak machine")
    void ramCap_onlyEverLowers() {
        assertEquals(CatchUpBurstMode.OFF,
                CatchUpBurstAuto.resolve(2, 4 * GIB, AMPLE_RAM),
                "ample RAM must not lift a machine the CPU band put at OFF");
    }

    /**
     * FILL is what every install ran before AUTO existed, so an unreadable probe must leave
     * behaviour exactly as it was. Downgrading on ignorance would make a failed read look like a
     * performance regression that no log explains.
     */
    @Test
    @DisplayName("unknown specs keep today's behaviour rather than downgrading")
    void unknownSpecs_fallBackToFill() {
        assertEquals(CatchUpBurstMode.FILL, CatchUpBurstAuto.resolve(0, AMPLE_HEAP, AMPLE_RAM));
        assertEquals(CatchUpBurstMode.FILL, CatchUpBurstAuto.resolve(AMPLE_CORES, 0L, AMPLE_RAM));
        assertEquals(CatchUpBurstMode.FILL, CatchUpBurstAuto.resolve(-1, -1L, -1L));
    }

    /** Unknown physical RAM (0) means "cannot tell", which must not trigger the low-RAM cap. */
    @Test
    @DisplayName("unknown physical RAM does not cap a capable machine")
    void unknownRam_doesNotCap() {
        assertEquals(CatchUpBurstMode.FILL,
                CatchUpBurstAuto.resolve(AMPLE_CORES, AMPLE_HEAP, 0L));
    }

    @Test
    @DisplayName("resolve never returns AUTO, for any input")
    void neverReturnsAuto() {
        int[] cores = { -1, 0, 1, 2, 4, 8, 64 };
        long[] heaps = { -1L, 0L, GIB, 2 * GIB, 3 * GIB, 4 * GIB, 64 * GIB };
        long[] rams = { -1L, 0L, 4 * GIB, 8 * GIB, 128 * GIB };
        for (int c : cores) {
            for (long h : heaps) {
                for (long r : rams) {
                    assertNotEquals(CatchUpBurstMode.AUTO, CatchUpBurstAuto.resolve(c, h, r),
                            "AUTO resolving to itself would loop, or pace as BURST_TWO by accident");
                }
            }
        }
    }
}
