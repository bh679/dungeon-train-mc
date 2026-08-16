package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry guard for the Open screen's mode strip.
 *
 * <p>At GUI scale 1 a 1080p window is ~1920×1080 GUI pixels; at scale 4 it is ~480×270, and
 * Minecraft's own floor is 320 wide. The strip is a fixed four tiles with one of them several times
 * the width of the others, so the interesting case is the narrow end — where it has to give way
 * without overlapping itself, running off the edge, or pushing the template grid down.</p>
 *
 * <p>The expanded slot moves with the selection, so every case below is swept across all four
 * selections as well as all six widths: it is the <em>first</em> and <em>last</em> slots being
 * expanded that push the row hardest against each edge.</p>
 */
final class BuilderModeStripLayoutTest {

    /** Where the strip starts on the real screen: under the title. */
    private static final int TOP = 27;

    /** {@code BuilderOpenScreen.CONTROL_WIDTH} and {@code BuilderTypeControls.ART_HEIGHT}. */
    private static final int SELECTED_WIDTH = 200;
    private static final int SELECTED_HEIGHT = 44;

    private static final int[] WIDTHS = {1920, 960, 854, 640, 480, 320};

    private static BuilderModeStripLayout at(int screenWidth, int selectedSlot) {
        return BuilderModeStripLayout.of(screenWidth, TOP, selectedSlot,
                SELECTED_WIDTH, SELECTED_HEIGHT);
    }

    @Test
    @DisplayName("Tiles never overlap, at any viewport width or selection")
    void tilesDoNotOverlap() {
        for (int width : WIDTHS) {
            for (int selected = 0; selected < BuilderModeStripLayout.SLOTS; selected++) {
                BuilderModeStripLayout layout = at(width, selected);
                String where = " at width " + width + " with slot " + selected + " selected";
                for (int slot = 0; slot + 1 < BuilderModeStripLayout.SLOTS; slot++) {
                    assertTrue(layout.xFor(slot) + layout.widthFor(slot) <= layout.xFor(slot + 1),
                            "slot " + slot + " overlaps its neighbour" + where);
                }
            }
        }
    }

    @Test
    @DisplayName("The strip stays on screen and is centred, wherever the selection sits")
    void stripIsCentredAndOnScreen() {
        for (int width : WIDTHS) {
            for (int selected = 0; selected < BuilderModeStripLayout.SLOTS; selected++) {
                BuilderModeStripLayout layout = at(width, selected);
                String where = " at width " + width + " with slot " + selected + " selected";
                int last = BuilderModeStripLayout.SLOTS - 1;

                assertTrue(layout.xFor(0) >= 0, "strip starts off the left edge" + where);
                assertTrue(layout.xFor(last) + layout.widthFor(last) <= width,
                        "strip runs off the right edge" + where);

                int leftMargin = layout.xFor(0);
                int rightMargin = width - (layout.xFor(last) + layout.widthFor(last));
                assertTrue(Math.abs(leftMargin - rightMargin) <= 1, "strip is off-centre" + where);

                // The walked positions and the declared extent must agree — two ways of saying how
                // wide the row is, and a centred strip is only centred if they do.
                assertEquals(layout.stripWidth(),
                        layout.xFor(last) + layout.widthFor(last) - layout.xFor(0),
                        "stripWidth disagrees with the laid-out tiles" + where);
            }
        }
    }

    @Test
    @DisplayName("The row holds still as the selection moves along it")
    void rowDoesNotJumpWhenTheSelectionMoves() {
        // One large tile and the rest small comes to the same total whichever one is large, so
        // clicking along the strip must not shift the row itself on the screen.
        for (int width : WIDTHS) {
            BuilderModeStripLayout first = at(width, 0);
            for (int selected = 1; selected < BuilderModeStripLayout.SLOTS; selected++) {
                BuilderModeStripLayout layout = at(width, selected);
                String where = " at width " + width + " with slot " + selected + " selected";
                assertEquals(first.originX(), layout.originX(), "the strip shifted" + where);
                assertEquals(first.stripWidth(), layout.stripWidth(), "the strip resized" + where);
            }
        }
    }

    @Test
    @DisplayName("Every tile has a positive size")
    void tilesAreNeverDegenerate() {
        // Below Minecraft's own 320 floor as well: the arithmetic subtracts before it clamps, and a
        // negative intermediate must not reach a width.
        for (int width : new int[]{1920, 480, 320, 200, 120}) {
            for (int selected = 0; selected < BuilderModeStripLayout.SLOTS; selected++) {
                BuilderModeStripLayout layout = at(width, selected);
                for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                    assertTrue(layout.widthFor(slot) > 0,
                            "slot " + slot + " has no width at " + width);
                    assertTrue(layout.heightFor(slot) > 0,
                            "slot " + slot + " has no height at " + width);
                }
            }
        }
    }

    @Test
    @DisplayName("The row's height never changes, so the grid below keeps its budget")
    void rowHeightIsFixed() {
        for (int width : WIDTHS) {
            for (int selected = 0; selected < BuilderModeStripLayout.SLOTS; selected++) {
                BuilderModeStripLayout layout = at(width, selected);
                assertEquals(SELECTED_HEIGHT, layout.height(),
                        "strip height moved at width " + width);
                for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                    assertTrue(layout.heightFor(slot) <= SELECTED_HEIGHT,
                            "slot " + slot + " is taller than the row at " + width);
                }
            }
        }
    }

    @Test
    @DisplayName("The selected slot is the one asked for, and it is the largest")
    void selectedSlotIsTheLargest() {
        for (int width : WIDTHS) {
            for (int selected = 0; selected < BuilderModeStripLayout.SLOTS; selected++) {
                BuilderModeStripLayout layout = at(width, selected);
                String where = " at width " + width + " with slot " + selected + " selected";
                int expanded = 0;
                for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                    if (layout.isSelected(slot)) {
                        expanded++;
                        continue;
                    }
                    assertTrue(layout.widthFor(slot) < layout.widthFor(selected),
                            "slot " + slot + " is not smaller than the selection" + where);
                    assertTrue(layout.heightFor(slot) < layout.heightFor(selected),
                            "slot " + slot + " is not shorter than the selection" + where);
                }
                assertEquals(1, expanded, "wrong number of expanded slots" + where);
                assertTrue(layout.isSelected(selected), "the wrong slot expanded" + where);
            }
        }
    }

    @Test
    @DisplayName("An out-of-range selection still expands a real slot")
    void outOfRangeSelectionIsClamped() {
        // Guards the "all thumbnails" failure — a wrong picture rather than a crash, and so the
        // harder kind to notice.
        for (int selected : new int[]{-1, BuilderModeStripLayout.SLOTS, 99}) {
            BuilderModeStripLayout layout = at(640, selected);
            int expanded = 0;
            for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                if (layout.isSelected(slot)) {
                    expanded++;
                }
            }
            assertEquals(1, expanded, "no single slot expanded for selection " + selected);
        }
    }

    @Test
    @DisplayName("The selected tile keeps its full width down to Minecraft's narrowest viewport")
    void selectedTileKeepsItsWidth() {
        // The thumbnails are what give way when the row is tight — the selected tile matching the
        // controls beneath it is the part worth keeping.
        for (int width : WIDTHS) {
            for (int selected = 0; selected < BuilderModeStripLayout.SLOTS; selected++) {
                assertEquals(SELECTED_WIDTH, at(width, selected).widthFor(selected),
                        "the selected tile shrank at width " + width);
            }
        }
    }

    @Test
    @DisplayName("Thumbnails ride the centre line of the selected tile")
    void thumbnailsAreVerticallyCentred() {
        for (int width : WIDTHS) {
            for (int selected = 0; selected < BuilderModeStripLayout.SLOTS; selected++) {
                BuilderModeStripLayout layout = at(width, selected);
                String where = " at width " + width + " with slot " + selected + " selected";
                assertEquals(TOP, layout.yFor(selected),
                        "the selected tile left the top of the row" + where);

                int rowCentre = TOP + SELECTED_HEIGHT / 2;
                for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                    int centre = layout.yFor(slot) + layout.heightFor(slot) / 2;
                    assertTrue(Math.abs(centre - rowCentre) <= 1,
                            "slot " + slot + " is off the centre line" + where);
                    assertTrue(layout.yFor(slot) >= TOP,
                            "slot " + slot + " sits above the row" + where);
                    assertTrue(layout.yFor(slot) + layout.heightFor(slot) <= TOP + SELECTED_HEIGHT,
                            "slot " + slot + " sits below the row" + where);
                }
            }
        }
    }

    @Test
    @DisplayName("Slots run left to right in menu order")
    void slotsAreInMenuOrder() {
        // The screen reads BuilderMode.values()[slot] straight off, so the only thing to hold is
        // that the row is laid out in ascending slot order — a mode stays where you last saw it.
        BuilderModeStripLayout layout = at(640, 2);
        for (int slot = 0; slot + 1 < BuilderModeStripLayout.SLOTS; slot++) {
            assertTrue(layout.xFor(slot) < layout.xFor(slot + 1),
                    "slot " + (slot + 1) + " does not sit right of slot " + slot);
        }
    }
}
