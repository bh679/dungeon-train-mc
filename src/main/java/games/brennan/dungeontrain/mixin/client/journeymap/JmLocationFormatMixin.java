package games.brennan.dungeontrain.mixin.client.journeymap;

import games.brennan.dungeontrain.client.CoordinateDisguise;
import games.brennan.dungeontrain.client.DebugScreenDepthLines;
import games.brennan.dungeontrain.client.MapReadoutText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes JourneyMap print the position the debug screen prints ({@link CoordinateDisguise}).
 *
 * <p>{@code LocationFormatKeys.format(verbose, x, z, y, vslice)} is the one formatter behind every
 * position JourneyMap prints live: the minimap's location line, the fullscreen map's player location,
 * and its mouse block info (JourneyMap 6.0.9). Note the order, z before y. The call is re-made with
 * the shifted figures, the vertical section recomputed from the shifted Y, under a guard so the
 * re-made call passes straight through. Waypoints keep their real coordinates by design.</p>
 */
@Pseudo
@Mixin(targets = "journeymap.client.ui.option.LocationFormat$LocationFormatKeys", remap = false)
public abstract class JmLocationFormatMixin {

    /** Set while the shifted call runs. Render thread only, like every caller of {@code format}. */
    @Unique
    private static boolean dungeontrain$reformatting;

    @Inject(method = "format(ZIIII)Ljava/lang/String;", at = @At("HEAD"), cancellable = true,
        require = 0, remap = false)
    private void dungeontrain$disguise(boolean verbose, int x, int z, int y, int vslice,
                                       CallbackInfoReturnable<String> cir) {
        if (dungeontrain$reformatting) return;
        try {
            DebugScreenDepthLines.Shift shift = CoordinateDisguise.cameraShift();
            if (shift.isZero()) return;
            int sx = MapReadoutText.add(x, shift.x());
            int sy = MapReadoutText.add(y, shift.y());
            int sz = MapReadoutText.add(z, shift.z());
            dungeontrain$reformatting = true;
            cir.setReturnValue(((journeymap.client.ui.option.LocationFormat.LocationFormatKeys) (Object) this)
                .format(verbose, sx, sz, sy, MapReadoutText.section(sy)));
        } catch (Throwable ignored) {
            // A readout: anything unexpected leaves JourneyMap's own, true, figures standing.
        } finally {
            dungeontrain$reformatting = false;
        }
    }
}
