package games.brennan.dungeontrain.client.menu.blockvariant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link StackedPanelLayout}: where the group panel sits above the block variant menu. */
class StackedPanelLayoutTest {

    private static final double EPS = 1.0e-9;

    @Test
    void childClearsBothHalvesPlusTheGap() {
        // Main 2.0 tall, child 1.0 tall: centres 1.0 + 0.5 apart, plus the gap.
        assertEquals(1.5 + StackedPanelLayout.GAP, StackedPanelLayout.childOffset(2.0, 1.0, 1.0), EPS);
    }

    @Test
    void offsetScalesWithTheWorldSpaceMenuScale() {
        double unscaled = StackedPanelLayout.childOffset(2.0, 1.0, 1.0);
        assertEquals(unscaled * 0.5, StackedPanelLayout.childOffset(2.0, 1.0, 0.5), EPS);
    }

    @Test
    void panelsNeverOverlap() {
        // The child's bottom edge sits above the main panel's top edge for any heights.
        for (double main = 0.5; main < 4; main += 0.5) {
            for (double child = 0.5; child < 4; child += 0.5) {
                double childBottom = StackedPanelLayout.childOffset(main, child, 1.0) - child / 2.0;
                assertTrue(childBottom > main / 2.0, "overlap at " + main + "/" + child);
            }
        }
    }
}
