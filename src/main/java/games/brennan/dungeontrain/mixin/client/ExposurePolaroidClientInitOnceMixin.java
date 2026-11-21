package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.SetupOnce;
import io.github.mortuusars.exposure_polaroid.ExposurePolaroidClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs Exposure Polaroid's client init once per launch. Everything it fills (instant camera poses,
 * viewfinder, capture template, item properties) is static and outlives a setup re-run,
 * and its registries throw on repeats; see {@link SetupOnce}.
 */
@Mixin(value = ExposurePolaroidClient.class, remap = false)
public abstract class ExposurePolaroidClientInitOnceMixin {

    @Unique
    private static final AtomicBoolean dungeontrain$initDone = new AtomicBoolean();

    @Inject(method = "init", at = @At("HEAD"), cancellable = true, require = 0)
    private static void dungeontrain$initOnce(CallbackInfo ci) {
        if (!SetupOnce.first(dungeontrain$initDone, "Exposure Polaroid client init")) ci.cancel();
    }
}
