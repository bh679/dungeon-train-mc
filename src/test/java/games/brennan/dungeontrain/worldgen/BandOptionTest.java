package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The editor's band options: what each controls, and that together they cover every band once. */
final class BandOptionTest {

    @Test
    @DisplayName("options partition every LapBand exactly once")
    void partitionsAllBands() {
        EnumSet<LapBand> seen = EnumSet.noneOf(LapBand.class);
        for (BandOption o : BandOption.values()) {
            for (LapBand b : o.bands()) assertEquals(true, seen.add(b), b + " is in two options");
        }
        assertEquals(EnumSet.allOf(LapBand.class), seen);
        int union = 0;
        for (BandOption.Group g : BandOption.Group.values()) union |= g.mask();
        assertEquals(LapBand.ALL_MASK, union);
    }

    @Test
    @DisplayName("row reads O N E C | P F O | C S")
    void rowLetters() {
        assertEquals("ONEC|PFO|CS", Stream.of(BandOption.Group.values())
            .map(g -> g.options().stream().map(BandOption::letter).collect(Collectors.joining()))
            .collect(Collectors.joining("|")));
    }

    @Test
    @DisplayName("vanilla and modded share options; Custom is Upside Down + Reassembly + Spheres")
    void sharedOptions() {
        assertEquals(EnumSet.of(LapBand.V_NETHER, LapBand.M_NETHER), BandOption.NETHER.bands());
        assertEquals(EnumSet.of(LapBand.V_UPSIDE_DOWN, LapBand.V_REASSEMBLY, LapBand.M_SPHERES), BandOption.CUSTOM.bands());
        assertEquals(true, BandOption.OVERWORLD.bands().contains(LapBand.C_OVERWORLD_2), "Corrupt gaps fold into O");
        assertEquals(EnumSet.of(LapBand.L_LARGE_BIOMES, LapBand.L_AMPLIFIED, LapBand.L_BETA), BandOption.PRE_FAR_LANDS.bands());
    }

    @Test
    @DisplayName("state reads all / some / none")
    void state() {
        assertEquals(BandOption.State.ALL, BandOption.NETHER.state(LapBand.ALL_MASK));
        assertEquals(BandOption.State.SOME, BandOption.NETHER.state(LapBand.V_NETHER.bit()));
        assertEquals(BandOption.State.NONE, BandOption.NETHER.state(LapBand.V_END.bit()));
    }
}
