package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldGenCycle#isAtOrPastFarLands} on the shipped {@link CycleLayout}: off before the Far Lands,
 * on from halfway through their entry crossfade to the end of the run, off again when the next run
 * restarts at the overworld, and off behind spawn or when the Far Lands are not in the order.
 */
final class WorldGenCycleFarLandsTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final WorldGenCycle C = cycle(LAYOUT);

    private static final int FAR_SLOT = LAYOUT.legacySlotOf(LegacyBandKind.FAR_LANDS);
    private static final int FAR_ERA = LAYOUT.eraIndex(LegacyBandKind.FAR_LANDS);
    /** Base coordinate of the Far Lands core's first block. */
    private static final long FAR_CORE = LAYOUT.start(FAR_SLOT) + LAYOUT.eraCoreStart(FAR_SLOT, FAR_ERA);
    /** The switch point: halfway through the crossfade from Beta into the Far Lands. */
    private static final long FAR_SWITCH = FAR_CORE - LAYOUT.fadeBefore(FAR_SLOT, FAR_ERA) / 2;

    private static WorldGenCycle cycle(CycleLayout layout) {
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
                CycleLayoutTest.eraDefaults(), layout, 0);
    }

    /** World X of base coordinate {@code u} on run {@code k}. */
    private static int x(long u, int k) {
        return (int) (START + CycleLayout.runStart(k, P) + (u << k));
    }

    private static int x(long u) {
        return x(u, 0);
    }

    private static int firstSlotOf(CycleLayout.Type type) {
        for (int i = 0; i < LAYOUT.count(); i++) {
            if (LAYOUT.slot(i).type() == type) return i;
        }
        throw new AssertionError("no " + type + " slot in the shipped order");
    }

    @Test
    @DisplayName("off in the overworld, the first Nether, the first End and the eras before the Far Lands")
    void offBefore() {
        assertTrue(FAR_SLOT >= 0 && FAR_ERA > 0, "the shipped order runs the Far Lands after another era");
        assertFalse(C.isAtOrPastFarLands(x(0)));
        assertFalse(C.isAtOrPastFarLands(x(LAYOUT.start(firstSlotOf(CycleLayout.Type.NETHER)) + 100)));
        assertFalse(C.isAtOrPastFarLands(x(LAYOUT.start(firstSlotOf(CycleLayout.Type.END)) + 100)));
        long betaCore = LAYOUT.start(FAR_SLOT) + LAYOUT.eraCoreStart(FAR_SLOT, FAR_ERA - 1);
        assertFalse(C.isAtOrPastFarLands(x(betaCore)));
        assertFalse(C.isAtOrPastFarLands(x(FAR_SWITCH - 1)));
    }

    @Test
    @DisplayName("on from halfway into the Far Lands crossfade, through its core and every later phase of the run")
    void onFromFarLands() {
        assertTrue(C.isAtOrPastFarLands(x(FAR_SWITCH)));
        assertTrue(C.isAtOrPastFarLands(x(FAR_CORE)));
        assertTrue(C.isAtOrPastFarLands(x(FAR_CORE + LAYOUT.eraCoreLen(FAR_SLOT, FAR_ERA) + 1)));
        int stacks = firstSlotOf(CycleLayout.Type.STACKS);
        assertTrue(stacks > FAR_SLOT, "stacks come after the Far Lands in the shipped order");
        assertTrue(C.isAtOrPastFarLands(x(LAYOUT.start(stacks) + 10)));
        assertTrue(C.isAtOrPastFarLands(x(P - 1)));
    }

    @Test
    @DisplayName("the next run restarts at the overworld (off) and reaches its own Far Lands at twice the distance")
    void perRun() {
        assertFalse(C.isAtOrPastFarLands(x(0, 1)));
        assertFalse(C.isAtOrPastFarLands(x(FAR_SWITCH - 1, 1)));
        assertTrue(C.isAtOrPastFarLands(x(FAR_SWITCH, 1)));
        assertTrue(C.isAtOrPastFarLands(x(P - 1, 1)));
    }

    @Test
    @DisplayName("off behind spawn and before the anchor")
    void offBehindSpawn() {
        assertTrue(C.isMirroredAt(START - 10L * P));
        assertFalse(C.isAtOrPastFarLands((int) (START - 10L * P)));
        assertFalse(C.isAtOrPastFarLands((int) START - 1));
    }

    @Test
    @DisplayName("off everywhere when the Far Lands are not in the order")
    void offWithoutFarLands() {
        String order = CycleLayout.DEFAULT_ORDER.replace(":far_lands=4320", "");
        CycleLayout layout = CycleLayout.parse(order, CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(), t -> true, w -> {});
        assertEquals(-1, layout.legacySlotOf(LegacyBandKind.FAR_LANDS));
        WorldGenCycle c = cycle(layout);
        for (long u = 0; u < layout.period(); u += 997) {
            assertFalse(c.isAtOrPastFarLands(x(u)), "u=" + u);
        }
    }
}
