package games.brennan.dungeontrain.client.shader;

import com.mojang.logging.LogUtils;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * The projection Distant Horizons is drawn with while a shader pack is running. Iris draws DH's LODs with
 * its own programs and {@code DHCompat#getProjection} — an ordinary, forward-Z perspective — rather than
 * the projection DH publishes, so DH's depth only turns back into positions through Iris' matrix. Not
 * public API, so it is reached reflectively; an Iris without it reports nothing and callers cull DH.
 *
 * <p>Iris' own handle on DH's depth texture ({@code DHCompat#getDepthTex}) is deliberately not used: it
 * is captured when the pipeline is built and can outlive the texture DH is actually drawing into, which
 * paints a frozen ghost of an old view. DH's per-frame texture id is always current.</p>
 */
public final class IrisDhDepth {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean resolved;
    private static boolean usable;
    private static Method getProjection;

    private IrisDhDepth() {}

    /** The projection Iris draws DH with, inverted into {@code out}; false if it could not be read. */
    public static boolean inverseProjection(Matrix4f out) {
        if (!resolve()) return false;
        try {
            Object projection = getProjection.invoke(null);
            if (!(projection instanceof Matrix4fc m)) return false;
            out.set(m).invert();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean resolve() {
        if (resolved) return usable;
        resolved = true;
        try {
            getProjection = Class.forName("net.irisshaders.iris.compat.dh.DHCompat").getMethod("getProjection");
            usable = true;
        } catch (Throwable t) {
            LOGGER.info("[DungeonTrain] Iris' DH projection is not reachable ({}); the void wall culls DH under packs",
                t.toString());
            usable = false;
        }
        return usable;
    }
}
