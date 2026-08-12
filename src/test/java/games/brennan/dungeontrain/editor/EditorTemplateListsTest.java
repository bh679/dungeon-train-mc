package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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

    // ---- portal rooms: the mode is the heading, and it is not the whole stored tag ----

    /** The mode tags as they actually appear in {@code portals/room/weights.json}. */
    private static final Map<String, String> ROOM_MODES = Map.ofEntries(
            Map.entry("default", "bedrock_lock"),
            Map.entry("pathsmol", "bedrock_lock"),
            Map.entry("beam", "bedrockless"),
            Map.entry("window_contents", "bedrockless/exact/fit"),
            Map.entry("singlepillar", "endless_open"),
            Map.entry("cubes", "endless_repetition"),
            Map.entry("labrynth", "endless_repetition/dynamic"));

    @Test
    @DisplayName("Each mode gets the rooms authored for it, and nothing else")
    void roomsBucketByMode() {
        assertEquals(List.of("default", "pathsmol"), roomsOf(PortalRoomMode.BEDROCK_LOCK));
        assertEquals(List.of("beam", "window_contents"), roomsOf(PortalRoomMode.BEDROCKLESS));
        assertEquals(List.of("singlepillar"), roomsOf(PortalRoomMode.ENDLESS_OPEN));
        assertEquals(List.of("cubes", "labrynth"), roomsOf(PortalRoomMode.ENDLESS_REPETITION));
    }

    @Test
    @DisplayName("A tag carrying its settings still files under its mode, not the default")
    void settingsSegmentsDoNotChangeTheMode() {
        // The trap this test exists for: the stored tag is "endless_repetition/dynamic", so reading
        // it with PortalRoomMode.parse would match no id and quietly return BEDROCK_LOCK — filing
        // every settings-carrying room under Bedrock. Roughly half the shipped library has one.
        assertEquals(List.of("labrynth"),
                EditorTemplateLists.filterByMode(List.of("labrynth"),
                        ROOM_MODES::get, PortalRoomMode.ENDLESS_REPETITION));
        assertEquals(List.of(),
                EditorTemplateLists.filterByMode(List.of("labrynth"),
                        ROOM_MODES::get, PortalRoomMode.BEDROCK_LOCK));
    }

    @Test
    @DisplayName("A room with no mode tag reads as the default one rather than vanishing")
    void untaggedRoomsFallBackToTheDefault() {
        // Every room must appear under exactly one heading — a null tag dropping out of all four
        // would hide the template from the screen entirely.
        assertEquals(List.of("untagged"),
                EditorTemplateLists.filterByMode(List.of("untagged"),
                        name -> null, PortalRoomMode.DEFAULT));
    }

    @Test
    @DisplayName("No names, or no mode, lists nothing rather than throwing")
    void emptyInputsAreTolerated() {
        assertEquals(List.of(), EditorTemplateLists.filterByMode(List.of(), ROOM_MODES::get,
                PortalRoomMode.BEDROCK_LOCK));
        assertEquals(List.of(), EditorTemplateLists.filterByMode(List.of("default"), ROOM_MODES::get, null));
        assertEquals(List.of(), EditorTemplateLists.filterByMode(null, ROOM_MODES::get,
                PortalRoomMode.BEDROCK_LOCK));
    }

    private static List<String> roomsOf(PortalRoomMode mode) {
        return EditorTemplateLists.filterByMode(
                ROOM_MODES.keySet().stream().sorted().toList(), ROOM_MODES::get, mode);
    }

    private static Stage stage(String id, int minLevel) {
        return new Stage(id, id, new TemplateGate(minLevel, TemplateGate.ALL, Set.of(TrainPhase.values())));
    }
}
