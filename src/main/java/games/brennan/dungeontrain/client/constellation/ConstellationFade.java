package games.brennan.dungeontrain.client.constellation;

/**
 * How far open the constellation patch is, {@code 0}..{@code 1}: eased toward 1 while the player
 * stands in a constellation block and back toward 0 once they step out.
 *
 * <p>Timed in real seconds, like {@code ClientPortalRoomSky}'s lift, so the ease does not depend on
 * the frame rate. Pure logic (no Minecraft types) so the timing is unit-tested; {@link ConstellationInside}
 * answers "inside?" and {@link ConstellationClient} feeds both in once a frame.</p>
 */
public final class ConstellationFade {

    /** How long the patch takes to open or close fully. */
    public static final float FADE_SECONDS = 1.2f;

    /** Below this the patch is not drawn at all. */
    public static final float OFF_EPSILON = 0.004f;

    private float linear;
    private long lastNanos = Long.MIN_VALUE;

    /**
     * Step the fade to {@code nowNanos} toward open ({@code inside}) or closed, and return the eased
     * strength to draw at.
     */
    public float advance(boolean inside, long nowNanos) {
        float dt = lastNanos == Long.MIN_VALUE ? 0f : (nowNanos - lastNanos) / 1.0e9f;
        lastNanos = nowNanos;
        // A long stall (a pause, a world load) must not count as time spent fading.
        dt = Math.max(0f, Math.min(dt, 0.25f));
        float step = dt / FADE_SECONDS;
        linear = inside ? Math.min(1f, linear + step) : Math.max(0f, linear - step);
        return eased();
    }

    /** The current strength, smoothstepped so it neither starts nor stops with a jolt. */
    public float eased() {
        float t = linear;
        return t * t * (3f - 2f * t);
    }

    public boolean visible() {
        return eased() > OFF_EPSILON;
    }

    /** Closed at once: leaving a world. */
    public void reset() {
        linear = 0f;
        lastNanos = Long.MIN_VALUE;
    }
}
