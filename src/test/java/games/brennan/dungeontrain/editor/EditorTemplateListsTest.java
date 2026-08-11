package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The listing rules the Train Editor's template list and the Train Builder's New picker now share.
 *
 * <p>These are the two places that used to disagree, so the rules are pinned here rather than in
 * either caller — a change that only reaches one of them should fail here first.</p>
 */
final class EditorTemplateListsTest {

    // ---- contents: group members belong under their parent, not beside it ----

    @Test
    @DisplayName("Group children are dropped from the top-level list")
    void childrenAreFiltered() {
        List<String> all = List.of("maze", "copper", "stone", "library");
        assertEquals(List.of("maze", "library"),
                EditorTemplateLists.topLevelContents(all, Set.of("copper", "stone")));
    }

    @Test
    @DisplayName("Registry order is preserved — the list isn't re-sorted on the way through")
    void orderIsPreserved() {
        List<String> all = List.of("zed", "alpha", "mid");
        assertEquals(all, EditorTemplateLists.topLevelContents(all, Set.of()));
    }

    @Test
    @DisplayName("No groups at all means nothing is filtered")
    void noGroupsKeepsEverything() {
        List<String> all = List.of("a", "b");
        assertEquals(all, EditorTemplateLists.topLevelContents(all, Set.of()));
        assertEquals(all, EditorTemplateLists.topLevelContents(all, null),
                "a store that hasn't loaded yet must not empty the list");
    }

    // ---- stages: the first entry is a picker's default, so the order is a decision ----

    @Test
    @DisplayName("Stages run earliest band first")
    void stagesOrderByBandStart() {
        List<Stage> stages = List.of(
                stage("nether", 60),
                stage("stone", 0),
                stage("desert", 11));
        assertEquals(List.of("stone", "desert", "nether"), EditorTemplateLists.orderedStages(stages));
    }

    @Test
    @DisplayName("Same band start falls back to the id, so the order is stable across runs")
    void tiesBreakOnId() {
        List<Stage> stages = List.of(stage("beta", 20), stage("alpha", 20));
        assertEquals(List.of("alpha", "beta"), EditorTemplateLists.orderedStages(stages));
    }

    @Test
    @DisplayName("A stage with no gate sorts as the default band rather than throwing")
    void nullGateIsTolerated() {
        List<Stage> stages = List.of(new Stage("ungated", "ungated", null), stage("late", 200));
        assertEquals(List.of("ungated", "late"), EditorTemplateLists.orderedStages(stages));
    }

    private static Stage stage(String id, int minLevel) {
        return new Stage(id, id, new TemplateGate(minLevel, TemplateGate.ALL, Set.of(TrainPhase.values())));
    }
}
