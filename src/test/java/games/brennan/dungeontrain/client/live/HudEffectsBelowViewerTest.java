package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudEffectsBelowViewerTest {

    /** The gap under the box is double the icons' own spacing. */
    @Test
    void gapIsDoubleIconSpacing() {
        assertEquals(2 * HudEffectsBelowViewer.ICON_SPACING, HudEffectsBelowViewer.GAP);
    }

    /** Vanilla's first row sits 1 px down; after the shift it sits exactly GAP under the box. */
    @Test
    void firstRowLandsGapUnderBox() {
        int boxBottom = 100;
        int vanillaFirstRowTop = 1;
        assertEquals(boxBottom + HudEffectsBelowViewer.GAP, vanillaFirstRowTop + HudEffectsBelowViewer.offset(boxBottom));
    }
}
