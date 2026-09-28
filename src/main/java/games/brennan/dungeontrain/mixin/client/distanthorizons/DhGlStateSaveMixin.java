package games.brennan.dungeontrain.mixin.client.distanthorizons;

import games.brennan.dungeontrain.client.gl.SafeGlGet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops Distant Horizons corrupting native memory on macOS. DH's {@code GLState.saveState()} reads
 * {@code GL_POLYGON_MODE} through LWJGL's one-int {@code glGetInteger}, and Apple's driver writes two
 * ints — 4 bytes past the render thread's stack buffer, every frame. Each read is routed through
 * {@link SafeGlGet}, which gives the driver room for the extra value.
 *
 * <p>The class moved between DH lines (2.x {@code core.render.glObject}, 3.x
 * {@code common.render.openGl.glObject}) and so did the LWJGL owner of the call (GL32 in 2.x, GL33 in
 * 3.x); only one redirect matches per version, hence {@code require = 0}. Gated on DH being installed by
 * {@code DistantHorizonsMixinPlugin}.</p>
 */
@Pseudo
@Mixin(targets = {
    "com.seibel.distanthorizons.common.render.openGl.glObject.GLState",
    "com.seibel.distanthorizons.core.render.glObject.GLState"
}, remap = false)
public abstract class DhGlStateSaveMixin {

    @Redirect(method = "saveState",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33;glGetInteger(I)I"),
        require = 0, remap = false)
    private int dungeontrain$safeGetIntegerGl33(int pname) {
        return SafeGlGet.getInteger(pname);
    }

    @Redirect(method = "saveState",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL32;glGetInteger(I)I"),
        require = 0, remap = false)
    private int dungeontrain$safeGetIntegerGl32(int pname) {
        return SafeGlGet.getInteger(pname);
    }
}
