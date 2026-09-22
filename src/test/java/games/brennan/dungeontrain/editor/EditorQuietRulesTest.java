package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.GameRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * What {@link EditorQuietRules#apply} actually switches off.
 *
 * <p>Weather and random ticks are the two worth pinning: an editor plot is skylit, so rain would
 * fall across a build that has no sky of its own to explain it. Random ticks matter more still:
 * with them on, grass an author built over turns to dirt on its own, the dirty scan reports the
 * plot as unsaved, and a save bakes the decay in.</p>
 */
final class EditorQuietRulesTest {

    @Test
    @DisplayName("an editor world sits under no spawning, a stopped clock, no weather or random ticks")
    void quietRulesApplied() {
        GameRules rules = new GameRules();
        EditorQuietRules.apply(rules, null);
        assertFalse(rules.getBoolean(GameRules.RULE_DOMOBSPAWNING));
        assertFalse(rules.getBoolean(GameRules.RULE_DAYLIGHT));
        assertFalse(rules.getBoolean(GameRules.RULE_WEATHER_CYCLE));
        assertEquals(0, rules.getInt(GameRules.RULE_RANDOMTICKING));
        assertEquals(4, EditorQuietRules.RULE_COUNT);
    }
}
