package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.worldgen.LapBand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry, mask arithmetic and click routing of the world-space lap / band selector. */
final class LapBandCellsTest {

    private static final int ALL = LapBand.ALL_MASK;
    private static final double L = 1.0;
    private static final double R = 3.2;

    @AfterEach
    void reset() {
        LapBandView.close();
    }

    private static double centre(int slot, LapBand.Lap open) {
        double[] b = LapBandCells.bounds(slot, L, R, open);
        return (b[0] + b[1]) / 2.0;
    }

    @Test
    @DisplayName("every slot's centre hits that slot, in the lap view and each band view")
    void geometryRoundTrips() {
        for (LapBand.Lap lap : LapBand.Lap.values()) {
            assertEquals(lap.ordinal(), LapBandCells.slotAt(centre(lap.ordinal(), null), L, R, null));
            assertEquals(LapBandCells.BACK_SLOT, LapBandCells.slotAt(centre(LapBandCells.BACK_SLOT, lap), L, R, lap));
            for (LapBand band : lap.members()) {
                int slot = LapBandCells.bandSlot(band);
                assertEquals(slot, LapBandCells.slotAt(centre(slot, lap), L, R, lap), band.name());
            }
        }
        assertEquals(0, LapBandCells.slotAt(L - 5, L, R, null));
        assertEquals(3, LapBandCells.slotAt(R + 5, L, R, null));
    }

    @Test
    @DisplayName("lap toggle: all on turns off, otherwise all on; never to no band")
    void toggleLap() {
        assertEquals(OptionalInt.of(ALL & ~LapBand.Lap.MOD.mask()), LapBandCells.toggleLap(ALL, LapBand.Lap.MOD));
        int one = LapBand.M_SPHERES.bit() | LapBand.V_NETHER.bit();
        assertEquals(OptionalInt.of(one | LapBand.Lap.MOD.mask()), LapBandCells.toggleLap(one, LapBand.Lap.MOD));
        assertTrue(LapBandCells.toggleLap(LapBand.Lap.CORRUPT.mask(), LapBand.Lap.CORRUPT).isEmpty());
    }

    @Test
    @DisplayName("band click flips it; shift flips every other band; never to no band")
    void clickBand() {
        assertEquals(OptionalInt.of(ALL & ~LapBand.M_NETHER.bit()), LapBandCells.clickBand(ALL, LapBand.M_NETHER, false));
        assertEquals(OptionalInt.of(LapBand.M_NETHER.bit()), LapBandCells.clickBand(ALL, LapBand.M_NETHER, true));
        assertTrue(LapBandCells.clickBand(LapBand.V_END.bit(), LapBand.V_END, false).isEmpty());
    }

    @Test
    @DisplayName("click routing: a lap opens, back closes, band edits send the new mask, empty is refused")
    void clickRouting() {
        List<Integer> sent = new ArrayList<>();
        int[] refused = {0};
        LapBandCells.click("row", ALL, LapBand.Lap.LEGACY.ordinal(), false, sent::add, () -> refused[0]++);
        assertEquals(LapBand.Lap.LEGACY, LapBandView.openLap("row"));
        assertNull(LapBandView.openLap("other"));
        LapBandCells.click("row", ALL, LapBandCells.bandSlot(LapBand.L_BETA), false, sent::add, () -> refused[0]++);
        assertEquals(List.of(ALL & ~LapBand.L_BETA.bit()), sent);
        LapBandCells.click("row", LapBand.L_BETA.bit(), LapBandCells.bandSlot(LapBand.L_BETA), false, sent::add,
            () -> refused[0]++);
        assertEquals(1, refused[0]);
        // Shift on the open lap's own cell toggles the whole lap and keeps its letters showing.
        LapBandCells.click("row", ALL, LapBandCells.BACK_SLOT, true, sent::add, () -> refused[0]++);
        assertEquals(ALL & ~LapBand.Lap.LEGACY.mask(), sent.get(sent.size() - 1));
        assertEquals(LapBand.Lap.LEGACY, LapBandView.openLap("row"));
        LapBandCells.click("row", ALL & ~LapBand.Lap.LEGACY.mask(), LapBandCells.BACK_SLOT, true, sent::add,
            () -> refused[0]++);
        assertEquals(ALL, sent.get(sent.size() - 1));
        LapBandCells.click("row", ALL, LapBandCells.BACK_SLOT, false, sent::add, () -> refused[0]++);
        assertNull(LapBandView.openLap("row"));
        LapBandCells.click("row", ALL, LapBand.Lap.MOD.ordinal(), true, sent::add, () -> refused[0]++);
        assertEquals(ALL & ~LapBand.Lap.MOD.mask(), sent.get(sent.size() - 1));
        assertNull(LapBandView.openLap("row"));
    }
}
