package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RandomTotemTable} — the weights file, and the weighted pick the random totem uses. */
final class RandomTotemTableTest {

    private static ResourceLocation rl(String id) {
        return ResourceLocation.parse(id);
    }

    @Test
    @DisplayName("the bundled weights list all 14 Balanced Totems, each with a positive weight")
    void bundledTableCoversEveryTotem() {
        Map<ResourceLocation, Integer> all = RandomTotemTable.all();
        assertEquals(14, all.size(), "one weight per Balanced Totem");
        all.forEach((id, w) -> {
            assertEquals("balancedtotems", id.getNamespace(), id + " is not a Balanced Totem");
            assertTrue(w > 0, id + " has no weight");
        });
    }

    @Test
    @DisplayName("bad entries are dropped one by one; the rest of the table survives")
    void dropsBadEntries() {
        Map<ResourceLocation, Integer> t = RandomTotemTable.parse(
            "{\"weights\":{\"void_totem\":5,\"balancedtotems:a\":0,\"balancedtotems:b\":1.5,"
                + "\"balancedtotems:c\":\"3\",\"balancedtotems:d\":4}}");
        assertEquals(Map.of(rl("balancedtotems:d"), 4), t);
        assertEquals(Map.of(), RandomTotemTable.parse("not json"));
    }

    @Test
    @DisplayName("picks follow the weights, and the same seed always picks the same totem")
    void picksByWeight() {
        Map<ResourceLocation, Integer> t = new LinkedHashMap<>();
        t.put(rl("balancedtotems:common"), 3);
        t.put(rl("balancedtotems:rare"), 1);
        Map<ResourceLocation, Integer> seen = new HashMap<>();
        for (long seed = 0; seed < 4000; seed++) {
            seen.merge(RandomTotemTable.pick(t, seed, id -> true).orElseThrow(), 1, Integer::sum);
        }
        assertEquals(3000, seen.get(rl("balancedtotems:common")));
        assertEquals(1000, seen.get(rl("balancedtotems:rare")));
        assertEquals(RandomTotemTable.pick(t, -77L, id -> true), RandomTotemTable.pick(t, -77L, id -> true));
    }

    @Test
    @DisplayName("before the void the Void Totem never rolls, and the rest keep their ratios")
    void voidTotemWaitsForTheVoid() {
        Map<ResourceLocation, Integer> t = new LinkedHashMap<>();
        t.put(RandomTotemTable.VOID_TOTEM, 10);
        t.put(rl("balancedtotems:common"), 3);
        t.put(rl("balancedtotems:rare"), 1);
        Map<ResourceLocation, Integer> before = new HashMap<>();
        Map<ResourceLocation, Integer> after = new HashMap<>();
        for (long seed = 0; seed < 1400; seed++) {
            before.merge(RandomTotemTable.pick(t, seed, RandomTotemTable.eligible(id -> true, false)).orElseThrow(), 1, Integer::sum);
            after.merge(RandomTotemTable.pick(t, seed, RandomTotemTable.eligible(id -> true, true)).orElseThrow(), 1, Integer::sum);
        }
        assertEquals(null, before.get(RandomTotemTable.VOID_TOTEM));
        assertEquals(3 * before.get(rl("balancedtotems:rare")), before.get(rl("balancedtotems:common")));
        assertEquals(1000, after.get(RandomTotemTable.VOID_TOTEM));
    }

    @Test
    @DisplayName("the bundled weights: Weakness and Fatigue rarest at 5, Void rolls at 10")
    void bundledWeights() {
        assertEquals(5, RandomTotemTable.minWeight());
        assertEquals(5, RandomTotemTable.weightOf(rl("balancedtotems:weakness_totem")));
        assertEquals(8, RandomTotemTable.weightOf(rl("balancedtotems:rampage_totem")));
        assertEquals(15, RandomTotemTable.weightOf(rl("balancedtotems:pacifism_totem")));
        assertEquals(10, RandomTotemTable.weightOf(RandomTotemTable.VOID_TOTEM));
        assertEquals(143, RandomTotemTable.all().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("unregistered ids are skipped; none registered answers empty (vanilla fallback)")
    void skipsUnregistered() {
        Map<ResourceLocation, Integer> t = new LinkedHashMap<>();
        t.put(rl("balancedtotems:gone"), 100);
        t.put(rl("balancedtotems:here"), 1);
        for (long seed = 0; seed < 50; seed++) {
            assertEquals(Optional.of(rl("balancedtotems:here")),
                RandomTotemTable.pick(t, seed, id -> id.getPath().equals("here")));
        }
        assertEquals(Optional.empty(), RandomTotemTable.pick(t, 1L, id -> false));
    }
}
