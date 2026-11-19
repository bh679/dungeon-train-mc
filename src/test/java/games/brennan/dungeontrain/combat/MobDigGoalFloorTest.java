package games.brennan.dungeontrain.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MobDigGoal#isFloor}: floor is judged by height, so the carpet, slab or path a mob
 * stands on (same block row as its feet) is floor, while a wall it cannot step over is not.
 */
final class MobDigGoalFloorTest {

    private static final double STEP = 0.6;

    @Test
    @DisplayName("the carpet next to a mob standing on carpet is floor")
    void carpetUnderfoot() {
        assertTrue(MobDigGoal.isFloor(201.0625, 201.0625, STEP));
    }

    @Test
    @DisplayName("the slab next to a mob standing on bottom slabs is floor")
    void slabUnderfoot() {
        assertTrue(MobDigGoal.isFloor(201.5, 201.5, STEP));
    }

    @Test
    @DisplayName("a bottom slab in front of a mob on full blocks can be stepped over: floor")
    void slabStepUp() {
        assertTrue(MobDigGoal.isFloor(201.5, 201.0, STEP));
    }

    @Test
    @DisplayName("a deck read a hair above the feet (a carriage at a fractional height) is floor")
    void deckJustAboveFeet() {
        assertTrue(MobDigGoal.isFloor(79.0, 78.9999, STEP));
    }

    @Test
    @DisplayName("a full block at feet height is a wall, not floor")
    void fullBlockAtFeet() {
        assertFalse(MobDigGoal.isFloor(202.0, 201.0, STEP));
    }

    @Test
    @DisplayName("a full block at feet height is still a wall to a mob standing on carpet")
    void fullBlockFromCarpet() {
        assertFalse(MobDigGoal.isFloor(202.0, 201.0625, STEP));
    }
}
