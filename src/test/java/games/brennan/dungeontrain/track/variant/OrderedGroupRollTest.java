package games.brennan.dungeontrain.track.variant;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordered dimensional sub-variants: the {@link VisitRules} schema and the {@link OrderedGroupRoll}
 * draw (order, seen again, weight after the Xth visit). Pure logic, no bootstrap.
 */
final class OrderedGroupRollTest {

    private static TrackVariantGroup.Member m(String id, int weight, VisitRules rules) {
        return new TrackVariantGroup.Member(id, weight).withVisits(rules);
    }

    /** The CMRA shape: a lobby that is step 1 and never seen again, hallways as step 2, one later room. */
    private static final List<TrackVariantGroup.Member> CMRA = List.of(
        m("cmra_lobby", 1, VisitRules.NONE.withOrder(1).withSeenAgain(false)),
        m("cmra_hallway_basic", 5, VisitRules.NONE.withOrder(2)),
        m("cmra_hallway_alcove_01", 3, VisitRules.NONE.withOrder(2)),
        m("cmra_hallway_basic_warped", 1, VisitRules.NONE.withAfterVisits(3).withLaterWeight(4)));

    private static ToIntFunction<String> visits(Map<String, Integer> counts) {
        return id -> counts.getOrDefault(id, 0);
    }

    private static Set<String> drawn(int selfWeight, List<TrackVariantGroup.Member> members,
                                     Map<String, Integer> counts) {
        Set<String> out = new HashSet<>();
        OrderedGroupRoll.Pool pool = OrderedGroupRoll.pool(selfWeight, members, visits(counts));
        // One stream: fresh Randoms on consecutive small seeds give near-identical first draws.
        Random rng = new Random(42);
        for (int i = 0; i < 500; i++) {
            TrackVariantGroup.Member chosen = OrderedGroupRoll.pick(rng, pool);
            out.add(chosen == null ? "<parent>" : chosen.id());
        }
        return out;
    }

    @Test
    @DisplayName("a first visit is always the step-1 lobby, never the parent")
    void firstVisitIsLobby() {
        assertEquals(Set.of("cmra_lobby"), drawn(5, CMRA, Map.of()));
    }

    @Test
    @DisplayName("after the lobby, only step 2 draws until a hallway is seen")
    void secondStepAfterLobby() {
        assertEquals(Set.of("cmra_hallway_basic", "cmra_hallway_alcove_01"),
            drawn(5, CMRA, Map.of("cmra_lobby", 1)));
    }

    @Test
    @DisplayName("once every step is seen: everything but the not-seen-again lobby, and the parent")
    void allStepsDone() {
        Set<String> got = drawn(5, CMRA, Map.of("cmra_lobby", 1, "cmra_hallway_basic", 1));
        assertFalse(got.contains("cmra_lobby"));
        assertTrue(got.containsAll(Set.of("<parent>", "cmra_hallway_basic", "cmra_hallway_alcove_01",
            "cmra_hallway_basic_warped")));
    }

    @Test
    @DisplayName("the weight switches to the later weight from the Xth visit on")
    void laterWeightAfterVisits() {
        Map<String, Integer> counts = new HashMap<>(Map.of("cmra_lobby", 1, "cmra_hallway_basic", 1));
        counts.put("cmra_hallway_basic_warped", 2);
        assertEquals(1, weightOf("cmra_hallway_basic_warped", counts));
        counts.put("cmra_hallway_basic_warped", 3);
        assertEquals(4, weightOf("cmra_hallway_basic_warped", counts));
    }

    private static int weightOf(String id, Map<String, Integer> counts) {
        return OrderedGroupRoll.pool(0, CMRA, visits(counts)).members().stream()
            .filter(w -> w.member().id().equals(id)).findFirst().orElseThrow().weight();
    }

    @Test
    @DisplayName("a step with nothing left to draw is skipped, not a dead end")
    void gatedOutStepSkipped() {
        // The lobby is gated out at this level (absent from the eligible list): step 2 draws.
        List<TrackVariantGroup.Member> eligible = CMRA.subList(1, CMRA.size());
        assertEquals(Set.of("cmra_hallway_basic", "cmra_hallway_alcove_01"), drawn(5, eligible, Map.of()));
    }

    @Test
    @DisplayName("a step counts as done when any of its members was seen, eligible here or not")
    void stepDoneByIneligibleMember() {
        // Step 2 was finished through alcove_01 elsewhere; only basic is eligible now.
        List<TrackVariantGroup.Member> eligible = List.of(CMRA.get(1), CMRA.get(3));
        List<TrackVariantGroup.Member> all = CMRA;
        assertEquals(0, OrderedGroupRoll.currentStep(eligible, all,
            visits(Map.of("cmra_lobby", 1, "cmra_hallway_alcove_01", 1))));
    }

    @Test
    @DisplayName("every member used up -> the parent")
    void nothingLeftIsParent() {
        List<TrackVariantGroup.Member> once = List.of(m("a", 1, VisitRules.NONE.withSeenAgain(false)));
        OrderedGroupRoll.Pool pool = OrderedGroupRoll.pool(0, once, visits(Map.of("a", 1)));
        assertNull(OrderedGroupRoll.pick(new Random(1), pool));
    }

    @Test
    @DisplayName("a group without rules draws exactly what TrackVariantGroup.pick draws")
    void ruleFreeGroupMatchesLegacyPick() {
        List<TrackVariantGroup.Member> plain = List.of(
            new TrackVariantGroup.Member("a", 5), new TrackVariantGroup.Member("b", 2),
            new TrackVariantGroup.Member("c", 0), new TrackVariantGroup.Member("d", 3));
        TrackVariantGroup group = new TrackVariantGroup(4, plain);
        for (int seed = 0; seed < 2000; seed++) {
            TrackVariantGroup.Member legacy = group.pick(new Random(seed), plain);
            TrackVariantGroup.Member ordered = OrderedGroupRoll.pick(new Random(seed),
                OrderedGroupRoll.pool(4, plain, id -> 7));
            assertSame(legacy, ordered, "seed " + seed);
        }
        assertFalse(group.usesVisitRules());
        assertTrue(new TrackVariantGroup(0, CMRA).usesVisitRules());
    }

    @Test
    @DisplayName("json: default rules are omitted, so an old group file round-trips unchanged")
    void jsonOldFileUnchanged() {
        String old = "{\"schemaVersion\":3,\"selfWeight\":0,\"variants\":["
            + "{\"id\":\"cmra_lobby\",\"weight\":1},{\"id\":\"cmra_hallway_basic\",\"weight\":5}]}";
        JsonObject in = JsonParser.parseString(old).getAsJsonObject();
        TrackVariantGroup g = TrackVariantGroup.fromJson(in);
        assertFalse(g.usesVisitRules());
        assertEquals(in, g.toJson());
    }

    @Test
    @DisplayName("json: rules round-trip, and malformed fields read as defaults")
    void jsonRulesRoundTrip() {
        TrackVariantGroup g = new TrackVariantGroup(0, CMRA);
        TrackVariantGroup back = TrackVariantGroup.fromJson(g.toJson());
        assertEquals(g, back);
        JsonObject lobby = g.toJson().getAsJsonArray("variants").get(0).getAsJsonObject();
        assertEquals(1, lobby.get("order").getAsInt());
        assertFalse(lobby.get("seenAgain").getAsBoolean());
        assertFalse(lobby.has("afterVisits"));

        JsonObject bad = JsonParser.parseString(
            "{\"id\":\"x\",\"order\":\"first\",\"seenAgain\":3,\"afterVisits\":500,\"laterWeight\":-2}")
            .getAsJsonObject();
        VisitRules r = VisitRules.fromJson(bad);
        assertEquals(0, r.order());
        assertTrue(r.seenAgain());
        assertEquals(VisitRules.MAX_AFTER_VISITS, r.afterVisits());
        assertEquals(0, r.laterWeight());
    }
}
