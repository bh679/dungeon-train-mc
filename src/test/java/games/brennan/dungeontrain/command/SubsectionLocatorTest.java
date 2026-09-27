package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.worldgen.BandStages;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.SpheresSegments;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /dtp <band> <subsection>} against pure {@link WorldGenCycle}s: every subsection of every slot is landed inside. */
final class SubsectionLocatorTest {

    private static final long START = 10_000L;
    private static final CycleLayout.Fades FADES = new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);
    private static final SpheresSegments SPHERES = SpheresSegments.of(1000, 1750, 2500, 3250, 6250, 0.1, 5, 20, 1, 1, 1);

    private static LegacySpan[] eraDefaults() {
        List<LegacySpan> out = new ArrayList<>();
        for (LegacyBandKind k : LegacyBandKind.values()) out.add(new LegacySpan(k, 3000, 480, 6000));
        return out.toArray(new LegacySpan[0]);
    }

    private static WorldGenCycle cycle() {
        CycleLayout layout = CycleLayout.parse(CycleLayout.DEFAULT_ORDER, FADES, eraDefaults(), t -> true, w -> {});
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
                eraDefaults(), layout, 0);
    }

    private static List<BandStages.Stage> stages(WorldGenCycle c, int slot) {
        return BandStages.of(c.layout(), slot, 5, 40, 32, SPHERES, 550, 100);
    }

    /** A column inside slot {@code i}'s occurrence in doubling run {@code run}. */
    private static int slotX(WorldGenCycle c, int i, int run) {
        return (int) (START + CycleLayout.runStart(run, c.period()) + (c.layout().start(i) << run));
    }

    @Test
    @DisplayName("every subsection of every slot is landed inside, in run 0 and the doubled run 2")
    void everySubsectionLandsInside() {
        WorldGenCycle c = cycle();
        for (int run : new int[] {0, 2}) {
            for (int i = 0; i < c.layout().count(); i++) {
                int anyX = slotX(c, i, run);
                assertEquals(i, c.slotIndexAt(anyX), "slot " + i + " run " + run);
                List<BandStages.Stage> st = stages(c, i);
                SubsectionLocator.Subsections subs = SubsectionLocator.of(c, st, "any", anyX).orElseThrow();
                for (int k = 0; k < st.size(); k++) {
                    OptionalLong x = SubsectionLocator.targetX(c, subs, k, 32);
                    assertTrue(x.isPresent());
                    int at = (int) x.getAsLong();
                    assertEquals(i, c.slotIndexAt(at), "slot " + i + " stage " + subs.tokens().get(k) + " run " + run);
                    assertEquals(k, BandStages.locate(st, c.slotLocal(at)).index(),
                        "slot " + i + " stage " + subs.tokens().get(k) + " run " + run);
                }
            }
        }
    }

    @Test
    @DisplayName("tokens are unique; the Nether's mirrored stages get _out")
    void tokens() {
        WorldGenCycle c = cycle();
        int nether = c.layout().firstIndexOf(CycleLayout.Type.NETHER);
        List<String> tokens = BandStages.tokens(stages(c, nether));
        assertEquals(tokens.size(), new HashSet<>(tokens).size(), tokens.toString());
        assertTrue(tokens.contains("mountain_3") && tokens.contains("mountain_3_out"), tokens.toString());
        assertTrue(tokens.contains("beach") && tokens.contains("beach_out"), tokens.toString());
        assertEquals("amplified_to_beta", BandStages.tokens(List.of(new BandStages.Stage("Amplified → Beta", 1))).get(0));
    }

    @Test
    @DisplayName("a legacy era offers only the stages that name it; unknown tokens resolve to nothing")
    void legacyFilter() {
        WorldGenCycle c = cycle();
        int legacy = c.layout().firstIndexOf(CycleLayout.Type.LEGACY_RUN);
        int anyX = slotX(c, legacy, 0);
        List<String> offered = SubsectionLocator.of(c, stages(c, legacy), "beta", anyX).orElseThrow().offeredTokens();
        assertTrue(offered.contains("beta"), offered.toString());
        assertTrue(offered.stream().allMatch(t -> ("_" + t + "_").contains("_beta_")), offered.toString());
        assertFalse(offered.contains("classic"), offered.toString());
        SubsectionLocator.Subsections subs = SubsectionLocator.of(c, stages(c, legacy), "beta", anyX).orElseThrow();
        assertEquals(-1, subs.indexOf("classic"));
        assertTrue(SubsectionLocator.targetX(c, subs, subs.indexOf("nope"), 32).isEmpty());
    }
}
