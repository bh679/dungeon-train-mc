package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The journey chain follows the layout: order, dedupe, disabled bands appended, closer last. */
final class BandAdvancementsTest {

    private static final CycleLayout.Fades FADES =
            new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);

    private static LegacySpan[] eraDefaults() {
        List<LegacySpan> out = new ArrayList<>();
        for (LegacyBandKind k : LegacyBandKind.values()) out.add(new LegacySpan(k, 3000, 480, 6000));
        return out.toArray(new LegacySpan[0]);
    }

    private static CycleLayout parse(String order) {
        List<String> warnings = new ArrayList<>();
        CycleLayout l = CycleLayout.parse(order, FADES, eraDefaults(), t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return l;
    }

    @Test
    @DisplayName("the shipped order chains every band in the order the player meets them")
    void shippedOrder() {
        List<String> chain = BandAdvancements.chain(parse(CycleLayout.DEFAULT_ORDER));
        assertEquals(List.of(
                "reached_nether", "reached_wwoo", "reached_void", "reached_end_islands",
                "the_upside_down", "reassembly_required",
                "reached_bop", "reached_better_nether", "reached_lost_city", "reached_better_end", "reached_spheres",
                "reached_amplified", "reached_beta", "reached_far_lands", "reached_caves_of_chaos", "reached_skylands", "reached_floating",
                "reached_alpha", "reached_infdev", "reached_classic", "reached_superflat",
                "reached_chuncks", "reached_stacks",
                "reached_overworld_again", "read_all_nether_starting_books"), chain);
    }

    @Test
    @DisplayName("moving a band in the order moves its advancement; every id appears exactly once")
    void reorderedBandsFollow() {
        List<String> chain = BandAdvancements.chain(parse(
                "ow:1000, stacks:5000, chuncks:5000, legacy:classic=2000:beta=5000, nether:4000, end:4000"));
        assertEquals("reached_stacks", chain.get(0));
        assertEquals("reached_chuncks", chain.get(1));
        // legacy eras run in the order the token names them
        assertEquals("reached_classic", chain.get(2));
        assertEquals("reached_beta", chain.get(3));
        assertEquals("reached_nether", chain.get(4));
        assertEquals("reached_void", chain.get(5));
        assertEquals("reached_end_islands", chain.get(6));
        assertEquals(chain.size(), new HashSet<>(chain).size());
        assertTrue(chain.containsAll(BandAdvancements.ALL));
        assertEquals(BandAdvancements.LATER_CYCLES, chain.subList(chain.size() - BandAdvancements.LATER_CYCLES.size(), chain.size()));
    }

    @Test
    @DisplayName("a band the layout leaves out is appended, not dropped, so it stays parented")
    void missingBandAppended() {
        List<String> chain = BandAdvancements.chain(parse("ow:1000, nether:4000"));
        assertEquals("reached_nether", chain.get(0));
        assertTrue(chain.contains("reached_spheres"));
        assertTrue(chain.indexOf("reached_spheres") > chain.indexOf("reached_nether"));
        assertEquals(BandAdvancements.LATER_CYCLES, chain.subList(chain.size() - BandAdvancements.LATER_CYCLES.size(), chain.size()));
    }

    @Test
    @DisplayName("a second vanilla Nether / End adds nothing; a BETTER one adds its own marker after the vanilla ones")
    void styledOccurrences() {
        List<String> chain = BandAdvancements.chain(parse("ow:1000, nether:4000, nether:4000, nether:better:4000"));
        assertEquals("reached_nether", chain.get(0));
        assertEquals("reached_better_nether", chain.get(1));
        assertEquals(1, chain.stream().filter("reached_nether"::equals).count());
    }

    @Test
    @DisplayName("no layout: the classic order, every band, the later-cycle links last")
    void classicFallback() {
        List<String> chain = BandAdvancements.chain(null);
        List<String> expected = new ArrayList<>(BandAdvancements.ALL);
        expected.removeAll(BandAdvancements.LATER_CYCLES);
        expected.addAll(BandAdvancements.LATER_CYCLES);
        assertEquals(expected, chain);
    }

    @Test
    @DisplayName("every advancement in the table has a trigger, and the triggers cover nothing else")
    void triggersMatchTable() {
        List<String> ids = BandAdvancements.triggers().stream().map(BandAdvancements.Trigger::id).toList();
        assertEquals(new HashSet<>(BandAdvancements.ALL), new HashSet<>(ids));
        assertEquals(ids.size(), new HashSet<>(ids).size());
        for (BandAdvancements.Trigger t : BandAdvancements.triggers()) {
            assertTrue(t.depth() > 0, t.id());
        }
    }
}
