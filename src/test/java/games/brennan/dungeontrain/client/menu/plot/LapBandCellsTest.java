package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.worldgen.BandOption;
import games.brennan.dungeontrain.worldgen.LapBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry, mask arithmetic and click routing of the flat band option row. */
final class LapBandCellsTest {

    private static final int ALL = LapBand.ALL_MASK;
    private static final double L = 1.0;
    private static final double R = 3.2;

    @Test
    @DisplayName("every option's centre hits that option; the ends clamp")
    void geometryRoundTrips() {
        for (BandOption o : BandOption.values()) {
            double[] b = LapBandCells.bounds(o.ordinal(), L, R);
            assertEquals(o.ordinal(), LapBandCells.slotAt((b[0] + b[1]) / 2.0, L, R), o.name());
        }
        assertEquals(0, LapBandCells.slotAt(L - 5, L, R));
        assertEquals(BandOption.values().length - 1, LapBandCells.slotAt(R + 5, L, R));
        assertEquals(R, LapBandCells.bounds(BandOption.values().length - 1, L, R)[1], 1e-9);
    }

    @Test
    @DisplayName("groups are separated by a gap")
    void groupGap() {
        double endOfMain = LapBandCells.bounds(BandOption.CUSTOM.ordinal(), L, R)[1];
        double startOfLegacy = LapBandCells.bounds(BandOption.PRE_FAR_LANDS.ordinal(), L, R)[0];
        assertTrue(startOfLegacy > endOfMain);
    }

    @Test
    @DisplayName("toggle: all on turns off; some or none turns all on; never to no band")
    void toggle() {
        int nether = BandOption.NETHER.mask();
        assertEquals(OptionalInt.of(ALL & ~nether), LapBandCells.toggle(ALL, nether));
        int partial = LapBand.V_NETHER.bit() | LapBand.V_END.bit();
        assertEquals(OptionalInt.of(partial | nether), LapBandCells.toggle(partial, nether));
        assertTrue(LapBandCells.toggle(nether, nether).isEmpty());
    }

    @Test
    @DisplayName("click toggles the option; shift toggles its whole group; empty is refused")
    void clickRouting() {
        List<Integer> sent = new ArrayList<>();
        int[] refused = {0};
        LapBandCells.click(ALL, BandOption.OLD.ordinal(), false, sent::add, () -> refused[0]++);
        assertEquals(ALL & ~BandOption.OLD.mask(), sent.get(0));
        LapBandCells.click(ALL, BandOption.OLD.ordinal(), true, sent::add, () -> refused[0]++);
        assertEquals(ALL & ~BandOption.Group.LEGACY.mask(), sent.get(1));
        int legacyOnly = BandOption.Group.LEGACY.mask();
        LapBandCells.click(legacyOnly, BandOption.FAR_LANDS.ordinal(), true, sent::add, () -> refused[0]++);
        assertEquals(1, refused[0]);
        assertEquals(2, sent.size());
    }
}
