package games.brennan.dungeontrain.track;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec + draw coverage for {@link TrackVariantGroup} — the sub-variant sidecar behind portal room
 * groups. Pure logic: no filesystem, no Forge bootstrap.
 */
final class TrackVariantGroupTest {

    private static TrackVariantGroup group(String... idsAndWeights) {
        List<TrackVariantGroup.Member> ms = new java.util.ArrayList<>();
        for (int i = 0; i < idsAndWeights.length; i += 2) {
            ms.add(new TrackVariantGroup.Member(idsAndWeights[i], Integer.parseInt(idsAndWeights[i + 1])));
        }
        return new TrackVariantGroup(ms);
    }

    @Test
    @DisplayName("json: an ungated group round-trips to the bare {id, weight} shape")
    void json_roundTrip_bare() {
        TrackVariantGroup g = group("library_tall", "5", "library_dark", "2").withSelfWeight(3);
        JsonObject json = g.toJson();

        assertEquals(TrackVariantGroup.SCHEMA_VERSION, json.get(TrackVariantGroup.SCHEMA_KEY).getAsInt());
        assertEquals(3, json.get("selfWeight").getAsInt());
        JsonObject first = json.getAsJsonArray("variants").get(0).getAsJsonObject();
        assertEquals("library_tall", first.get("id").getAsString());
        assertEquals(5, first.get("weight").getAsInt());
        assertFalse(first.has("minLevel"), "an ungated member writes no gate fields");
        assertFalse(first.has("phases"), "an ungated member writes no gate fields");
        assertFalse(first.has("stage"), "an unlinked member writes no stage field");

        TrackVariantGroup back = TrackVariantGroup.fromJson(json);
        assertEquals(g, back);
    }

    @Test
    @DisplayName("json: gates and Stage links survive the round trip")
    void json_roundTrip_gatedAndLinked() {
        TrackVariantGroup g = new TrackVariantGroup(1, List.of(
            new TrackVariantGroup.Member("library_nether", 2,
                new TemplateGate(4, 9, EnumSet.of(TrainPhase.NETHER)), List.of()),
            new TrackVariantGroup.Member("library_late", 1,
                TemplateGate.DEFAULT, List.of("late", "endgame"))));

        TrackVariantGroup back = TrackVariantGroup.fromJson(g.toJson());
        assertEquals(g, back);
        assertTrue(back.member("library_late").orElseThrow().isStageLinked());
        assertEquals(List.of("late", "endgame"), back.member("library_late").orElseThrow().stageIds());
        assertEquals(4, back.member("library_nether").orElseThrow().gate().minLevel());
    }

    @Test
    @DisplayName("json: a malformed member is skipped, not fatal; 'default' is never a member")
    void json_tolerantReader() {
        JsonObject o = JsonParser.parseString("""
            {
              "schemaVersion": 3,
              "selfWeight": 2,
              "variants": [
                {"id": "good_one", "weight": 4},
                {"weight": 3},
                {"id": "Not A Name!", "weight": 1},
                {"id": "default", "weight": 9},
                "nonsense"
              ]
            }
            """).getAsJsonObject();

        TrackVariantGroup g = TrackVariantGroup.fromJson(o);
        assertEquals(2, g.selfWeight());
        assertEquals(1, g.members().size());
        assertEquals("good_one", g.members().get(0).id());
    }

    @Test
    @DisplayName("member: ids are normalised and validated, weights clamped")
    void member_validation() {
        assertEquals("library_tall", new TrackVariantGroup.Member("Library_Tall", 1).id());
        assertEquals(TrackVariantGroup.MAX_WEIGHT, new TrackVariantGroup.Member("a", 9999).weight());
        assertEquals(TrackVariantGroup.MIN_WEIGHT, new TrackVariantGroup.Member("a", -5).weight());
        assertThrows(IllegalArgumentException.class, () -> new TrackVariantGroup.Member("has spaces", 1));
    }

    @Test
    @DisplayName("members: a repeat id updates the first slot in place, keeping plot order stable")
    void members_dedupeKeepsPosition() {
        TrackVariantGroup g = new TrackVariantGroup(List.of(
            new TrackVariantGroup.Member("a", 1),
            new TrackVariantGroup.Member("b", 2),
            new TrackVariantGroup.Member("a", 9)));

        assertEquals(2, g.members().size());
        assertEquals("a", g.members().get(0).id());
        assertEquals(9, g.members().get(0).weight());
        assertEquals(0, g.indexOf("a"));
        assertEquals(1, g.indexOf("b"));
    }

    @Test
    @DisplayName("pick: null means the parent — self entry, empty pool, or all-zero weights")
    void pick_selfCases() {
        TrackVariantGroup onlySelf = group("child", "0").withSelfWeight(1);
        // Every member weighs 0, so only the self entry can win.
        for (int seed = 0; seed < 20; seed++) {
            assertNull(onlySelf.pick(new Random(seed), onlySelf.members()));
        }
        TrackVariantGroup allZero = group("child", "0").withSelfWeight(0);
        assertNull(allZero.pick(new Random(1), allZero.members()));
        assertNull(group("child", "1").pick(new Random(1), List.of()));
    }

    @Test
    @DisplayName("pick: weights bias the draw and every entry is reachable")
    void pick_distribution() {
        TrackVariantGroup g = group("heavy", "8", "light", "1").withSelfWeight(1);
        int heavy = 0, light = 0, self = 0;
        for (int seed = 0; seed < 400; seed++) {
            TrackVariantGroup.Member m = g.pick(new Random(seed), g.members());
            if (m == null) self++;
            else if (m.id().equals("heavy")) heavy++;
            else light++;
        }
        assertTrue(self > 0 && light > 0 && heavy > 0, "every entry should be reachable");
        assertTrue(heavy > light * 3, "an 8:1 weight should dominate — got heavy=" + heavy + " light=" + light);
    }

    @Test
    @DisplayName("pick: the same rng seed always draws the same sub-variant")
    void pick_deterministic() {
        TrackVariantGroup g = group("a", "3", "b", "3", "c", "3").withSelfWeight(1);
        for (int seed = 0; seed < 50; seed++) {
            TrackVariantGroup.Member first = g.pick(new Random(seed), g.members());
            TrackVariantGroup.Member again = g.pick(new Random(seed), g.members());
            assertEquals(first == null ? "<self>" : first.id(), again == null ? "<self>" : again.id());
        }
    }

    @Test
    @DisplayName("withStageToggled: adds when absent, removes when present, normalises the id")
    void member_stageToggle() {
        TrackVariantGroup.Member m = new TrackVariantGroup.Member("library_tall", 1);
        assertFalse(m.isStageLinked());

        TrackVariantGroup.Member linked = m.withStageToggled("Nether");
        assertEquals(List.of("nether"), linked.stageIds(), "the id is lower-cased on the way in");
        assertTrue(linked.isStageLinked());
        assertFalse(m.isStageLinked(), "the original is untouched");

        // A second, different stage is a union — the first link stays and order is preserved.
        TrackVariantGroup.Member two = linked.withStageToggled("late");
        assertEquals(List.of("nether", "late"), two.stageIds());

        // Toggling one back off leaves the other, and does not disturb weight or inline gate.
        TrackVariantGroup.Member back = two.withStageToggled("nether");
        assertEquals(List.of("late"), back.stageIds());
        assertEquals(1, back.weight());
        assertEquals(TemplateGate.DEFAULT, back.gate());

        // Blank / null are no-ops rather than an empty link.
        assertEquals(two, two.withStageToggled(null));
        assertEquals(two, two.withStageToggled("  "));
    }

    @Test
    @DisplayName("withMember / withoutMember are copies, never mutations")
    void immutableUpdates() {
        TrackVariantGroup g = group("a", "1");
        TrackVariantGroup added = g.withMember(new TrackVariantGroup.Member("b", 2));
        TrackVariantGroup removed = added.withoutMember("a");

        assertEquals(1, g.members().size(), "the original is untouched");
        assertEquals(2, added.members().size());
        assertEquals(List.of("b"), removed.members().stream().map(TrackVariantGroup.Member::id).toList());
        assertEquals(added, added.withoutMember("not_a_member"));
    }
}
