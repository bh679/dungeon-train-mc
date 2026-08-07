package games.brennan.dungeontrain.mixin.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Makes a hallway portal swap land on a drawn frame instead of an empty one, by waiting out the
 * occlusion-graph rebuild the swap triggers rather than letting the frame render from a stale graph.
 *
 * <h2>What goes wrong without this</h2>
 * <p>A swap moves the camera a hundred-odd blocks down into the basement twin. {@code LevelRenderer}
 * calls {@code sectionOcclusionGraph.invalidate()} for any camera move of eight blocks or more, and
 * {@code update} then hands the rebuild to {@code Util.backgroundExecutor()} and returns immediately —
 * {@code currentGraph} still holds the graph walked from the <i>old</i> camera position, and
 * {@code visibleSections} is only re-derived once {@code consumeFrustumUpdate()} reports the task
 * done. The twin's sections were occlusion-culled, sealed under bedrock with nothing able to see into
 * them, so they are not in the stale set: for as many frames as the background task takes, nothing
 * around the player is drawn and the screen keeps the clear colour. Which colour that is depends on
 * the twin's Y lane — vanilla darkens the clear colour toward the build floor, so a low lane flashed
 * dark and a high one flashed pale. Whether it happened at all depended on thread timing, which is
 * why it was intermittent.</p>
 *
 * <h2>What this does</h2>
 * <p>On the one frame following a swap, block until the rebuild finishes. {@code update} is called
 * from {@code setupRender}, before any geometry is drawn, and the rebuild sets the frustum-update flag
 * on its way out — so {@code LevelRenderer} sees it in the same frame, rebuilds
 * {@code visibleSections} from the fresh graph, and the destination is on screen the instant the
 * player is in it. The cost is one frame's worth of a breadth-first walk that vanilla was going to do
 * anyway, just on this thread: a few milliseconds, once per swap, in exchange for the flash.</p>
 *
 * <p><b>Bounded.</b> The wait has a timeout, so a saturated background executor degrades to the old
 * behaviour — a possible flash — rather than to a frozen client. {@code require = 0} for the same
 * shape of reason: a mod that replaces the chunk renderer wholesale (Sodium, which Sable ships
 * pipeline compatibility for) leaves this with nothing to attach to, and vanishing quietly is the
 * right outcome there.</p>
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphPortalSwapMixin {

    private static final Logger DUNGEONTRAIN$LOGGER = LogUtils.getLogger();

    /**
     * Long enough that a rebuild queued behind other background work still lands, short enough that
     * the worst case reads as one stutter rather than as a hang.
     */
    private static final long DUNGEONTRAIN$WAIT_MILLIS = 100L;

    @Shadow
    @Nullable
    private Future<?> fullUpdateTask;

    @Inject(method = "update", at = @At("TAIL"), require = 0)
    private void dungeontrain$awaitRebuildAfterPortalSwap(boolean smartCull, Camera camera,
                                                          Frustum frustum,
                                                          List<SectionRenderDispatcher.RenderSection> sections,
                                                          CallbackInfo ci) {
        if (!ClientPortalSwap.consume()) return;

        Future<?> task = this.fullUpdateTask;
        // Nothing scheduled means the graph was already current for this camera — the swap moved less
        // than vanilla cares about, or a previous frame's rebuild covered it. Either way, no wait.
        if (task == null || task.isDone()) return;

        try {
            task.get(DUNGEONTRAIN$WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failed) {
            // A timeout or a failed rebuild is only a cosmetic loss — the frame may flash, as it did
            // before this existed. Logged rather than swallowed so a client that flashes every swap
            // says why in its log.
            DUNGEONTRAIN$LOGGER.debug(
                "[DungeonTrain] Portal swap: occlusion graph rebuild not ready in {} ms",
                DUNGEONTRAIN$WAIT_MILLIS, failed);
        }
    }
}
