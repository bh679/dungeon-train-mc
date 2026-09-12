package games.brennan.dungeontrain.client.menu;

/**
 * Which surface the X key opens: the inventory-style editor screen, or the row-list panel.
 *
 * <p>The screen is the editor's own menu, so it answers anywhere in the editor world — between
 * plots as well as inside one — inside a plot in any other world, and anywhere in a Train Builder
 * world, where the one build on the platform stands in for "the plot you are in". Only when the X
 * menu is set to screen space: world-space keeps the row-list panel it was asked for. Pure, so the
 * rule is pinned by a test rather than by walking into the editor.</p>
 */
public final class EditorScreenGate {

    private EditorScreenGate() {}

    /** The three-cue form from before the Train Builder counted. */
    public static boolean opensInventoryScreen(boolean standingInPlot, boolean inEditorWorld, boolean screenspace) {
        return opensInventoryScreen(standingInPlot, inEditorWorld, false, screenspace);
    }

    public static boolean opensInventoryScreen(boolean standingInPlot, boolean inEditorWorld,
                                               boolean inBuilderWorld, boolean screenspace) {
        return screenspace && (standingInPlot || inEditorWorld || inBuilderWorld);
    }
}
