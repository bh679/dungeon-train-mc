package games.brennan.dungeontrain.client.tributeroom;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.VistaRenderPass;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The zoom out of the TV as a visitor arrives: the field of view starts narrow, so the screen is all
 * they see, and eases out to whatever the game would have used — the configured FOV with its sprint
 * and speed modifiers, and any other mod's say — over {@link #EASE_MS}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TributeArrivalFov {

    /** Where the zoom starts, in degrees. A 3-wide screen a block and a half away more than fills this. */
    public static final double START_FOV = 30.0;
    public static final long EASE_MS = 1500L;

    private static long easeStartMs = -1L;

    private TributeArrivalFov() {}

    /** Start the zoom out now. */
    public static void begin(long nowMs) {
        easeStartMs = nowMs;
    }

    public static void reset() {
        easeStartMs = -1L;
    }

    /** 0 at the start of the ease, 1 once it is over (or when none is running). Smoothstep. */
    static double progress(long nowMs) {
        if (easeStartMs < 0) return 1.0;
        double t = Mth.clamp((nowMs - easeStartMs) / (double) EASE_MS, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** The FOV to draw with at {@code progress} of the ease, given what the game would use. */
    static double fovAt(double progress, double computed) {
        return Mth.lerp(progress, START_FOV, computed);
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
