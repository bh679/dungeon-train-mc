package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.event.ResumeWatchdog;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link ResumeWatchdog} the singleplayer server ran its pause loop. While the game is paused,
 * {@code IntegratedServer.tickServer} calls {@code tickPaused()} every loop iteration instead of the
 * real tick; a server stall blocks the loop and never gets here. That is the evidence that separates
 * a resume-from-pause (hold the train, #547) from a multi-second stall between ticks (don't).
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerTickPausedMixin {

    @Inject(method = "tickPaused", at = @At("HEAD"))
    private void dungeontrain$notePausedTick(CallbackInfo ci) {
        ResumeWatchdog.notePausedTick();
    }
}
