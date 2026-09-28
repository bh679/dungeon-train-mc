package games.brennan.dungeontrain.client.menu;

/**
 * Which surface the X key opens: the inventory-style editor screen, or the row-list panel.
 *
 * <p>The screen is the editor's own menu, so it answers anywhere in the editor world — between
 * plots as well as inside one — and inside a plot in any other world. Only when the X menu is set
 * to screen space: world-space keeps the row-list panel it was asked for. Pure, so the rule is
 * pinned by a test rather than by walking into the editor.</p>
 */
public final class EditorScreenGate {

    private EditorScreenGate() {}

    public static boolean opensInventoryScreen(boolean standingInPlot, boolean inEditorWorld, boolean screenspace) {
        return screenspace && (standingInPlot || inEditorWorld);
    }
}
