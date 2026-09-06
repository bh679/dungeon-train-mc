package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * How far the preview has turned.
 *
 * <p>Spins on its own, the way a hovered tile does; a drag takes over and the spin resumes a
 * moment after the mouse lets go. Pure, with time passed in, so it is testable frame by frame.</p>
 */
public final class OrbitState {

    /** Degrees per second while spinning on its own — a drift, not a carousel. */
    public static final float SPIN_DEGREES_PER_SECOND = 30.0F;
    /** Degrees of yaw per pixel of horizontal drag. */
    public static final float DRAG_DEGREES_PER_PIXEL = 0.6F;
    /** How long after a drag ends before the spin resumes. */
    public static final float RESUME_AFTER_SECONDS = 1.5F;
    /** Off-axis, so the resting pose shows two faces of the build. */
    public static final float REST_YAW = 35.0F;

    private float yaw = REST_YAW;
    private boolean dragging;
    private float idleSeconds = RESUME_AFTER_SECONDS;

    public float yaw() {
        return yaw;
    }

    public boolean isDragging() {
        return dragging;
    }

    /** Advance one frame of {@code seconds}; spins unless a drag is in progress or just ended. */
    public void advance(float seconds) {
        if (dragging) return;
        if (idleSeconds < RESUME_AFTER_SECONDS) {
            idleSeconds += seconds;
            return;
        }
        yaw = wrap(yaw + SPIN_DEGREES_PER_SECOND * seconds);
    }

    public void beginDrag() {
        dragging = true;
    }

    /** The mouse moved {@code dxPixels} while dragging. */
    public void drag(float dxPixels) {
        if (!dragging) return;
        yaw = wrap(yaw + dxPixels * DRAG_DEGREES_PER_PIXEL);
    }

    public void endDrag() {
        if (!dragging) return;
        dragging = false;
        idleSeconds = 0.0F;
    }

    /** A new template: back to the resting angle, spinning. */
    public void reset() {
        yaw = REST_YAW;
        dragging = false;
        idleSeconds = RESUME_AFTER_SECONDS;
    }

    private static float wrap(float degrees) {
        float d = degrees % 360.0F;
        return d < 0 ? d + 360.0F : d;
    }
}
