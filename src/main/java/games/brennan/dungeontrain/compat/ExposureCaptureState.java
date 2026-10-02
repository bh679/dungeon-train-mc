package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BooleanSupplier;

/**
 * Whether the Exposure camera mod is taking a photo <em>right now</em>.
 *
 * <p>Exposure's capture rewrites global render state for a frame or two — field of view, gamma,
 * the HUD — and, under Sable/Veil, reads its photo straight off the main framebuffer. A ride
 * snapshot's extra render pass on one of those frames comes out at the photo's zoom and
 * brightness, and under a shader pack it bleeds into the photo too. {@code RideSnapshotCapture}
 * holds its shot while this answers true; the request stays armed and fires a frame or two later.</p>
 *
 * <p>The signal is "HUD hidden <b>and</b> Exposure overriding FOV": a capture does both, the
 * viewfinder alone only the second (ride snapshots are fine then — see
 * {@code GameRendererSnapshotMixin}), F1 alone only the first.</p>
 *
 * <p>Reached reflectively so Dungeon Train neither compiles against nor requires Exposure for this.
 * Client-only.</p>
 */
public final class ExposureCaptureState {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "exposure";
    private static final String FOV_MODIFIER_CLASS = "io.github.mortuusars.exposure.client.render.FovModifier";
    private static final String FOV_OVERRIDE_METHOD = "shouldOverride";

    private static boolean resolved = false;
    private static MethodHandle fovOverride;

    private ExposureCaptureState() {}

    public static boolean captureInFlight() {
        return captureInFlight(
            ModList.get().isLoaded(MOD_ID),
            Minecraft.getInstance().options.hideGui,
            ExposureCaptureState::fovOverridden);
    }

    /** The decision on its own; the FOV probe is only consulted once the cheap checks pass. */
    static boolean captureInFlight(boolean exposureLoaded, boolean hudHidden, BooleanSupplier fovOverridden) {
        return exposureLoaded && hudHidden && fovOverridden.getAsBoolean();
    }

    private static boolean fovOverridden() {
        MethodHandle handle = resolve();
        if (handle == null) return false;
        try {
            return (boolean) handle.invokeExact();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Exposure FOV probe failed; ride snapshots will not wait for its captures", t);
            fovOverride = null;
            return false;
        }
    }

    private static MethodHandle resolve() {
        if (resolved) return fovOverride;
        resolved = true;
        try {
            Class<?> fovModifier = Class.forName(FOV_MODIFIER_CLASS, false, ExposureCaptureState.class.getClassLoader());
            fovOverride = MethodHandles.publicLookup()
                .findStatic(fovModifier, FOV_OVERRIDE_METHOD, MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("[DungeonTrain] Exposure is installed but {}#{} was not found; "
                + "ride snapshots will not wait for its captures", FOV_MODIFIER_CLASS, FOV_OVERRIDE_METHOD, e);
        }
        return fovOverride;
    }
}
