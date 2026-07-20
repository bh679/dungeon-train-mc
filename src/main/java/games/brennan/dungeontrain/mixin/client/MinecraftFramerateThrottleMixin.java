package games.brennan.dungeontrain.mixin.client;

import com.mojang.blaze3d.platform.Window;
import games.brennan.dungeontrain.client.FramerateThrottle;
import games.brennan.dungeontrain.client.VrCompat;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps the render framerate while the client is paused, unfocused, or minimised — see
 * {@link FramerateThrottle} for why vanilla 1.21.1 leaves the render loop uncapped in
 * those states.
 *
 * <p>Deliberately stateless. An earlier option was to hook NeoForge's
 * {@code ClientPauseChangeEvent} and call {@code window.setFramerateLimit()}, but that
 * has to save and restore the player's value — and {@code Options#framerateLimit}'s
 * setter writes straight to the window, so editing the video setting while paused would
 * be clobbered on restore. Overriding the getter instead means there is nothing to
 * restore, and it works on "Unlimited" (260) where the vanilla limiter is skipped
 * entirely.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftFramerateThrottleMixin {

    @Shadow public abstract boolean isPaused();

    @Shadow public abstract boolean isWindowActive();

    @Shadow public abstract Window getWindow();

    /**
     * Only overrides the return value when the throttle actually lowers it. That guard also keeps
     * vanilla's main-menu behaviour intact: when idle we must not return the window limit in place
     * of vanilla's 60 fps menu cap, and leaving the value untouched lets the original method run.
     */
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$throttleWhileIdle(CallbackInfoReturnable<Integer> cir) {
        // Client config loads early but isn't guaranteed ready before the first frame draws
        // (see ClientDisplayConfig#isLoaded) — stay out of the way until it is.
        if (!ClientDisplayConfig.isLoaded()) return;

        int vanillaLimit = getWindow().getFramerateLimit();
        int limit = FramerateThrottle.decide(
                isPaused(),
                isWindowActive(),
                ClientDisplayConfig.isFramerateThrottleEnabled(),
                VrCompat.isVivecraftPresent(),
                ClientDisplayConfig.getFramerateThrottleFps(),
                vanillaLimit);

        if (limit != vanillaLimit) {
            cir.setReturnValue(limit);
        }
    }
}
