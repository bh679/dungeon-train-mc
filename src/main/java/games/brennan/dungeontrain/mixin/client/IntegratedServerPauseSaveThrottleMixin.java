package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.PauseSaveThrottle;
import net.minecraft.client.server.IntegratedServer;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips vanilla's save-on-pause when the world was saved less than a minute ago — see
 * {@link PauseSaveThrottle}. Wraps the only {@code saveEverything} call in
 * {@code IntegratedServer.tickServer} (the not-paused → paused edge); {@code tickPaused()} and the
 * pause itself are untouched.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerPauseSaveThrottleMixin {

    @Unique
    private static final Logger DUNGEONTRAIN$LOGGER = LogUtils.getLogger();

    @WrapOperation(
        method = "tickServer",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;saveEverything(ZZZ)Z"))
    private boolean dungeontrain$throttlePauseSave(IntegratedServer server, boolean suppressLog,
                                                   boolean flush, boolean forced, Operation<Boolean> original) {
        long now = System.nanoTime();
        if (PauseSaveThrottle.allowPauseSave(now)) {
            return original.call(server, suppressLog, flush, forced);
        }
        DUNGEONTRAIN$LOGGER.info("[DT-PauseSave] skipped pause save (last save {}s ago)",
            PauseSaveThrottle.secondsSinceLastSave(now));
        return false;
    }
}
