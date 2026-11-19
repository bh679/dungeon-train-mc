package games.brennan.dungeontrain.mixin.effortlessbuilding;

import games.brennan.dungeontrain.client.EffortlessBuildingClientGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops Effortless Building taking over clicks outside Creative — see
 * {@link EffortlessBuildingClientGate}.
 *
 * <p>The mod's own {@code MixinMinecraft} and {@code MixinBlockItem} ask these two methods before
 * swallowing a right or left click into a shape. Answering {@code false} hands every click back to
 * vanilla, so a Survival player places and breaks one block as usual, even with a build mode left
 * selected from Creative.</p>
 *
 * <p>Same fragility contract as {@link EffortlessBuildingPacketHandlerMixin}: read out of the pinned
 * {@code effortlessbuilding-4.2+1.21.1}, {@code required: false}, {@code remap = false}.</p>
 */
@Mixin(targets = "neoforge.nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient", remap = false)
public abstract class EffortlessBuildingClientInterceptMixin {

    @Inject(method = {"shouldInterceptPlacing", "shouldInterceptBreaking"}, at = @At("HEAD"),
        cancellable = true, remap = false)
    private static void dungeontrain$onlyInCreative(CallbackInfoReturnable<Boolean> cir) {
        if (EffortlessBuildingClientGate.isBlocked()) cir.setReturnValue(false);
    }
}
