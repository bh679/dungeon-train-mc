package games.brennan.dungeontrain.train;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CarriageContentsPools}: a pooled group is drawn by its own weight, scaled by the share of its rooms open
 * at that spot. Weighted at their full room totals, groups sharing a slot give each room the chance it had when
 * all of them were one group, at every level; and adding rooms to a group never makes the group more common.
 */
final class CarriageContentsPoolsTest {

    private static final int SAMPLES = 60_000;

    @BeforeEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
        CarriageContentsGroupStore.clearCache();
        CarriageContentsPools.clearCache();
        CarriageContentsWeights.clear();
    }

    @AfterEach
    void restore() {
        cleanSlate();
    }

    private static CarriageContentsGroup.Member member(String id, int weight, int minLevel) {
        return new CarriageContentsGroup.Member(id, weight, TemplateGate.ofLevels(minLevel, TemplateGate.ALL), List.of());
    }

    private static void register(String... ids) {
        for (String id : ids) CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom(id));
    }

    private static Map<String, Double> frequencies(GateContext ctx) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            String id = CarriageContentsRegistry.pick(0x5EEDL, i, CarriageContentsAllowList.EMPTY, ctx).id();
            counts.merge(id, 1, Integer::sum);
        }
        Map<String, Double> out = new HashMap<>();
        counts.forEach((id, n) -> out.put(id, n / (double) SAMPLES));
        return out;
    }

    /** Rooms a..e: e is level-gated so the eligible weight changes between the two levels tested. */
    private static List<CarriageContentsGroup.Member> rooms() {
        return List.of(member("a", 10, 0), member("b", 3, 0), member("c", 2, 0), member("d", 4, 0), member("e", 12, 9));
    }

    @Test
    @DisplayName("pooled groups give every room the chance one combined group gave, below and above a gate")
    void poolMatchesOneGroup() {
        for (int level : new int[] {0, 10}) {
            GateContext ctx = new GateContext(level, TrainPhase.OVERWORLD);

            cleanSlate();
            register("other", "all", "a", "b", "c", "d", "e");
            CarriageContentsGroupStore.injectForTesting("all", new CarriageContentsGroup(0, rooms()));
            Map<String, Double> combined = frequencies(ctx);

            cleanSlate();
            register("other", "g1", "g2", "a", "b", "c", "d", "e");
            List<CarriageContentsGroup.Member> all = rooms();
            CarriageContentsGroupStore.injectForTesting("g1", new CarriageContentsGroup(0, all.subList(0, 2)));
            CarriageContentsGroupStore.injectForTesting("g2", new CarriageContentsGroup(0, all.subList(2, 5)));
            CarriageContentsPools.injectForTesting(List.of(new CarriageContentsPools.Pool("p", 1, List.of("g1", "g2"))));
            CarriageContentsWeights.injectForTesting(Map.of("g1", 13, "g2", 18));
            Map<String, Double> pooled = frequencies(ctx);

            for (String room : List.of("other", "a", "b", "c", "d", "e")) {
                double want = combined.getOrDefault(room, 0.0);
                double got = pooled.getOrDefault(room, 0.0);
                assertEquals(want, got, 0.012, "level " + level + " room " + room);
            }
            assertNull(pooled.get("g1"), "a pooled group with no room of its own is never stamped itself");
            if (level == 0) assertNull(pooled.get("e"), "gated room stays out below its level");
        }
    }

    @Test
    @DisplayName("a group's weight holds however many rooms it has")
    void groupWeightHoldsAsRoomsAreAdded() {
        GateContext ctx = new GateContext(10, TrainPhase.OVERWORLD);
        register("small", "big", "s1", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9", "b10");
        CarriageContentsGroupStore.injectForTesting("small", new CarriageContentsGroup(0, List.of(member("s1", 1, 0))));
        CarriageContentsGroupStore.injectForTesting("big", new CarriageContentsGroup(0, List.of(
            member("b1", 1, 0), member("b2", 1, 0), member("b3", 1, 0), member("b4", 1, 0), member("b5", 1, 0),
            member("b6", 1, 0), member("b7", 1, 0), member("b8", 1, 0), member("b9", 1, 0), member("b10", 1, 0))));
        CarriageContentsPools.injectForTesting(List.of(new CarriageContentsPools.Pool("p", 1, List.of("small", "big"))));
        CarriageContentsWeights.injectForTesting(Map.of("small", 1, "big", 1));
        Map<String, Double> f = frequencies(ctx);
        double big = 0;
        for (int i = 1; i <= 10; i++) big += f.getOrDefault("b" + i, 0.0);
        assertEquals(big, f.get("s1"), 0.012, "one room against ten, equal group weights " + f);
    }

    @Test
    @DisplayName("a group offers its weight times the share of its rooms open, nothing when none are")
    void offeredShare() {
        assertEquals(6.0, CarriageContentsPools.offeredShare(12, 5, 10), 1e-9);
        assertEquals(12.0, CarriageContentsPools.offeredShare(12, 10, 10), 1e-9);
        assertEquals(0.0, CarriageContentsPools.offeredShare(12, 0, 10), 1e-9);
        assertEquals(0.0, CarriageContentsPools.offeredShare(0, 10, 10), 1e-9);
    }

    @Test
    @DisplayName("pickMember draws by offered weight, and uniformly when nothing is offered")
    void pickMemberWeights() {
        Random rng = new Random(1);
        int[] hits = new int[3];
        for (int i = 0; i < 30_000; i++) hits[CarriageContentsPools.pickMember(new double[] {1, 0, 3}, rng)]++;
        assertEquals(0, hits[1]);
        assertEquals(0.75, hits[2] / 30_000.0, 0.02);
        assertTrue(CarriageContentsPools.pickMember(new double[] {0, 0}, rng) < 2);
    }

    @Test
    @DisplayName("parse reads weight and members, skips _doc keys and empty pools")
    void parse() {
        List<CarriageContentsPools.Pool> pools = CarriageContentsPools.parse(JsonParser.parseString(
            "{\"_doc\": \"x\", \"enemies\": {\"weight\": 8, \"members\": [\"Enemies_Undead\", \"enemies_end\"]},"
                + " \"empty\": {\"weight\": 2, \"members\": []}}"));
        assertEquals(1, pools.size());
        assertEquals(8, pools.get(0).weight());
        assertEquals(List.of("enemies_undead", "enemies_end"), pools.get(0).members());
    }

    @Test
    @DisplayName("the bundled pools.json splits enemies into its eight groups")
    void bundledEnemiesPool() {
        CarriageContentsPools.Pool pool = CarriageContentsPools.poolOf("enemies_creepers");
        assertEquals("enemies", pool.id());
        assertEquals(8, pool.weight());
        assertEquals(8, pool.members().size());
    }
}
