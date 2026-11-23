package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.client.ClientPortalRoomSky;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lifts vanilla's near-the-floor fog darkening inside a room lit by an ordinary overworld sky.
 *
 * <p>{@code setupColor} scales the fog colour by {@code ((cameraY - minBuildHeight) * clearColorScale)²}
 * while that is under 1, and {@code clearColorScale} is 1/32 outside flat worlds. Dimensional rooms
 * and tribute visits stand in the basement only a few blocks above the floor, so the fog came out
 * nearly black. Clouds and the far sky are drawn through it, which is why they looked dark under a
 * Daylight room.</p>
 *
 * <p>Raising the scale with {@link ClientPortalRoomSky#overworldSkyWeight()} brings the factor up to
 * 1 as the room's ease comes in, so walking in and out stays smooth. Everywhere else the weight is 0
 * and vanilla's value goes through untouched.</p>
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererRoomSkyMixin {

    /** Added to the scale at full weight: enough that any camera above the floor reads a factor of 1. */
    private static final float DUNGEONTRAIN_LIFT = 1.0f;

    @ModifyExpressionValue(
            method = "setupColor",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;getClearColorScale()F"
            )
    )
    private static float dungeontrain$liftRoomFog(float original) {
        float w = ClientPortalRoomSky.overworldSkyWeight();
        return w <= 0.0f ? original : original + DUNGEONTRAIN_LIFT * w;
    }
}
