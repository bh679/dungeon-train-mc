package games.brennan.dungeontrain.compat.vista;

import com.mojang.logging.LogUtils;
import net.mehvahdjukaar.vista.client.renderer.VistaLevelRenderer;
import org.slf4j.Logger;

/**
 * Whether the level being drawn right now is one of Vista's nested renders — a TV feed or a mirror —
 * rather than the player's own view.
 *
 * <p>Vista draws each feed with a full {@code renderLevel}, so {@code RenderLevelStageEvent} fires
 * again inside it, and for the length of that pass it swaps {@code GameRenderer.mainCamera} for the
 * feed's camera. Anything that reads the main camera from a level-stage listener — the world-space
 * editor menus' hover raycasts — sees the TV's viewpoint there. Those raycasts used to reset hover to
 * nothing after every main pass, so the next click found no hovered cell and fell through to the
 * block behind the panel.</p>
 *
 * <p>Client-only: call it from render code.</p>
 */
public final class VistaRenderPass {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean unavailable;

    private VistaRenderPass() {}

    /** True while Vista is rendering a feed or reflection; false on the player's own frame. */
    public static boolean isNestedRender() {
        if (unavailable) return false;
        try {
            return VistaLevelRenderer.isRenderingLiveFeed();
        } catch (LinkageError apiChanged) {
            // A Vista update moved this seam. Fall back to treating every pass as the main one —
            // the behaviour before this check existed — rather than failing every frame.
            unavailable = true;
            LOGGER.warn("[DungeonTrain] Vista render-pass check unavailable; editor menu hover may misfire near TVs", apiChanged);
            return false;
        }
    }
}
