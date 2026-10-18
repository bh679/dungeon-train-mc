package games.brennan.dungeontrain.editor;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Touching conditions take their sides from the neighbouring variant cells: an unset condition's
 * first value, and a switch from Group to Touching, light every side with a variant cell. Sides
 * the author picked by hand, and conditions that already have values, are left alone.
 */
final class VariantConditionSidesTest {

    private static final int UP_DOWN = VariantCondition.bit(Direction.UP) | VariantCondition.bit(Direction.DOWN);
    private static final ResourceLocation LOG = ResourceLocation.withDefaultNamespace("pale_oak_log");

    @Test
    void firstValueTakesTheVariantSides() {
        assertEquals(UP_DOWN, VariantConditionEdits.withDefaultSides(VariantCondition.NONE, UP_DOWN).sideMask());
    }

    @Test
    void handPickedSidesStay() {
        VariantCondition north = VariantCondition.NONE.withDirection(Direction.NORTH);
        assertEquals(north, VariantConditionEdits.withDefaultSides(north, UP_DOWN));
    }

    @Test
    void conditionWithValuesIsUnchanged() {
        VariantCondition set = VariantCondition.NONE.withValueAdded(LOG);
        assertEquals(set, VariantConditionEdits.withDefaultSides(set, UP_DOWN));
    }

    @Test
    void noVariantNeighboursKeepsTheDefault() {
        assertEquals(VariantCondition.NONE, VariantConditionEdits.withDefaultSides(VariantCondition.NONE, 0));
    }

    @Test
    void groupToTouchingLightsTheVariantSides() {
        VariantCondition group = VariantCondition.NONE.withGroup(2).withValueAdded(LOG);
        VariantCondition touching = VariantConditionEdits.setTarget(group, "down", Set.of(2), 0, UP_DOWN).condition();
        assertEquals(UP_DOWN, touching.sideMask());
        assertEquals(group.blocks(), touching.blocks());
    }

    @Test
    void groupToTouchingWithNoVariantNeighboursUsesTheDefaultSide() {
        VariantCondition group = VariantCondition.NONE.withGroup(2);
        VariantCondition touching = VariantConditionEdits.setTarget(group, "down", Set.of(2), 0, 0).condition();
        assertEquals(VariantCondition.bit(Direction.DOWN), touching.sideMask());
    }
}
