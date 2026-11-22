package games.brennan.dungeontrain.client.menu.blockvariant;

/**
 * Where the stacked group panel sits relative to the block variant menu below it. Pure maths, kept
 * apart from the menu state so it can be unit-tested.
 */
public final class StackedPanelLayout {

    /** Space between the two panels, in panel-local units (a third of a row). */
    public static final double GAP = 0.1;

    private StackedPanelLayout() {}

    /**
     * How far above the main panel's anchor, in world blocks, the child panel's anchor goes. Both
     * panels are centred on their anchors, so the child's centre is half of each height plus the gap
     * away, all scaled by the world-space menu scale.
     */
    public static double childOffset(double mainHeight, double childHeight, double worldScale) {
        return (mainHeight / 2.0 + childHeight / 2.0 + GAP) * worldScale;
    }
}
