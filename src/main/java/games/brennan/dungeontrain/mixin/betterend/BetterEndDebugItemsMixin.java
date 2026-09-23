package games.brennan.dungeontrain.mixin.betterend;

import org.betterx.betterend.registry.EndItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops BetterEnd: New Dawn generating its dev-only debug items. {@code EndItems.ensureStaticallyLoaded}
 * does nothing else: when {@code BCLib.isDevEnvironment()} (= {@code !FMLEnvironment.production}) it
 * constructs a dozen {@code DebugDataItem}s AFTER item registration has closed. Their intrusive holders
 * are then never registered, and the registry freeze aborts mod loading with "Some intrusive holders
 * were not registered", so {@code runClient}, {@code runServer} and the JUnit boot can't start at all.
 * In production the method is already a no-op, so this changes nothing for players.
 */
@Mixin(value = EndItems.class, remap = false)
public abstract class BetterEndDebugItemsMixin {

    @Inject(method = "ensureStaticallyLoaded", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$skipDevDebugItems(CallbackInfo ci) {
        ci.cancel();
    }
}
