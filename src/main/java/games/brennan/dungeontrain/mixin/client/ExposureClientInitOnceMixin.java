package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.SetupOnce;
import io.github.mortuusars.exposure.ExposureClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs Exposure's client init once per launch. Everything it fills (camera poses, viewfinders,
 * capture templates, photograph styles, item properties) is static and outlives a setup re-run,
 * and its registries throw on repeats; see {@link SetupOnce}.
 */
@Mixin(value = ExposureClient.class, remap = false)
public abstract class ExposureClientInitOnceMixin {

    @Unique
    private static final AtomicBoolean dungeontrain$initDone = new AtomicBoolean();

    @Inject(method = "init", at = @At("HEAD"), cancellable = true, require = 0)
    private static void dungeontrain$initOnce(CallbackInfo ci) {
        if (!SetupOnce.first(dungeontrain$initDone, "Exposure client init")) ci.cancel();
    }
}
