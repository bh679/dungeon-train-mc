package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;

/**
 * Where the perk map sits on a {@link PerksScreen#forSky()} canvas, for the constellation patch to map
 * its disc onto: the canvas point under the map's centre (the Engine) and the disc's radius in GUI
 * units. The sky-only screen keeps the fitted view ({@link SkyView#fit}), so this is fixed per size.
 */
public final class SkyViewAccess {

    /** Disc radius over half the map's fitted size: room past the outermost stars for the feather. */
    public static final float MARGIN = 1.2f;

    private SkyViewAccess() {}

    /** {@code {centreX, centreY, radius}} in GUI units on a {@code width x height} canvas. */
    public static float[] skySquare(int width, int height) {
        SkyView view = SkyView.fit(width, height);
        float cx = (float) view.toScreenX(PerkTree.CENTRE, width);
        float cy = (float) view.toScreenY(PerkTree.CENTRE, height);
        float radius = (float) (PerkTree.SIZE / 2.0 * view.zoom()) * MARGIN;
        return new float[] {cx, cy, radius};
    }
}
