package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import games.brennan.dungeontrain.client.gl.DhFrameTextureClear;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Empties Distant Horizons' frame textures on a frame {@link DistantHorizonsSuppression} has told DH
 * not to draw.
 *
 * <h2>Why cancelling alone is not enough under a shader pack</h2>
 * <p>DH clears its colour and depth textures <em>inside</em> the render pass that the cancel skips, so
 * a suppressed frame leaves them holding whatever DH last drew. Without a shader pack nobody reads
 * them until DH's next real frame clears them, so the cancel is clean. Under Iris they are read every
 * frame regardless: Iris hands DH's depth texture to the pack as {@code dhDepthTex0} and its own
 * no-translucent copy as {@code dhDepthTex1}, and the pack's sky and fog composite decides where
 * terrain ends by reading them. Stale depth becomes terrain-shaped fog silhouettes frozen on the
 * screen — the "ghost terrain" seen inside dimensional carriages with DH and a pack on. Iris's own
 * hooks that would have re-attached and cleared those textures ({@code LodRendererEvents}) sit on the
 * same DH setup step the cancel skips, so they never get the chance.</p>
 *
 * <p>The fix is to do that clear ourselves: DH's two textures through its public render proxy, and
 * Iris's copy best-effort through reflection (resolved once; a rename in a future Iris only loses
 * that one sampler's clear and is logged at DEBUG). DH's own textures are cleared whether or not a
 * pack is active — the clear is cheap, and DH's fade passes run outside the cancelled path too.</p>
 *
 * <p>The GL sequence itself lives in {@link DhFrameTextureClear}, which names no DH type and is what
 * the unit test drives. This class names DH API types, so it is reached only behind the
 * {@code ModList} check that guards {@link DistantHorizonsSuppression}.</p>
 */
public final class DistantHorizonsFrameClear {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String IRIS_EVENTS_CLASS = "net.irisshaders.iris.compat.dh.LodRendererEvents";
    private static final String IRIS_DEPTH_FIELD = "depthTexNoTranslucent";
    private static final int NO_TEXTURE = 0;
    /**
     * Dev A/B seam: {@code ./gradlew runClient -PdhFrameClear=false} skips the clear so the ghost
     * terrain can be photographed on the same build that fixes it. Never set in a shipped install.
     */
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("dungeontrain.dh_frame_clear", "true"));

    private static boolean firstClearLogged;
    private static boolean irisResolved;
    private static Method irisGetInstance;
    private static Field irisDepthField;
    private static Method irisTextureId;

    private DistantHorizonsFrameClear() {}

    /** Clear DH's frame textures (and Iris's copy, if a pack is active) after a suppressed frame. */
    public static void clearSuppressedFrame() {
        if (!ENABLED) return;
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null) return;
        int depth = textureId(proxy.getDhDepthTextureId());
        int color = textureId(proxy.getDhColorTextureId());
        int iris = GraphicsCapabilities.shaderPackActive() ? irisNoTranslucentDepth() : NO_TEXTURE;
        boolean cleared = DhFrameTextureClear.clear(DhFrameTextureClear.lwjgl(), depth, color, iris);
        if (cleared && !firstClearLogged) {
            // Once per session, so a player's log shows the path is live (and whether Iris's copy
            // was reachable) without a line per frame.
            firstClearLogged = true;
            LOGGER.info("[DungeonTrain] Cleared Distant Horizons' frame textures on a suppressed frame "
                    + "(depth={}, colour={}, irisNoTranslucentDepth={})", depth, color, iris);
        }
    }

    private static int textureId(DhApiResult<Integer> result) {
        if (result == null || !result.success || result.payload == null) return NO_TEXTURE;
        return Math.max(NO_TEXTURE, result.payload);
    }

    /** Iris's {@code dhDepthTex1} texture id, or {@code 0} when it cannot be reached. */
    private static int irisNoTranslucentDepth() {
        resolveIris();
        if (irisGetInstance == null) return NO_TEXTURE;
        try {
            Object compat = irisGetInstance.invoke(null);
            if (compat == null) return NO_TEXTURE;
            Object depthTexture = irisDepthField.get(compat);
            if (depthTexture == null) return NO_TEXTURE;
            Object id = irisTextureId.invoke(depthTexture);
            return id instanceof Integer i ? Math.max(NO_TEXTURE, i) : NO_TEXTURE;
        } catch (Throwable t) {
            return NO_TEXTURE;
        }
    }

    private static synchronized void resolveIris() {
        if (irisResolved) return;
        irisResolved = true;
        try {
            Class<?> events = Class.forName(IRIS_EVENTS_CLASS);
            Method getInstance = events.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Field depthField = getInstance.getReturnType().getDeclaredField(IRIS_DEPTH_FIELD);
            depthField.setAccessible(true);
            Method textureId = depthField.getType().getMethod("getTextureId");
            irisGetInstance = getInstance;
            irisDepthField = depthField;
            irisTextureId = textureId;
        } catch (ClassNotFoundException notInstalled) {
            // Iris absent — nothing of Iris's to clear.
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Iris DH depth copy unreachable; dhDepthTex1 will not be cleared "
                    + "on suppressed Distant Horizons frames: {}", t.toString());
        }
    }

}
