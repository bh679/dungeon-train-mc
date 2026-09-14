package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static games.brennan.dungeontrain.player.EnderChestLayout.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnderChestLayoutTest {

    @Test
    void expandedIsFiveNineFiveByThreePlusThree() {
        assertEquals(19, EXPANDED_COLUMNS);
        assertEquals(6, EXPANDED_ROWS);
        assertEquals(114, EXPANDED_SLOTS);
        assertEquals(27, VANILLA_SLOTS);
        assertEquals(EXPANDED_SLOTS, UPPER_CENTRE_SLOTS + VANILLA_SLOTS + 2 * WING_SLOTS);
    }

    @Test
    void plainChestMatchesVanillaChestMenuPositions() {
        for (int i = 0; i < VANILLA_SLOTS; i++) {
            assertEquals(8 + (i % 9) * 18, slotX(i, false));
            assertEquals(18 + (i / 9) * 18, slotY(i, false));
        }
    }

    @Test
    void vanillaSlotsKeepTheirColumnAndDropThreeRowsWhenExpanded() {
        for (int i = 0; i < VANILLA_SLOTS; i++) {
            assertEquals(slotX(i, false), slotX(i, true));
            assertEquals(slotY(i, false) + 3 * 18, slotY(i, true));
        }
    }

    @Test
    void upperCentreRowsSitDirectlyAboveTheVanillaBlock() {
        assertEquals(8, slotX(UPPER_CENTRE_START, true));
        assertEquals(18, slotY(UPPER_CENTRE_START, true));
        // Last upper-centre slot is the row just above vanilla row 0.
        int last = LEFT_WING_START - 1;
        assertEquals(8 + 8 * 18, slotX(last, true));
        assertEquals(slotY(0, true) - 18, slotY(last, true));
    }

    @Test
    void wingsFillDownThenOutwardAndSpanAllSixRows() {
        // Left wing: innermost column first, going down, then the next column outward.
        assertEquals(LEFT_INNER_X, slotX(LEFT_WING_START, true));
        assertEquals(18, slotY(LEFT_WING_START, true));
        assertEquals(LEFT_INNER_X, slotX(LEFT_WING_START + 5, true));
        assertEquals(18 + 5 * 18, slotY(LEFT_WING_START + 5, true));
        assertEquals(LEFT_INNER_X - 18, slotX(LEFT_WING_START + 6, true));
        assertEquals(LEFT_INNER_X - 4 * 18, slotX(RIGHT_WING_START - 1, true));
        // Right wing mirrors it outward.
        assertEquals(RIGHT_INNER_X, slotX(RIGHT_WING_START, true));
        assertEquals(RIGHT_INNER_X + 4 * 18, slotX(EXPANDED_SLOTS - 1, true));
        assertEquals(18 + 5 * 18, slotY(EXPANDED_SLOTS - 1, true));
    }

    @Test
    void everyExpandedSlotHasAUniquePositionOnAnEighteenPixelGrid() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < EXPANDED_SLOTS; i++) {
            int x = slotX(i, true);
            int y = slotY(i, true);
            // Centre block on vanilla's grid; each wing on its own, 8px clear of the window (like EB's panels).
            int gridX = switch (regionOf(i)) {
                case VANILLA, UPPER_CENTRE -> 8;
                case LEFT_WING -> LEFT_INNER_X;
                case RIGHT_WING -> RIGHT_INNER_X;
            };
            assertEquals(0, Math.floorMod(x - gridX, 18), "x off-grid at " + i);
            assertEquals(0, Math.floorMod(y - 18, 18), "y off-grid at " + i);
            assertTrue(seen.add(((long) x << 32) | (y & 0xffffffffL)), "duplicate position at " + i);
        }
        assertEquals(EXPANDED_SLOTS, seen.size());
    }

    @Test
    void wingsStayOutsideTheWindow() {
        int[] left = wingBounds(false);
        int[] right = wingBounds(true);
        assertTrue(left[2] <= 0, "left wing overlaps the window");
        assertTrue(right[0] >= GUI_WIDTH, "right wing overlaps the window");
        assertEquals(left[1], right[1]);
        assertEquals(left[3], right[3]);
        assertEquals(EXPANDED_ROWS * 18 + 2 * BORDER, left[3] - left[1]);
        assertEquals(WING_COLUMNS * 18 + 2 * BORDER, left[2] - left[0]);
        assertEquals(WING_COLUMNS * 18 + 2 * BORDER, right[2] - right[0]);
    }

    @Test
    void extraSlotsDoNotExistInThePlainChest() {
        assertFalse(isExtra(26));
        assertTrue(isExtra(27));
        assertThrows(IllegalArgumentException.class, () -> slotX(27, false));
        assertThrows(IllegalArgumentException.class, () -> slotY(113, false));
        assertThrows(IllegalArgumentException.class, () -> regionOf(114));
    }
}
