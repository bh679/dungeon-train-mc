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
    public static final double MAX_ZOOM = 1.3;
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

    /**
     * The zoom steps: fully out (the whole sky fits) and close, halfway from there to {@link #MAX_ZOOM};
     * with {@code closest}, a third at {@link #MAX_ZOOM} itself. A screen so large that the fit is already
     * past {@link #MAX_ZOOM} has the one step.
     */
    public static double[] zoomSteps(int screenWidth, int screenHeight, boolean closest) {
        double fit = fitZoom(screenWidth, screenHeight);
        if (fit >= MAX_ZOOM) return new double[] {fit};
        double half = (fit + MAX_ZOOM) / 2;
        return closest ? new double[] {fit, half, MAX_ZOOM} : new double[] {fit, half};
    }

    public static double[] zoomSteps(int screenWidth, int screenHeight) {
        return zoomSteps(screenWidth, screenHeight, false);
    }

    /** Which of {@link #zoomSteps} the view is at (the nearest one). */
    public int step(int screenWidth, int screenHeight, boolean closest) {
        return stepIndex(zoomSteps(screenWidth, screenHeight, closest));
    }

    /** The view at zoom step {@code i}, keeping its centre. */
    public SkyView atStep(int i, int screenWidth, int screenHeight, boolean closest) {
        double[] steps = zoomSteps(screenWidth, screenHeight, closest);
        double z = steps[Math.max(0, Math.min(steps.length - 1, i))];
        return new SkyView(z, centreX, centreY).clamped(screenWidth, screenHeight);
    }

    /** Index of the step nearest the current zoom. */
    private int stepIndex(double[] steps) {
        int best = 0;
        for (int i = 1; i < steps.length; i++) {
            if (Math.abs(steps[i] - zoom) < Math.abs(steps[best] - zoom)) best = i;
        }
        return best;
    }

    /**
     * One zoom step in ({@code factor > 1}) or out ({@code factor < 1}), keeping the sky point under
     * {@code (screenX, screenY)} still. Zoom moves only between {@link #zoomSteps}.
     */
    public SkyView zoomedAt(double factor, double screenX, double screenY, int screenWidth, int screenHeight) {
        return zoomedAt(factor, screenX, screenY, screenWidth, screenHeight, false);
    }

    public SkyView zoomedAt(double factor, double screenX, double screenY, int screenWidth, int screenHeight, boolean closest) {
        double[] steps = zoomSteps(screenWidth, screenHeight, closest);
        int i = stepIndex(steps);
        int target = factor > 1 ? Math.min(steps.length - 1, i + 1) : factor < 1 ? Math.max(0, i - 1) : i;
        double next = steps[target];
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
