package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.InitialScreensCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a copy of vanilla's initial-screen task so {@link InitialScreensCapture} can hand it back
 * if another mod swallows it.
 *
 * <p>Vanilla's {@code onGameLoadFinished} builds one {@link Runnable} (title screen, or the quick-play
 * join, behind NeoForge's loading-warnings screen) and calls {@code run()} on it. Distant Horizons
 * {@code @Redirect}s that call and, when its updater has a release to offer, runs its update prompt in
 * place of the task. Both injections here sit on different instructions from DH's redirect, so the two
 * mods never contend for the same target: the capture is at the end of {@code buildInitialScreens}
 * (before DH can act) and the clear is at the end of {@code onGameLoadFinished} (after DH's prompt —
 * and any suppression of it — has run synchronously).</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInitialScreensMixin {

    @Inject(method = "buildInitialScreens", at = @At("RETURN"))
    private void dungeontrain$captureInitialScreens(CallbackInfoReturnable<Runnable> cir) {
        InitialScreensCapture.capture(cir.getReturnValue());
    }

    @Inject(method = "onGameLoadFinished", at = @At("TAIL"))
    private void dungeontrain$forgetInitialScreens(CallbackInfo ci) {
        InitialScreensCapture.clear();
    }
}
