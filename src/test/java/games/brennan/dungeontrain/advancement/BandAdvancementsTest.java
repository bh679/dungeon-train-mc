package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ---- reverse journey ---------------------------------------------------------------------

    private static final List<String> SHIPPED_REVERSE = List.of(
            "reversed_stacks", "reversed_chuncks",
            "reversed_superflat", "reversed_classic", "reversed_infdev", "reversed_alpha", "reversed_floating",
            "reversed_skylands", "reversed_caves_of_chaos", "reversed_far_lands", "reversed_beta", "reversed_amplified",
            "reversed_spheres", "reversed_better_end", "reversed_lost_city", "reversed_better_nether", "reversed_bop",
            "reversed_reassembly", "reversed_upside_down", "reversed_void", "reversed_end_islands", "reversed_wwoo",
            "reversed_nether");

    @Test
    @DisplayName("walking back from spawn meets the shipped bands last-first, all the way to the first Nether")
    void reverseShippedOrder() {
        assertEquals(SHIPPED_REVERSE, BandAdvancements.reverseChain(parse(CycleLayout.DEFAULT_ORDER)));
    }

    @Test
    @DisplayName("reverse: a Better Nether/End is its own band, so the plain first ones stay last")
    void reverseStyledOccurrences() {
        List<String> chain = BandAdvancements.reverseChain(parse("ow:1000, nether:4000, end:4000, nether:better:4000, end:better:4000"));
        assertEquals(List.of("reversed_better_end", "reversed_better_nether", "reversed_void", "reversed_end_islands",
                "reversed_nether"), chain.subList(0, 5));
    }

    @Test
    @DisplayName("reverse: a band the layout leaves out is appended; every reverse id appears exactly once")
    void reverseMissingAppended() {
        List<String> chain = BandAdvancements.reverseChain(parse("ow:1000, nether:4000, stacks:5000"));
        assertEquals("reversed_stacks", chain.get(0));
        assertEquals("reversed_nether", chain.get(1));
        assertEquals(chain.size(), new HashSet<>(chain).size());
        assertEquals(new HashSet<>(SHIPPED_REVERSE), new HashSet<>(chain));
        assertEquals(SHIPPED_REVERSE, BandAdvancements.reverseChain(null));
    }

    @Test
    @DisplayName("every reverse advancement has one trigger; isBackwards covers The Secrete Menu and nothing forward")
    void reverseTriggersMatchTable() {
        List<String> ids = BandAdvancements.reverseTriggers().stream().map(BandAdvancements.Trigger::id).toList();
        assertEquals(SHIPPED_REVERSE, ids);
        for (BandAdvancements.Trigger t : BandAdvancements.reverseTriggers()) {
            assertTrue(t.depth() > 0, t.id());
            assertTrue(BandAdvancements.isBackwards("secrete_menu/" + t.id()), t.id());
        }
        assertTrue(BandAdvancements.isBackwards("secrete_menu/root"));
        assertTrue(BandAdvancements.isBackwards("dungeon_train/secrete_menu"));
        for (String id : BandAdvancements.ALL) assertFalse(BandAdvancements.isBackwards("dungeon_train/" + id), id);
        assertFalse(BandAdvancements.isBackwards("dungeon_train/root"));
        assertFalse(BandAdvancements.isBackwards("editor/secrete_menu"));
    }

    @Test
    @DisplayName("each reverse advancement JSON sits on The Secrete Menu tab, hidden, and chains in shipped order")
    void reverseJsonMatchesChain() throws IOException {
        String parent = "dungeontrain:secrete_menu/" + BandAdvancements.REVERSE_ANCHOR;
        for (String id : SHIPPED_REVERSE) {
            JsonObject json = advancement("secrete_menu/" + id);
            assertEquals(parent, json.get("parent").getAsString(), id);
            assertTrue(json.getAsJsonObject("display").get("hidden").getAsBoolean(), id);
            assertEquals(id, actionId(json), id);
            parent = "dungeontrain:secrete_menu/" + id;
        }
    }

    @Test
    @DisplayName("The Secrete Menu: a hidden tab root and a hidden twin under Dungeon Train Explorer, on one action")
    void secreteMenuPair() throws IOException {
        JsonObject root = advancement("secrete_menu/root");
        assertFalse(root.has("parent"));
        assertTrue(root.getAsJsonObject("display").has("background"));
        JsonObject twin = advancement("dungeon_train/secrete_menu");
        assertEquals("dungeontrain:dungeon_train/" + BandAdvancements.ANCHOR, twin.get("parent").getAsString());
        for (JsonObject json : List.of(root, twin)) {
            assertTrue(json.getAsJsonObject("display").get("hidden").getAsBoolean());
            assertEquals(BandAdvancements.SECRETE_MENU, actionId(json));
            assertEquals("advancements.dungeontrain.dungeon_train.secrete_menu.title",
                    json.getAsJsonObject("display").getAsJsonObject("title").get("translate").getAsString());
        }
    }

    private static JsonObject advancement(String path) throws IOException {
        Path file = RepoPaths.advancements().resolve(path + ".json");
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static String actionId(JsonObject json) {
        return json.getAsJsonObject("criteria").getAsJsonObject("reached")
                .getAsJsonObject("conditions").get("actionId").getAsString();
    }
}
