package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import games.brennan.dungeontrain.ship.sable.SableStorageSync;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs each Sable holding save's duration and how many storage files it synced and skipped
 * (#1508): WARN when slower than {@link SableStorageSync#SLOW_SAVE_LOG_MS}, DEBUG otherwise (so
 * bug reports' attached {@code debug.log} carries every save). A bug report's log tail is only 512 KB
 * and a thread dump can fill it, so this one line is what tells us whether a player's "Saving
 * world" freeze is still Sable's save — and whether {@link SubLevelStorageFileNoDsyncMixin} is
 * doing its job.
 *
 * <p>{@code saveAll} runs on the server thread from {@code ServerLevel.save}; the counters are
 * reset at HEAD and read at RETURN, so they cover exactly this save. An exception out of
 * {@code saveAll} skips the log, which is fine — Sable logs its own save failures.</p>
 *
 * <p><b>Re-verify on any {@code sable_version} bump.</b> {@code remap = false}: Sable's own names.</p>
 */
@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class SubLevelHoldingChunkMapSaveTimingMixin {

    @Unique
    private static final Logger DUNGEONTRAIN$LOGGER = LoggerFactory.getLogger("DungeonTrain/SableSave");

    @Shadow
    @Final
    private ServerLevel level;

    @Unique
    private long dungeontrain$saveStartNanos;

    @Inject(method = "saveAll", at = @At("HEAD"))
    private void dungeontrain$startSaveTimer(CallbackInfo ci) {
        SableStorageSync.beginSave();
        dungeontrain$saveStartNanos = System.nanoTime();
    }

    @Inject(method = "saveAll", at = @At("RETURN"))
    private void dungeontrain$logSlowSave(CallbackInfo ci) {
        long elapsedMs = (System.nanoTime() - dungeontrain$saveStartNanos) / 1_000_000L;
        String message = "[DT-SableSave] saveAll {} took {} ms ({} files synced, {} unchanged skipped)";
        Object[] args = {level.dimension().location(), elapsedMs,
            SableStorageSync.forcedSinceBegin(), SableStorageSync.skippedSinceBegin()};
        if (elapsedMs >= SableStorageSync.SLOW_SAVE_LOG_MS) {
            DUNGEONTRAIN$LOGGER.warn(message, args);
        } else {
            DUNGEONTRAIN$LOGGER.debug(message, args);
        }
    }
}
