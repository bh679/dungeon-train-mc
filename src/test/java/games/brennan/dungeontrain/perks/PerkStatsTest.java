package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerkStatsTest {

    @Test
    @DisplayName("a stat is worth per_point for each point")
    void parsesAndScales() {
        Map<String, List<PerkStats.Stat>> m = PerkStats.parse(new StringReader(
            "{\"_comment\":\"x\",\"throwables\":[{\"stat\":\"damage\",\"per_point\":1.5},{\"stat\":\"effect\",\"per_point\":2,\"unit\":\"seconds\"}]}"));
        List<PerkStats.Stat> s = m.get("throwables");
        assertEquals(2, s.size());
        assertEquals(4.5, s.get(0).at(3), 1e-9);
        assertEquals(0, s.get(1).at(0), 1e-9);
        assertEquals("seconds", s.get(1).unit());
    }

    @Test
    @DisplayName("unknown units and missing fields are refused")
    void rejectsBadStats() {
        assertThrows(IllegalArgumentException.class,
            () -> PerkStats.parse(new StringReader("{\"a\":[{\"stat\":\"damage\",\"per_point\":1,\"unit\":\"miles\"}]}")));
        assertThrows(IllegalArgumentException.class, () -> PerkStats.parse(new StringReader("{\"a\":[{\"stat\":\"damage\"}]}")));
    }

    @Test
    @DisplayName("the bundled stats name perks that are in the tree")
    void bundledStatsMatchTree() throws Exception {
        try (var in = PerkStats.class.getResourceAsStream(PerkStats.RESOURCE);
             var tin = PerkTreeLoader.class.getResourceAsStream(PerkTreeLoader.RESOURCE)) {
            Map<String, List<PerkStats.Stat>> m = PerkStats.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            PerkTree tree = PerkTreeLoader.parse(new InputStreamReader(tin, StandardCharsets.UTF_8));
            for (String id : m.keySet()) assertTrue(tree.perk(id).isPresent(), "stats for unknown perk " + id);
        }
    }
}
