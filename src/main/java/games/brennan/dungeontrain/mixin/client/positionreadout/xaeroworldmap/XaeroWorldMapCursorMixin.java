package games.brennan.dungeontrain.mixin.client.positionreadout.xaeroworldmap;

import games.brennan.dungeontrain.client.CoordinateDisguise;
import games.brennan.dungeontrain.client.MapReadoutText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Makes Xaero's World Map cursor readout ({@code X: … Y: … Z: …}) print the position the debug screen
 * prints ({@link CoordinateDisguise}), by the camera's shift, as F3 treats its Targeted Block line.
 *
 * <p>The cursor line is one of several strings {@code GuiMap.render} hands to
 * {@code MapRenderHelper.drawCenteredStringWithBackground}; {@link MapReadoutText#xaeroCursor} only
 * rewrites the one shaped like it, so the biome and zoom lines pass straight through. The fields the
 * line is built from are left alone: they also drive teleports and new waypoints, which keep the real
 * position. As of Xaero's World Map 1.47.0.</p>
 */
@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class XaeroWorldMapCursorMixin {

    @ModifyArg(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At(value = "INVOKE",
        target = "Lxaero/map/graphics/MapRenderHelper;drawCenteredStringWithBackground(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIFFFFLcom/mojang/blaze3d/vertex/VertexConsumer;)V"),
        index = 2, require = 0, remap = false)
    private String dungeontrain$disguiseCursor(String line) {
        try {
            return MapReadoutText.xaeroCursor(line, CoordinateDisguise.cameraShift());
        } catch (Throwable ignored) {
            return line;
        }
    }
}
