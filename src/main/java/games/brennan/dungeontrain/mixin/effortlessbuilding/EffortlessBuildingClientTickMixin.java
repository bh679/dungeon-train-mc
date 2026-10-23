package games.brennan.dungeontrain.mixin.effortlessbuilding;

import games.brennan.dungeontrain.client.EffortlessBuildingClientGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Switches off Effortless Building's client game events outside Creative — see
 * {@link EffortlessBuildingClientGate}.
 *
 * <p>{@code onClientTick} is where the mod opens its Alt radial menu and modifier screen, sends
 * undo / redo, and feeds clicks into a shape; {@code onRenderGui} and {@code onRenderLevel} draw its
 * HUD and the shape previews. Skipping all three leaves nothing of the mod on screen.</p>
 *
 * <p>Same fragility contract as {@link EffortlessBuildingPacketHandlerMixin}: read out of the pinned
 * {@code effortlessbuilding-4.2+1.21.1}, {@code required: false}, {@code remap = false}. Client-only
 * (the target is a client event subscriber), so it is listed under {@code "client"}.</p>
 */
@Mixin(targets = "neoforge.nl.requios.effortlessbuilding.NeoForgeClientSetup$GameEvents", remap = false)
public abstract class EffortlessBuildingClientTickMixin {

    @Inject(method = "onClientTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$onlyInCreativeTick(@Coerce Object event, CallbackInfo ci) {
        if (EffortlessBuildingClientGate.isBlocked()) {
            EffortlessBuildingClientGate.onBlockedTick();
            ci.cancel();
        } else {
            EffortlessBuildingClientGate.onAllowedTick();
        }
    }

    @Inject(method = {"onRenderGui", "onRenderLevel"}, at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$onlyInCreativeRender(@Coerce Object event, CallbackInfo ci) {
        if (EffortlessBuildingClientGate.isBlocked()) ci.cancel();
    }
}
