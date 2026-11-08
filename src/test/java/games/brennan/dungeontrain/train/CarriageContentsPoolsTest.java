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
 * {@link CarriageContentsPools}: groups sharing a pool slot give each room the chance it had when all of them
 * were one group, at every level, because the slot splits by the weight each group can offer there.
 */
final class CarriageContentsPoolsTest {

    private static final int SAMPLES = 60_000;

    @BeforeEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
        CarriageContentsGroupStore.clearCache();
        CarriageContentsPools.clearCache();
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
    @DisplayName("pickMember draws by offered weight, and uniformly when nothing is offered")
    void pickMemberWeights() {
        Random rng = new Random(1);
        int[] hits = new int[3];
        for (int i = 0; i < 30_000; i++) hits[CarriageContentsPools.pickMember(new int[] {1, 0, 3}, rng)]++;
        assertEquals(0, hits[1]);
        assertEquals(0.75, hits[2] / 30_000.0, 0.02);
        assertTrue(CarriageContentsPools.pickMember(new int[] {0, 0}, rng) < 2);
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
    @DisplayName("the editor names a pooled group's shared slot, and the stored name comes back out")
    void editorNameRoundTrips() {
        String shown = CarriageContentsPools.editorName("enemies_creepers", "Creepers");
        assertEquals("Creepers (shares enemies slot, weight 8)", shown);
        assertEquals("Creepers", CarriageContentsPools.storedName("enemies_creepers", shown));
        assertEquals("Books", CarriageContentsPools.editorName("books", "Books"));
        assertEquals("Books", CarriageContentsPools.storedName("books", "Books"));
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
