package games.brennan.dungeontrain.mixin.client.distanthorizons;

import games.brennan.dungeontrain.client.DistantHorizonsQueuePause;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Distant Horizons' chunk-update queue thread spinning. Each time
 * {@code tryDispatchReadyChunkUpdate} puts an item back (still inside its 250 ms debounce, or no LOD
 * executor to take it), the queue thread now pauses {@link DistantHorizonsQueuePause#PAUSE_MILLIS}
 * before its next pass instead of going straight round. Measured on DH 3.3.3 riding the train:
 * ~3 million passes a second and ~40% of a core before, a fraction of a percent after, with the queue
 * still keeping up.
 *
 * <p>The pause runs in {@code runQueueingLoop}, which only DH's own queue thread executes, never in
 * {@code processQueue}, so no game thread can be made to wait. DH's types are not on DT's compile
 * classpath, so they appear only in descriptor strings. Both injectors are {@code require = 0}: if a DH
 * update renames either site, DH simply runs as shipped. {@code DistantHorizonsMixinPlugin} only applies
 * this when DH is installed and its jar has the target class, and logs when it does.</p>
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager", remap = false)
public abstract class DhChunkUpdateQueueSpinMixin {

    /** Set when the last pass put an item back. Only DH's queue thread reads or writes it. */
    @Unique
    private boolean dungeontrain$requeued;

    @Inject(method = "tryDispatchReadyChunkUpdate",
        at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/api/internal/chunkUpdating/ChunkPosQueue;addItem(Lcom/seibel/distanthorizons/core/pos/DhChunkPos;Lcom/seibel/distanthorizons/core/api/internal/chunkUpdating/ChunkUpdateData;)V",
            shift = At.Shift.AFTER),
        require = 0, remap = false)
    private void dungeontrain$markRequeued(CallbackInfo ci) {
        dungeontrain$requeued = true;
    }

    @Inject(method = "runQueueingLoop",
        at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/api/internal/chunkUpdating/ChunkUpdateQueueManager;processQueue()V",
            shift = At.Shift.AFTER),
        require = 0, remap = false)
    private void dungeontrain$pauseAfterRequeue(CallbackInfo ci) {
        if (!dungeontrain$requeued) return;
        dungeontrain$requeued = false;
        DistantHorizonsQueuePause.pause();
    }
}
