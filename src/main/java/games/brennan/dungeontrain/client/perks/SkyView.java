package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;

/**
 * Where the sky sits on the screen: {@code zoom} GUI px per editor unit, and the editor point under
 * the centre of the sky area. Immutable; every change returns a new view. Pure maths, unit-tested.
 *
 * @param zoom    GUI pixels per editor unit
 * @param centreX editor x shown at the middle of the sky area
 * @param centreY editor y shown at the middle of the sky area
 */
public record SkyView(double zoom, double centreX, double centreY) {

    /** Header strip above the sky, GUI px. */
    public static final int HEADER = 24;
    public static final double MAX_ZOOM = 2.0;
    public static final double ZOOM_STEP = 1.25;
    /** A quarter of the sky must stay on screen. */
    private static final double PAN_SLACK = 0.25;

    /** Zoom at which 800 editor units fill the sky's height. */
    public static double fitZoom(int screenWidth, int screenHeight) {
        int h = Math.max(1, screenHeight - HEADER);
        int w = Math.max(1, screenWidth);
        return Math.min(h, w) / PerkTree.SIZE;
    }

    public static SkyView fit(int screenWidth, int screenHeight) {
        return new SkyView(fitZoom(screenWidth, screenHeight), PerkTree.CENTRE, PerkTree.CENTRE);
    }

    private static double skyMidX(int screenWidth) {
        return screenWidth / 2.0;
    }

    private static double skyMidY(int screenHeight) {
        return HEADER + (screenHeight - HEADER) / 2.0;
    }

    public double toScreenX(double x, int screenWidth) {
        return skyMidX(screenWidth) + (x - centreX) * zoom;
    }

    public double toScreenY(double y, int screenHeight) {
        return skyMidY(screenHeight) + (y - centreY) * zoom;
    }

    public double toSkyX(double screenX, int screenWidth) {
        return centreX + (screenX - skyMidX(screenWidth)) / zoom;
    }

    public double toSkyY(double screenY, int screenHeight) {
        return centreY + (screenY - skyMidY(screenHeight)) / zoom;
    }

    /** Pan by a screen-pixel delta, then clamp. */
    public SkyView panned(double dxScreen, double dyScreen, int screenWidth, int screenHeight) {
        return new SkyView(zoom, centreX - dxScreen / zoom, centreY - dyScreen / zoom).clamped(screenWidth, screenHeight);
    }

    /** Zoom by {@code factor} keeping the sky point under {@code (screenX, screenY)} still. */
    public SkyView zoomedAt(double factor, double screenX, double screenY, int screenWidth, int screenHeight) {
        double minZoom = fitZoom(screenWidth, screenHeight);
        double next = Math.max(minZoom, Math.min(MAX_ZOOM, zoom * factor));
        if (next == zoom) return this;
        double sx = toSkyX(screenX, screenWidth), sy = toSkyY(screenY, screenHeight);
        double cx = sx - (screenX - skyMidX(screenWidth)) / next;
        double cy = sy - (screenY - skyMidY(screenHeight)) / next;
        return new SkyView(next, cx, cy).clamped(screenWidth, screenHeight);
    }

    /** Centre on a sky point at the current zoom. */
    public SkyView centredOn(double x, double y, int screenWidth, int screenHeight) {
        return new SkyView(zoom, x, y).clamped(screenWidth, screenHeight);
    }

    /** Keep at least a quarter of the sky's extent on screen in each axis. */
    public SkyView clamped(int screenWidth, int screenHeight) {
        double halfW = screenWidth / 2.0 / zoom;
        double halfH = (screenHeight - HEADER) / 2.0 / zoom;
        double slackX = Math.max(0, halfW - PerkTree.SIZE * PAN_SLACK);
        double slackY = Math.max(0, halfH - PerkTree.SIZE * PAN_SLACK);
        double minX = -slackX + PerkTree.SIZE * PAN_SLACK, maxX = PerkTree.SIZE - PerkTree.SIZE * PAN_SLACK + slackX;
        double minY = -slackY + PerkTree.SIZE * PAN_SLACK, maxY = PerkTree.SIZE - PerkTree.SIZE * PAN_SLACK + slackY;
        double cx = Math.max(Math.min(minX, maxX), Math.min(maxX, centreX));
        double cy = Math.max(Math.min(minY, maxY), Math.min(maxY, centreY));
        return cx == centreX && cy == centreY ? this : new SkyView(zoom, cx, cy);
    }

    /** Whether perk names are drawn for every star (not only the hovered one). */
    public boolean showsAllNames() {
        return zoom >= 1.0;
    }
}
