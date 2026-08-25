package games.brennan.dungeontrain.client.builder;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;

/**
 * How far each tile's model has turned.
 *
 * <p>Hovered, a tile spins; let go, it walks back to the resting three-quarter angle rather than
 * snapping, so a mouse crossing the grid leaves a wake of tiles settling instead of a row of jumps.
 * The two are separate speeds on purpose — the return is quicker than the spin, because a tile you
 * have stopped looking at should be done moving by the time you notice it again.</p>
 *
 * <p>Keyed by the entry's own id rather than its grid index: the list re-lays-out when you scroll
 * or drill into a group, and an index-keyed angle would hand one tile's rotation to whichever
 * template happened to land in that slot.</p>
 *
 * <p>Owned by the screen, one instance per screen, so nothing survives a close.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTileSpin {

    /** Degrees per second while hovered. A full turn in twelve seconds — a drift, not a carousel. */
    private static final float SPIN_DEGREES_PER_SECOND = 30.0F;

    /** Degrees per second easing home. Faster, so a tile settles while you are still moving away. */
    private static final float RETURN_DEGREES_PER_SECOND = 120.0F;

    /**
     * The angle a tile sits at when nothing is happening to it.
     *
     * <p>Off-axis so the resting pose shows two faces of the build and reads as a solid, which a
     * head-on elevation does not.</p>
     */
    static final float REST_YAW = 35.0F;

    /** Close enough to home to stop moving, in degrees. */
    private static final float SETTLED = 0.5F;

    private final Map<String, Float> yaws = new HashMap<>();

    /**
     * Advance {@code id}'s angle for a frame and return where it now points.
     *
     * @param seconds real seconds since the last frame — real, not ticks, so the spin keeps the
     *                same speed whether the game is running at 20 fps or 200, and does not stop
     *                when the game is paused behind the screen
     */
    float advance(String id, boolean hovered, float seconds) {
        float yaw = yaws.getOrDefault(id, REST_YAW);
        float next = hovered
                ? wrap(yaw + SPIN_DEGREES_PER_SECOND * seconds)
                : towardsRest(yaw, RETURN_DEGREES_PER_SECOND * seconds);

        if (!hovered && Math.abs(shortestTo(next, REST_YAW)) < SETTLED) {
            // Settled: drop the entry so an idle screen holds no state at all.
            yaws.remove(id);
            return REST_YAW;
        }
        yaws.put(id, next);
        return next;
    }

    /** Forget every angle — the grid now shows different templates. */
    void clear() {
        yaws.clear();
    }

    /** Move {@code yaw} up to {@code step} degrees towards the rest angle, whichever way is nearer. */
    private static float towardsRest(float yaw, float step) {
        float delta = shortestTo(yaw, REST_YAW);
        if (Math.abs(delta) <= step) {
            return REST_YAW;
        }
        return wrap(yaw + Math.copySign(step, delta));
    }

    /** The signed short way round from {@code from} to {@code to}, in (-180, 180]. */
    private static float shortestTo(float from, float to) {
        float delta = (to - from) % 360.0F;
        if (delta > 180.0F) {
            delta -= 360.0F;
        } else if (delta < -180.0F) {
            delta += 360.0F;
        }
        return delta;
    }

    private static float wrap(float degrees) {
        float wrapped = degrees % 360.0F;
        return wrapped < 0.0F ? wrapped + 360.0F : wrapped;
    }
}
