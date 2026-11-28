package games.brennan.dungeontrain.client.constellation;

import org.joml.Vector3f;

/**
 * Where the constellation patch hangs: a flat disc facing the camera's origin, 45 degrees above the
 * southern horizon, {@link #DISTANCE} blocks out (sky distance, the same as the sky dome). Camera-
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

    /** Toward the disc's centre: due south (+Z), tipped up by {@link #ELEVATION_DEG}. */
    public static final Vector3f FORWARD = new Vector3f(0f,
        (float) Math.sin(Math.toRadians(ELEVATION_DEG)), (float) Math.cos(Math.toRadians(ELEVATION_DEG)));
    /** The disc's right, as seen facing it: facing south, east (+X) is on the left. */
    public static final Vector3f RIGHT = new Vector3f(-1f, 0f, 0f);
    /** The disc's up: tipped back from vertical by the elevation. */
    public static final Vector3f UP = new Vector3f(RIGHT).cross(FORWARD);

    private ConstellationGeometry() {}

    /** A point on the disc's plane, from its centre-relative coordinates in radii ({@code -1..1}). */
    public static Vector3f point(float x, float y) {
        return new Vector3f(FORWARD).mul(DISTANCE)
            .add(new Vector3f(RIGHT).mul(x * RADIUS))
            .add(new Vector3f(UP).mul(y * RADIUS));
    }

    /**
     * Where a view ray from the camera meets the disc, in radii from its centre ({@code x} right,
     * {@code y} up), or null when the ray points away from the disc's plane.
     */
    public static float[] hit(Vector3f look) {
        float facing = look.dot(FORWARD);
        if (facing <= 1.0e-4f) return null;
        float t = DISTANCE / facing;
        Vector3f p = new Vector3f(look).mul(t).sub(new Vector3f(FORWARD).mul(DISTANCE));
        return new float[] {p.dot(RIGHT) / RADIUS, p.dot(UP) / RADIUS};
    }
}
