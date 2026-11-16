package games.brennan.dungeontrain.client.tributeroom;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.VistaRenderPass;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The zoom out of the TV as a visitor arrives: the field of view starts at exactly the angle the TV's
 * picture fills ({@link #fillFov}), so the menu dissolving off the window lands on the same picture at
 * the same size, and eases out to whatever the game would have used — the configured FOV with its
 * sprint and speed modifiers, and any other mod's say — over {@link #EASE_MS}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TributeArrivalFov {

    /** Where the zoom starts, in degrees, when the TV's geometry is not known. */
    public static final double FALLBACK_START_FOV = 30.0;
    public static final long EASE_MS = 1500L;

    private static long easeStartMs = -1L;
    private static double startFov = FALLBACK_START_FOV;

    private TributeArrivalFov() {}

    /** Start the zoom out now, from {@code fromFov} degrees (a non-finite or out-of-range value falls back). */
    public static void begin(long nowMs, double fromFov) {
        easeStartMs = nowMs;
        startFov = Double.isFinite(fromFov) && fromFov > 1.0 && fromFov < 170.0 ? fromFov : FALLBACK_START_FOV;
    }

    public static void reset() {
        easeStartMs = -1L;
        startFov = FALLBACK_START_FOV;
    }

    /**
     * The vertical field of view, in degrees, at which a picture of {@code pictureAspect}, contain-fitted
     * inside the bezel of a screen {@code across} by {@code down} blocks, exactly fills the view's height
     * from {@code distance} blocks away. The picture is the window's own shape, so filling its height
     * fills its width too: the frame on the TV is the frame that was on the window.
     */
    static double fillFov(int across, int down, double pictureAspect, double distance) {
        double innerW = across - 2 * TvPointer.BEZEL, innerH = down - 2 * TvPointer.BEZEL;
        double pictureH = pictureAspect > innerW / innerH ? innerW / pictureAspect : innerH;
        return Math.toDegrees(2.0 * Math.atan(pictureH / 2.0 / Math.max(0.1, distance)));
    }

    /** 0 at the start of the ease, 1 once it is over (or when none is running). Smoothstep. */
    static double progress(long nowMs) {
        if (easeStartMs < 0) return 1.0;
        double t = Mth.clamp((nowMs - easeStartMs) / (double) EASE_MS, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** The FOV to draw with at {@code progress} of the ease, given what the game would use. */
    static double fovAt(double progress, double computed) {
        return Mth.lerp(progress, startFov, computed);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (easeStartMs < 0) return;
        if (VistaRenderPass.isNestedRender()) return;
        double p = progress(System.currentTimeMillis());
        if (p >= 1.0) {
            easeStartMs = -1L;
            return;
        }
        event.setFOV(fovAt(p, event.getFOV()));
    }
}
