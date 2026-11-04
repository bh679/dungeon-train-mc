package games.brennan.dungeontrain.client.menu.editorscreen;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * One region of the X menu as its routers see it: whether it is on screen for the current tab and
 * mode, and what it does with a click, a right-click on a number, a wheel notch and a hover.
 *
 * <p>The pane classes themselves keep their own shapes — each lays out and renders from what it
 * needs — and {@link EditorGuiScreen} wraps each in a small adapter that implements this. The
 * screen's click, right-click, scroll and tooltip routers are then one loop each over the active
 * panes, in a fixed order: the tab bar and filter bar first, the left column's page pane, then the
 * right column's.</p>
 */
interface EditorPane {

    /** Whether this pane is on screen for the current tab and mode. */
    boolean active();

    /** A left click: true when the pane took it (consumed, whether or not anything happened). */
    default boolean mouseClicked(double mx, double my, int button) {
        return false;
    }

    /**
     * A right click: the step-down of whichever number cell is under the cursor, and nothing
     * anywhere else. Probed without acting on what is not a number, so a right-click never selects,
     * opens or toggles. True when a number stepped.
     */
    default boolean rightClickNumber(double mx, double my) {
        return false;
    }

    /** A wheel notch ({@code dir} is +1 down, -1 up): true when the pane took it. */
    default boolean scrollBy(double mx, double my, int dir) {
        return false;
    }

    /** The hover text under the cursor, or {@link Tooltip#NONE}. */
    default Tooltip tooltipAt(double mx, double my) {
        return Tooltip.NONE;
    }

    /** A tooltip: its lines, and the item icons drawn under them (a prefab's contents), if any. */
    record Tooltip(List<String> lines, List<ItemStack> icons) {
        static final Tooltip NONE = new Tooltip(List.of(), List.of());

        static Tooltip of(String line) {
            return line == null ? NONE : new Tooltip(List.of(line), List.of());
        }

        static Tooltip of(List<String> lines) {
            return lines == null || lines.isEmpty() ? NONE : new Tooltip(lines, List.of());
        }

        boolean isEmpty() {
            return lines.isEmpty();
        }
    }
}
