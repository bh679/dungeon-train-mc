package games.brennan.dungeontrain.mixin.wover;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.gui.screens.Screen;
import org.betterx.wover.ui.impl.client.VersionCheckerClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Function;

/**
 * Stops WorldWeaver (the BetterNether/BetterEnd: New Dawn library) queueing its startup popups. On first
 * launch {@code presentUpdateScreen} adds the "Welcome to BetterX" screen, and whenever its version checker
 * finds newer BetterX builds it adds an updates screen. Neither makes sense in DT: BetterX is a pinned
 * dependency the player never chose or updates themselves. Cancelling at HEAD also skips WorldWeaver's
 * network version check. Its experimental-settings warning is a separate hook and is not affected.
 *
 * <p>Only while {@link ClientDisplayConfig#isBetterXStartupScreensSuppressed()} is on (the default). This
 * runs at the title screen, before any world exists, so it's a per-install setting, not a DT-world check.</p>
 */
@Mixin(value = VersionCheckerClient.class, remap = false)
public abstract class WoverStartupScreensMixin {

    @Inject(method = "presentUpdateScreen", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$skipStartupScreens(List<Function<Runnable, Screen>> screens, CallbackInfo ci) {
        if (ClientDisplayConfig.isBetterXStartupScreensSuppressed()) {
            ci.cancel();
        }
    }
}
