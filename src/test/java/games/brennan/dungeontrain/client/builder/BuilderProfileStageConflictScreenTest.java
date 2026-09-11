package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diff classification behind the conflict screen's tinting — which of a row's three lines
 * light up — and the keys it draws with (asserted as keys: without a resource pack a translatable
 * renders as its key, so English text is never what is under test here).
 */
final class BuilderProfileStageConflictScreenTest {

    private static final Stage SWAMP = new Stage("swamp", "swamp",
            new TemplateGate(20, 40, EnumSet.of(TrainPhase.OVERWORLD, TrainPhase.VOID)));

    private static String json(Stage s) {
        return StageStore.toJsonText(s);
    }

    @Test
    @DisplayName("identical sides change nothing")
    void identicalIsClean() {
        assertTrue(BuilderProfileStageConflictScreen.changedFields("swamp", json(SWAMP), json(SWAMP)).isEmpty());
    }

    @Test
    @DisplayName("each line lights up on its own field: name, levels, phases")
    void eachFieldSeparately() {
        assertEquals(Set.of("name"), BuilderProfileStageConflictScreen.changedFields("swamp",
                json(SWAMP), json(SWAMP.withName("Marsh"))));
        assertEquals(Set.of("levels"), BuilderProfileStageConflictScreen.changedFields("swamp",
                json(SWAMP), json(SWAMP.withGate(new TemplateGate(25, 40, SWAMP.gate().phases())))));
        assertEquals(Set.of("phases"), BuilderProfileStageConflictScreen.changedFields("swamp",
                json(SWAMP), json(SWAMP.withGate(new TemplateGate(20, 40, EnumSet.of(TrainPhase.NETHER))))));
    }

    @Test
    @DisplayName("an open-ended band and the full phase set read as their 'all' keys, not as numbers")
    void allKeys() {
        BuilderProfileStageConflictScreen.Summary s = BuilderProfileStageConflictScreen.summarise("stone",
                json(new Stage("stone", "stone", TemplateGate.DEFAULT)));
        assertEquals(BuilderProfileStageConflictScreen.KEY_ALL_PHASES, s.phasesLine().getString());
        assertEquals(BuilderProfileStageConflictScreen.KEY_LEVELS, s.levelsLine().getString(),
                "the band renders through its key (its args only show once a language is loaded)");
        assertEquals(TemplateGate.ALL, s.maxLevel());
    }

    @Test
    @DisplayName("unreadable text gives three 'could not show' lines rather than throwing mid-render")
    void unreadableIsSafe() {
        BuilderProfileStageConflictScreen.Summary s = BuilderProfileStageConflictScreen.summarise("x", "{");
        assertEquals(BuilderProfileStageConflictScreen.KEY_UNREADABLE, s.nameLine().getString());
        assertEquals(BuilderProfileStageConflictScreen.KEY_UNREADABLE, s.levelsLine().getString());
        assertEquals(BuilderProfileStageConflictScreen.KEY_UNREADABLE, s.phasesLine().getString());
        assertTrue(BuilderProfileStageConflictScreen.changedFields("x", "{", "{").isEmpty(),
                "two unreadable sides have nothing to tint");
        // And a readable side against an unreadable one differs on every line.
        assertEquals(Set.of("name", "levels", "phases"),
                BuilderProfileStageConflictScreen.changedFields("swamp", json(SWAMP), "{"));
    }
}
