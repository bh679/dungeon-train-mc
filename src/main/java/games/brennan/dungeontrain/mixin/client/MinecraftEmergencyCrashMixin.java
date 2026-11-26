package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.crash.CrashRunTracker;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link CrashRunTracker} the game is crashing before vanilla's emergency save disconnects.
 *
 * <p>A Java exception on the client thread reaches {@code Minecraft#emergencySaveAndCrash}, whose
 * {@code emergencySave} halts the integrated server and then disconnects. The disconnect fires
 * {@code ClientPlayerNetworkEvent.LoggingOut}, which on its own reads as a clean exit and would clear
 * the crash record just before the crash report is written, so the next launch offered nothing.
 * Only this method: the out-of-memory path calls {@code emergencySave} alone and carries on.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftEmergencyCrashMixin {

    @Inject(method = "emergencySaveAndCrash", at = @At("HEAD"))
    private void dungeontrain$markCrashing(CrashReport report, CallbackInfo ci) {
        CrashRunTracker.markCrashing();
    }
}
