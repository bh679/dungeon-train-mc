package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.advancement.HiddenGap;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the "…" on an advancement's incoming connector when hidden advancements were skipped there.
 *
 * <p>Both vanilla's {@code AdvancementWidget.drawConnectivity} and Better Advancements'
 * {@code drawConnection} draw a connector as an elbow of {@code hLine}/{@code vLine} calls: once 3px thick
 * in black (the shadow pass), then 1px in the line colour. The widget mixins route every {@code hLine} of
 * that elbow through {@link #hLine}; only the child-side horizontal run of a marked advancement is changed.
 * It is cut around the middle of the empty column the layout left for it, and three dots go in the cut,
 * 3×3 black in the shadow pass and 1×1 in the line colour after, so they read like the line itself.</p>
 */
public final class HiddenGapConnector {

    private HiddenGapConnector() {}

    /** Draws one horizontal piece of a connector, given the line call it replaces. */
    @FunctionalInterface
    public interface Line {
        void draw(int from, int to, int y, int colour);
    }

    /**
     * @param node         the advancement the connector leads into
     * @param childCentreX x of that advancement's tile centre, screen space
     * @param childCentreY y of that advancement's tile centre, screen space
     * @param shadow       whether this is the black shadow pass
     */
    public static void hLine(GuiGraphics graphics, int from, int to, int y, int colour,
                             AdvancementNode node, int childCentreX, int childCentreY, boolean shadow, Line original) {
        boolean childRun = (from == childCentreX || to == childCentreX) && Math.abs(y - childCentreY) <= 1;
        if (!childRun || node == null || !AdvancementGapsClient.isMarked(node.holder().id())) {
            original.draw(from, to, y, colour);
            return;
        }
        HiddenGap.Split split = HiddenGap.split(from, to, childCentreX);
        for (int[] segment : split.segments()) {
            original.draw(segment[0], segment[1], y, colour);
        }
        if (!split.hasDots() || y != childCentreY) return;
        int r = shadow ? 1 : 0;
        for (int dot : split.dots()) {
            graphics.fill(dot - r, y - r, dot + r + 1, y + r + 1, colour);
        }
    }
}
