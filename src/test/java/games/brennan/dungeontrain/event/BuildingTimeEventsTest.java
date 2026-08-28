package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.editor.EditorLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which counter a player's seconds feed — the one decision in {@link BuildingTimeEvents} that can be
 * wrong without anything failing, since a mis-attributed tick still lands on a real counter.
 */
class BuildingTimeEventsTest {

    @Test
    @DisplayName("a builder world claims the time at any height")
    void builderWorldWins() {
        assertEquals(BuildingTimeEvents.Target.BUILDER, BuildingTimeEvents.targetFor(true, 64));
        assertEquals(BuildingTimeEvents.Target.BUILDER,
                BuildingTimeEvents.targetFor(true, EditorLayout.PLOT_Y));
        assertEquals(BuildingTimeEvents.Target.BUILDER, BuildingTimeEvents.targetFor(true, -60));
    }

    @Test
    @DisplayName("in an ordinary world only the sky editor counts, and the plot floor is inside it")
    void ordinaryWorldNeedsEditorHeight() {
        assertEquals(BuildingTimeEvents.Target.EDITOR,
                BuildingTimeEvents.targetFor(false, EditorLayout.PLOT_Y));
        // Standing ON the plot floor is a few blocks below its origin — still the editor.
        assertEquals(BuildingTimeEvents.Target.EDITOR,
                BuildingTimeEvents.targetFor(false, EditorLayout.PLOT_Y - 5));
        assertEquals(BuildingTimeEvents.Target.NONE,
                BuildingTimeEvents.targetFor(false, EditorLayout.PLOT_Y - 6));
    }

    @Test
    @DisplayName("riding the train is not building")
    void ridingIsNotBuilding() {
        assertEquals(BuildingTimeEvents.Target.NONE, BuildingTimeEvents.targetFor(false, 78));
        assertEquals(BuildingTimeEvents.Target.NONE, BuildingTimeEvents.targetFor(false, 0));
    }
}
