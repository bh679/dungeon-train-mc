package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which worlds get natural mob spawning switched off.
 *
 * <p>Worth pinning because both ends of the rule depend on this one string and neither can check the
 * other: {@code DevQuickWorldHandler.launchEditorWorld} names the world, this decides on every later
 * start whether it is one. Drift between them is silent — editor worlds that quietly keep spawning
 * mobs, which then get saved into somebody's template.</p>
 */
final class EditorQuietRuleEventsTest {

    @Test
    @DisplayName("the worlds the Train Editor button creates are recognised")
    void recognisesEditorWorlds() {
        assertTrue(EditorQuietRuleEvents.isEditorWorldName("train editor 1"));
        assertTrue(EditorQuietRuleEvents.isEditorWorldName("train editor 12"));
        // The launcher builds every name as prefix + n, so matching the prefix is matching the set.
        assertTrue(EditorQuietRuleEvents.isEditorWorldName(
                EditorQuietRuleEvents.EDITOR_WORLD_PREFIX + "3"));
    }

    @Test
    @DisplayName("an ordinary world keeps its own spawning")
    void leavesOtherWorldsAlone() {
        // The one that matters: switching mob spawning off in somebody's survival save would be a
        // serious bug, so anything that is not an editor world has to read as false here.
        assertFalse(EditorQuietRuleEvents.isEditorWorldName("New World"));
        assertFalse(EditorQuietRuleEvents.isEditorWorldName("train builder 1"));
        assertFalse(EditorQuietRuleEvents.isEditorWorldName("my train editor world"));
        assertFalse(EditorQuietRuleEvents.isEditorWorldName(""));
        assertFalse(EditorQuietRuleEvents.isEditorWorldName(null));
    }
}
