package games.brennan.dungeontrain.client.menu;

/**
 * The arithmetic that maps an editor panel's own coordinates onto the screen, and back.
 *
 * <p>Panels are laid out in <em>panel-local</em> units: x right, y up, origin at the panel's
 * centre. The screen is pixels with y growing downward. This carries the whole of that
 * conversion — the fit scale and the Y flip — and nothing else, so it can be checked without a
 * running client.</p>
 *
 * <p>It is worth isolating because it is the one piece that can be wrong <em>silently</em>. If
 * the draw transform and the inverse used for hit-testing disagree, nothing crashes and nothing
 * looks obviously broken: rows simply highlight in one place and act in another, by an offset
 * that grows with distance from the centre. {@link #localX}/{@link #localY} are exact inverses
 * of the transform {@link PanelScreenHost} pushes, and the round-trip test pins that.</p>
 */
public record PanelSpaceMapping(double pxPerUnit, double centreX, double centreY) {

    /** Breathing room between the panel and the window edge, in pixels. */
    public static final int MARGIN = 8;

    /**
     * Fit a panel of {@code widthUnits} x {@code heightUnits} into the window.
     *
     * <p>The vertical budget stops short of the hotbar, which keeps rendering behind the panel
     * and stays live — the V menu's Swap row takes whichever block the author is holding, so
     * covering the slots they are scrolling through would break the feature the panel exists
     * for. A panel too big for what remains is scaled down rather than clipped: a clipped panel
     * silently hides rows and the author has no way to know they are missing.</p>
     *
     * @param preferredPxPerUnit the panel's natural scale — the reciprocal of its text scale,
     *                           so its font lands at 1:1. Never exceeded, only shrunk.
     */
    public static PanelSpaceMapping fit(int screenWidth, int screenHeight, int hotbarReserve,
                                        double widthUnits, double heightUnits,
                                        double preferredPxPerUnit) {
        double maxW = Math.max(1, screenWidth - 2 * MARGIN);
        double maxH = Math.max(1, screenHeight - hotbarReserve - 2 * MARGIN);
        double w = Math.max(0.001, widthUnits);
        double h = Math.max(0.001, heightUnits);
        double px = Math.min(preferredPxPerUnit, Math.min(maxW / w, maxH / h));
        return new PanelSpaceMapping(px, screenWidth / 2.0, (screenHeight - hotbarReserve) / 2.0);
    }

    /** Screen pixel x to panel-local x. */
    public double localX(double mouseX) {
        return (mouseX - centreX) / pxPerUnit;
    }

    /** Screen pixel y to panel-local y. Negated: the panel's y grows upward, the screen's down. */
    public double localY(double mouseY) {
        return (centreY - mouseY) / pxPerUnit;
    }

    /** Panel-local x to screen pixel x — the forward direction the draw transform applies. */
    public double screenX(double localX) {
        return centreX + localX * pxPerUnit;
    }

    /** Panel-local y to screen pixel y. */
    public double screenY(double localY) {
        return centreY - localY * pxPerUnit;
    }
}
