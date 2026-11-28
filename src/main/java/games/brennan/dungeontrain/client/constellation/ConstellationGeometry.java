package games.brennan.dungeontrain.client.constellation;

import org.joml.Vector3f;

/**
 * Where the constellation patch hangs: a flat disc facing the camera's origin, 45 degrees above the
 * horizon the player faced when they last ate a cosmic apple (south until then), {@link #DISTANCE} blocks out (sky distance, the same as the sky dome). Camera-
 * relative and orientation-only, so the patch stays put in the sky as the player walks and the train
 * moves, the way the moon does. Pure maths, unit-tested.
 */
public final class ConstellationGeometry {

    public static final float DISTANCE = 100.0f;
    public static final float ELEVATION_DEG = 45.0f;
    /** Half the angle the disc spans across, measured from its centre. */
    public static final float ANGULAR_RADIUS_DEG = 30.0f;
    /** The disc's radius in blocks at {@link #DISTANCE}. */
    public static final float RADIUS = (float) (DISTANCE * Math.tan(Math.toRadians(ANGULAR_RADIUS_DEG)));

    /** The disc's frame: toward its centre, its right (as seen facing it) and its up. */
    private record Axes(Vector3f forward, Vector3f right, Vector3f up) {}

    /** South until an apple says otherwise ({@link #setHeading}). */
    private static volatile Axes axes = axesFor(0f);

    /**
     * Turn the disc to hang over the horizon {@code yawDegrees} faces (Minecraft yaw: 0 is south, 90
     * west), still {@link #ELEVATION_DEG} up. Set when the player eats a cosmic apple.
     */
    public static void setHeading(float yawDegrees) {
        axes = axesFor(yawDegrees);
    }

    static Axes axesFor(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees), e = Math.toRadians(ELEVATION_DEG);
        float hx = (float) -Math.sin(yaw), hz = (float) Math.cos(yaw);
        Vector3f forward = new Vector3f(hx * (float) Math.cos(e), (float) Math.sin(e), hz * (float) Math.cos(e));
        // facing the disc, its right is the player's right: facing south, west (-X)
        Vector3f right = new Vector3f(-hz, 0f, hx);
        Vector3f up = new Vector3f(right).cross(forward);
        return new Axes(forward, right, up);
    }

    public static Vector3f forward() {
        return new Vector3f(axes.forward());
    }

    public static Vector3f right() {
        return new Vector3f(axes.right());
    }

    public static Vector3f up() {
        return new Vector3f(axes.up());
    }

    private ConstellationGeometry() {}

    /** A point on the disc's plane, from its centre-relative coordinates in radii ({@code -1..1}). */
    public static Vector3f point(float x, float y) {
        Axes a = axes;
        return new Vector3f(a.forward()).mul(DISTANCE)
            .add(new Vector3f(a.right()).mul(x * RADIUS))
            .add(new Vector3f(a.up()).mul(y * RADIUS));
    }

    /**
     * Where a view ray from the camera meets the disc, in radii from its centre ({@code x} right,
     * {@code y} up), or null when the ray points away from the disc's plane.
     */
    public static float[] hit(Vector3f look) {
        Axes a = axes;
        float facing = look.dot(a.forward());
        if (facing <= 1.0e-4f) return null;
        float t = DISTANCE / facing;
        Vector3f p = new Vector3f(look).mul(t).sub(new Vector3f(a.forward()).mul(DISTANCE));
        return new float[] {p.dot(a.right()) / RADIUS, p.dot(a.up()) / RADIUS};
    }
}
