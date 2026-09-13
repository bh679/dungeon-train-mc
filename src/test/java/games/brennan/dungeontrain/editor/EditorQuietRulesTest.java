package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.GameRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What {@link EditorQuietRules#apply} actually switches off.
 *
 * <p>Random ticks are the one worth pinning: with them on, grass an author built over turns to
 * dirt on its own, the dirty scan reports the plot as unsaved, and a save bakes the decay in.</p>
 */
final class EditorQuietRulesTest {

    @Test
    @DisplayName("an editor world sits under no spawning, a stopped clock and no random ticks")
    void quietRulesApplied() {
        GameRules rules = new GameRules();
        EditorQuietRules.apply(rules, null);
        assertFalse(rules.getBoolean(GameRules.RULE_DOMOBSPAWNING));
        assertFalse(rules.getBoolean(GameRules.RULE_DAYLIGHT));
        assertEquals(0, rules.getInt(GameRules.RULE_RANDOMTICKING));
        assertEquals(3, EditorQuietRules.RULE_COUNT);
    }
}
