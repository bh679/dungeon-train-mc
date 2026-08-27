package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry for the rebuilt builder pause menu.
 *
 * <p>Vanilla's grid is gone by the time the mod gets the screen, so every coordinate here is
 * computed rather than laid out. A wrong half-width leaves a visible gutter or an overlap on
 * every row at once, which is exactly the sort of thing worth pinning.</p>
 */
final class BuilderPauseMenuLayoutTest {

    /** Vanilla's full-width pause button ({@code PauseScreen.BUTTON_WIDTH_FULL}). */
    private static final int SLOT_W = 204;
    private static final int SLOT_H = 20;

    @Test
    @DisplayName("The two halves exactly span the slot, gap included")
    void halvesSpanTheSlot() {
        int left = BuilderPauseMenuLayout.leftHalfWidth(SLOT_W);
        int right = BuilderPauseMenuLayout.rightHalfWidth(SLOT_W);
        assertEquals(SLOT_W, left + BuilderPauseMenuLayout.INNER_GAP + right);
        assertEquals(98, left, "matches vanilla's BUTTON_WIDTH_HALF for a 204 slot");
        assertEquals(98, right);
    }

    @Test
    @DisplayName("An odd slot width puts the spare pixel in the right half, never in a gutter")
    void oddWidthLeavesNoGutter() {
        for (int w : new int[] {199, 200, 201, 203, 205}) {
            int left = BuilderPauseMenuLayout.leftHalfWidth(w);
            int right = BuilderPauseMenuLayout.rightHalfWidth(w);
            assertEquals(w, left + BuilderPauseMenuLayout.INNER_GAP + right, "slot width " + w);
            assertTrue(right >= left, "the spare pixel belongs to the right half at width " + w);
        }
    }

    @Test
    @DisplayName("The right half starts after the left half plus the gap")
    void rightHalfStartsAfterTheGap() {
        int slotX = -102;
        assertEquals(slotX + 98 + BuilderPauseMenuLayout.INNER_GAP,
                BuilderPauseMenuLayout.rightHalfX(slotX, SLOT_W));
    }

    @Test
    @DisplayName("Rows step by button height plus gap, starting at the Back-to-Game row")
    void rowsStepUniformly() {
        int firstY = 60;
        assertEquals(firstY, BuilderPauseMenuLayout.rowY(firstY, SLOT_H, 0));
        assertEquals(firstY + 24, BuilderPauseMenuLayout.rowY(firstY, SLOT_H, 1));
        assertEquals(firstY + 120, BuilderPauseMenuLayout.rowY(firstY, SLOT_H, 5));
        // No row may overlap the one above it.
        for (int i = 1; i <= 5; i++) {
            int prevBottom = BuilderPauseMenuLayout.rowY(firstY, SLOT_H, i - 1) + SLOT_H;
            assertTrue(BuilderPauseMenuLayout.rowY(firstY, SLOT_H, i) >= prevBottom, "row " + i);
        }
    }

    @Test
    @DisplayName("Exit Game is a dev affordance revealed by Shift, never present on a release build")
    void exitGameRevealRule() {
        assertTrue(BuilderPauseMenuLayout.shouldRevealExitGame("dev/train-builder-mode", true));
        assertFalse(BuilderPauseMenuLayout.shouldRevealExitGame("dev/train-builder-mode", false));
        assertFalse(BuilderPauseMenuLayout.shouldRevealExitGame("main", true));
        assertTrue(BuilderPauseMenuLayout.shouldRevealExitGame(null, true),
                "unknown branch fails open, like the other dev affordances");
    }

    // ---- side panel ----

    @Test
    @DisplayName("The menu and its panel are positioned as one centred block")
    void combinedBlockIsCentred() {
        int screenW = 854;
        int mainX = BuilderPauseMenuLayout.mainColumnX(screenW, SLOT_W, 0);
        int panelX = BuilderPauseMenuLayout.panelX(screenW, SLOT_W, 0);
        int right = panelX + BuilderPauseMenuLayout.panelWidth(SLOT_W);
        assertEquals(mainX, screenW - right, "equal slack either side of the whole block");
        assertTrue(mainX >= 0);
    }

    @Test
    @DisplayName("The panel sits one inner gap right of the main column")
    void panelFollowsTheMainColumn() {
        int screenW = 854;
        assertEquals(
                BuilderPauseMenuLayout.mainColumnX(screenW, SLOT_W, 0) + SLOT_W + BuilderPauseMenuLayout.INNER_GAP,
                BuilderPauseMenuLayout.panelX(screenW, SLOT_W, 0));
    }

    @Test
    @DisplayName("A viewport too narrow for the panel drops it and leaves the menu where vanilla put it")
    void narrowViewportKeepsVanillaPosition() {
        int needed = BuilderPauseMenuLayout.combinedWidth(SLOT_W);
        assertEquals(SLOT_W + BuilderPauseMenuLayout.INNER_GAP + 98, needed);

        assertFalse(BuilderPauseMenuLayout.fitsSidePanel(needed - 1, SLOT_W));
        assertTrue(BuilderPauseMenuLayout.fitsSidePanel(needed, SLOT_W));

        int vanillaX = 42;
        assertEquals(vanillaX, BuilderPauseMenuLayout.mainColumnX(needed - 1, SLOT_W, vanillaX),
                "a missing panel must not move the menu");
    }

    @Test
    @DisplayName("The panel is exactly one half-slot wide, so it lines up with the menu's halves")
    void panelIsHalfSlotWide() {
        assertEquals(BuilderPauseMenuLayout.leftHalfWidth(SLOT_W), BuilderPauseMenuLayout.panelWidth(SLOT_W));
    }

    // ---- mirror row ----

    @Test
    @DisplayName("Three cells and four cells each span the panel exactly")
    void mirrorRowsSpanThePanel() {
        int panel = BuilderPauseMenuLayout.panelWidth(SLOT_W);   // 98
        for (int count : new int[] {3, 4}) {
            int last = count - 1;
            int right = BuilderPauseMenuLayout.mirrorCellX(0, panel, count, last)
                    + BuilderPauseMenuLayout.mirrorCellWidthAt(panel, count, last);
            assertEquals(panel, right, count + " cells must reach the panel edge");
        }
    }

    @Test
    @DisplayName("Mirror cells never overlap")
    void mirrorCellsDoNotOverlap() {
        int panel = BuilderPauseMenuLayout.panelWidth(SLOT_W);
        for (int count : new int[] {3, 4}) {
            for (int i = 1; i < count; i++) {
                int prevRight = BuilderPauseMenuLayout.mirrorCellX(0, panel, count, i - 1)
                        + BuilderPauseMenuLayout.mirrorCellWidthAt(panel, count, i - 1);
                assertTrue(BuilderPauseMenuLayout.mirrorCellX(0, panel, count, i) >= prevRight,
                        "cell " + i + " of " + count);
            }
        }
    }

    @Test
    @DisplayName("Adding V narrows the other cells rather than pushing them off the panel")
    void fourCellsAreNarrowerThanThree() {
        int panel = BuilderPauseMenuLayout.panelWidth(SLOT_W);
        assertTrue(BuilderPauseMenuLayout.mirrorCellWidth(panel, 4)
                < BuilderPauseMenuLayout.mirrorCellWidth(panel, 3));
    }
}
