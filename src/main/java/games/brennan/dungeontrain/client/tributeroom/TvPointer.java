package games.brennan.dungeontrain.client.tributeroom;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where on the picture the player is looking: the crosshair's hit on the TV's face, turned into the
 * GUI coordinates of the screen drawn on it, so the screen's own buttons hover and take clicks as
 * they would under a mouse.
 *
 * <p>Pure, so the mapping is unit-tested for every facing: the TV's blocks and the direction its
 * picture faces go in, a point on that face comes back as a fraction across and down the screen,
 * then through the same contain-fit the TV draws the picture with.</p>
 */
public final class TvPointer {

    /** Anything this far outside the picture, in GUI units, is "no mouse" to the screen. */
    public static final int OFF_SCREEN = -10_000;

    private TvPointer() {}

    /**
     * The fraction across ({@code [0]}, left to right as the viewer sees it) and down ({@code [1]},
     * top to bottom) of the screen face at {@code hit}; null when {@code hit} is not on the face.
     */
    @Nullable
    public static double[] faceFraction(Direction facing, BoundingBox screen, Vec3 hit) {
        double w, u;
        switch (facing) {
            // Facing south: the viewer looks north, their left is west — the low x edge.
            case SOUTH -> { w = screen.getXSpan(); u = (hit.x - screen.minX()) / w; }
            case NORTH -> { w = screen.getXSpan(); u = (screen.maxX() + 1 - hit.x) / w; }
            // Facing east: the viewer looks west, their left is south — the high z edge.
            case EAST -> { w = screen.getZSpan(); u = (screen.maxZ() + 1 - hit.z) / w; }
            case WEST -> { w = screen.getZSpan(); u = (hit.z - screen.minZ()) / w; }
            default -> { return null; }
        }
        double v = (screen.maxY() + 1 - hit.y) / screen.getYSpan();
        if (u < 0 || u > 1 || v < 0 || v > 1) return null;
        return new double[] {u, v};
    }

    /**
     * The bezel Vista leaves around the picture, in blocks: the screen is {@code 16 * blocks - 4} pixels
     * across, two pixels in from each edge of the whole face ({@code TVBlockEntity#getScreenPixelWidth}).
     */
    public static final double BEZEL = 2.0 / 16.0;

    /**
     * {@code uv} across the whole face turned into the fraction across the picture inside the bezel;
     * null when the point is on the bezel. {@code blocksAcross} and {@code blocksDown} size the face.
     */
    @Nullable
    public static double[] insideBezel(double[] uv, int blocksAcross, int blocksDown) {
        if (uv == null) return null;
        double u = (uv[0] * blocksAcross - BEZEL) / (blocksAcross - 2 * BEZEL);
        double v = (uv[1] * blocksDown - BEZEL) / (blocksDown - 2 * BEZEL);
        if (u < 0 || u > 1 || v < 0 || v > 1) return null;
        return new double[] {u, v};
    }

    /**
     * GUI coordinates for a point {@code u} across and {@code v} down a screen face of aspect
     * {@code screenAspect} showing a picture of aspect {@code pictureAspect} contain-fitted (centred,
     * the larger axis filling); {@link #OFF_SCREEN} when the point lands in the letterbox.
     */
    public static int[] toGui(double u, double v, double screenAspect, double pictureAspect, int guiWidth, int guiHeight) {
        double pw = 1, ph = 1;
        if (pictureAspect > screenAspect) ph = screenAspect / pictureAspect;
        else if (pictureAspect < screenAspect) pw = pictureAspect / screenAspect;
        double x = (u - (1 - pw) / 2) / pw;
        double y = (v - (1 - ph) / 2) / ph;
        if (x < 0 || x > 1 || y < 0 || y > 1) return new int[] {OFF_SCREEN, OFF_SCREEN};
        return new int[] {(int) Math.floor(x * guiWidth), (int) Math.floor(y * guiHeight)};
    }
}
