package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry guard for the Open screen's mode strip.
 *
 * <p>At GUI scale 1 a 1080p window is ~1920×1080 GUI pixels; at scale 4 it is ~480×270, and
 * Minecraft's own floor is 320 wide. The strip is a fixed four tiles with one of them several times
 * the width of the others, so the interesting case is the narrow end — where it has to give way
 * without overlapping itself, running off the edge, or pushing the template grid down.</p>
 */
final class BuilderModeStripLayoutTest {

    /** Where the strip starts on the real screen: under the title. */
    private static final int TOP = 27;

    /** {@code BuilderOpenScreen.CONTROL_WIDTH} and {@code BuilderTypeControls.ART_HEIGHT}. */
    private static final int SELECTED_WIDTH = 200;
    private static final int SELECTED_HEIGHT = 44;

    private static final int[] WIDTHS = {1920, 960, 854, 640, 480, 320};

    private static BuilderModeStripLayout at(int screenWidth) {
        return BuilderModeStripLayout.of(screenWidth, TOP, SELECTED_WIDTH, SELECTED_HEIGHT);
    }

    @Test
    @DisplayName("Tiles never overlap, at any viewport width")
    void tilesDoNotOverlap() {
        for (int width : WIDTHS) {
            BuilderModeStripLayout layout = at(width);
            String where = " at width " + width;
            for (int slot = 0; slot + 1 < BuilderModeStripLayout.SLOTS; slot++) {
                assertTrue(layout.xFor(slot) + layout.widthFor(slot) <= layout.xFor(slot + 1),
                        "slot " + slot + " overlaps its neighbour" + where);
            }
        }
    }

    @Test
    @DisplayName("The strip stays on screen and is centred")
    void stripIsCentredAndOnScreen() {
        for (int width : WIDTHS) {
            BuilderModeStripLayout layout = at(width);
            String where = " at width " + width;
            int last = BuilderModeStripLayout.SLOTS - 1;

            assertTrue(layout.xFor(0) >= 0, "strip starts off the left edge" + where);
            assertTrue(layout.xFor(last) + layout.widthFor(last) <= width,
                    "strip runs off the right edge" + where);

            int leftMargin = layout.xFor(0);
            int rightMargin = width - (layout.xFor(last) + layout.widthFor(last));
            assertTrue(Math.abs(leftMargin - rightMargin) <= 1, "strip is off-centre" + where);

            // The walked positions and the declared extent must agree — two ways of saying how wide
            // the row is, and a centred strip is only centred if they do.
            assertEquals(layout.stripWidth(),
                    layout.xFor(last) + layout.widthFor(last) - layout.xFor(0),
                    "stripWidth disagrees with the laid-out tiles" + where);
        }
    }

    @Test
    @DisplayName("Every tile has a positive size")
    void tilesAreNeverDegenerate() {
        // Below Minecraft's own 320 floor as well: the arithmetic subtracts before it clamps, and a
        // negative intermediate must not reach a width.
        for (int width : new int[]{1920, 480, 320, 200, 120}) {
            BuilderModeStripLayout layout = at(width);
            for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                assertTrue(layout.widthFor(slot) > 0,
                        "slot " + slot + " has no width at " + width);
                assertTrue(layout.heightFor(slot) > 0,
                        "slot " + slot + " has no height at " + width);
            }
        }
    }

    @Test
    @DisplayName("The row's height never changes, so the grid below keeps its budget")
    void rowHeightIsFixed() {
        for (int width : WIDTHS) {
            BuilderModeStripLayout layout = at(width);
            assertEquals(SELECTED_HEIGHT, layout.height(),
                    "strip height moved at width " + width);
            for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                assertTrue(layout.heightFor(slot) <= SELECTED_HEIGHT,
                        "slot " + slot + " is taller than the row at " + width);
            }
        }
    }

    @Test
    @DisplayName("Exactly one slot is selected, and it is the largest")
    void selectedSlotIsTheLargest() {
        for (int width : WIDTHS) {
            BuilderModeStripLayout layout = at(width);
            String where = " at width " + width;
            int selected = 0;
            for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                if (layout.isSelected(slot)) {
                    selected++;
                    continue;
                }
                assertTrue(layout.widthFor(slot) < layout.widthFor(BuilderModeStripLayout.SELECTED_SLOT),
                        "slot " + slot + " is not smaller than the selection" + where);
                assertTrue(layout.heightFor(slot) < layout.heightFor(BuilderModeStripLayout.SELECTED_SLOT),
                        "slot " + slot + " is not shorter than the selection" + where);
            }
            assertEquals(1, selected, "wrong number of selected slots" + where);
            assertTrue(layout.isSelected(BuilderModeStripLayout.SELECTED_SLOT), "wrong slot selected" + where);
        }
    }

    @Test
    @DisplayName("The selected tile keeps its full width down to Minecraft's narrowest viewport")
    void selectedTileKeepsItsWidth() {
        // The thumbnails are what give way when the row is tight — the selected tile matching the
        // controls beneath it is the part worth keeping.
        for (int width : WIDTHS) {
            assertEquals(SELECTED_WIDTH, at(width).widthFor(BuilderModeStripLayout.SELECTED_SLOT),
                    "the selected tile shrank at width " + width);
        }
    }

    @Test
    @DisplayName("Thumbnails ride the centre line of the selected tile")
    void thumbnailsAreVerticallyCentred() {
        for (int width : WIDTHS) {
            BuilderModeStripLayout layout = at(width);
            String where = " at width " + width;
            assertEquals(TOP, layout.yFor(BuilderModeStripLayout.SELECTED_SLOT),
                    "the selected tile left the top of the row" + where);

            int rowCentre = TOP + SELECTED_HEIGHT / 2;
            for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                int centre = layout.yFor(slot) + layout.heightFor(slot) / 2;
                assertTrue(Math.abs(centre - rowCentre) <= 1,
                        "slot " + slot + " is off the centre line" + where);
                assertTrue(layout.yFor(slot) >= TOP, "slot " + slot + " sits above the row" + where);
                assertTrue(layout.yFor(slot) + layout.heightFor(slot) <= TOP + SELECTED_HEIGHT,
                        "slot " + slot + " sits below the row" + where);
            }
        }
    }

    @Test
    @DisplayName("Every mode appears exactly once, whichever one is selected")
    void rotationShowsEveryModeOnce() {
        for (int selected = 0; selected < BuilderModeStripLayout.SLOTS; selected++) {
            Set<Integer> seen = new HashSet<>();
            for (int slot = 0; slot < BuilderModeStripLayout.SLOTS; slot++) {
                int index = BuilderModeStripLayout.rotatedIndex(slot, selected);
                assertTrue(index >= 0 && index < BuilderModeStripLayout.SLOTS,
                        "mode index " + index + " is out of range");
                assertTrue(seen.add(index),
                        "mode " + index + " appears twice with " + selected + " selected");
            }
            assertEquals(BuilderModeStripLayout.SLOTS, seen.size(),
                    "a mode is missing with " + selected + " selected");
        }
    }

    @Test
    @DisplayName("The selected mode lands in the selected slot, with the menu order wound round it")
    void rotationKeepsMenuOrder() {
        for (int selected = 0; selected < BuilderModeStripLayout.SLOTS; selected++) {
            assertEquals(selected,
                    BuilderModeStripLayout.rotatedIndex(BuilderModeStripLayout.SELECTED_SLOT, selected),
                    "the selection is not in the selected slot");

            for (int slot = 0; slot + 1 < BuilderModeStripLayout.SLOTS; slot++) {
                int here = BuilderModeStripLayout.rotatedIndex(slot, selected);
                int next = BuilderModeStripLayout.rotatedIndex(slot + 1, selected);
                assertEquals((here + 1) % BuilderModeStripLayout.SLOTS, next,
                        "menu order breaks between slots " + slot + " and " + (slot + 1));
            }
        }
    }

    @Test
    @DisplayName("The selection is flanked on both sides")
    void selectionIsFlanked() {
        // The point of a fixed slot rather than the selected mode's own place in the enum: the row
        // would otherwise be all-to-one-side for the first and last modes.
        assertTrue(BuilderModeStripLayout.SELECTED_SLOT > 0, "nothing sits left of the selection");
        assertTrue(BuilderModeStripLayout.SELECTED_SLOT < BuilderModeStripLayout.SLOTS - 1,
                "nothing sits right of the selection");
        assertFalse(at(480).isSelected(0), "the first slot should be a thumbnail");
    }
}
