package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StageStore#configDelta} must persist <b>only</b> the per-install changes (added / modified
 * vs the bundled defaults), never a full snapshot — otherwise a later build's updated bundled default
 * would be masked permanently by a stale config entry the player never touched.
 */
final class StageStoreDeltaTest {

    private static Stage stage(String id, TemplateGate gate) {
        return new Stage(id, id, gate);
    }

    @Test
    @DisplayName("delta omits untouched bundled defaults, keeps added + modified stages")
    void deltaKeepsOnlyChanges() {
        Map<String, Stage> bundled = new LinkedHashMap<>();
        bundled.put("nether", stage("nether", new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER))));
        bundled.put("stone", stage("stone", TemplateGate.DEFAULT));

        Map<String, Stage> current = new LinkedHashMap<>(bundled);
        // Modify 'stone', add a brand-new 'custom', leave 'nether' identical to bundled.
        current.put("stone", stage("stone", new TemplateGate(5, TemplateGate.ALL, EnumSet.of(TrainPhase.VOID))));
        current.put("custom", stage("custom", TemplateGate.DEFAULT));

        Map<String, Stage> delta = StageStore.configDelta(current, bundled);
        assertEquals(Set.of("stone", "custom"), delta.keySet(), "only modified + added persist to config");
        assertFalse(delta.containsKey("nether"), "an untouched bundled default must not be snapshotted into config");
    }

    @Test
    @DisplayName("current identical to bundled yields an empty delta (config stays clean)")
    void noChangesEmptyDelta() {
        Map<String, Stage> bundled = new LinkedHashMap<>();
        bundled.put("a", stage("a", TemplateGate.DEFAULT));
        Map<String, Stage> current = new LinkedHashMap<>(bundled);
        assertTrue(StageStore.configDelta(current, bundled).isEmpty(), "no edits => empty config delta");
    }
}
