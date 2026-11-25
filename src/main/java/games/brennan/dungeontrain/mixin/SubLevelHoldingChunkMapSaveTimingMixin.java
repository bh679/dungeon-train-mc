package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelTicketsSavedData;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import games.brennan.dungeontrain.ship.sable.SableStorageSync;
import games.brennan.dungeontrain.ship.sable.SableStorageSync.SavePhase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Logs each Sable holding save's duration, whether it was a flush save, how many storage files
 * it synced, deferred (autosave: left in the OS cache) and skipped, how the time split between
 * serialising, writing and syncing (#1508), and how it split between {@code saveAll}'s own calls
 * ({@link SavePhase}) and the collector: WARN when slower than {@link SableStorageSync#SLOW_SAVE_LOG_MS},
 * DEBUG otherwise (so bug reports' attached {@code debug.log} carries every save). A bug report's log
 * tail is only 512 KB and a thread dump can fill it, so this one line is what tells us whether a
 * player's "Saving world" freeze is still Sable's save, and which part of it.
 *
 * <p>The phase split exists because player autosaves of Nov 2026 took 1.0 to 1.4 s with serialise,
 * write and sync adding up to 20 to 50 ms. Each wrapper below times one call where {@code saveAll}
 * makes it; calls made anywhere else are untouched.</p>
 *
 * <p>{@code saveAll} runs on the server thread from {@code ServerLevel.save}; the counters are
 * reset at HEAD and read at RETURN, so they cover exactly this save. An exception out of
 * {@code saveAll} skips the log, which is fine: Sable logs its own save failures.</p>
 *
 * <p>Bytecode-verified against {@code sable-2.0.5+mc1.21.1}. The phase wrappers are
 * {@code require = 0}: if a Sable bump moves one, that phase reads 0 and its time lands in
 * {@code other} rather than the game failing to start. <b>Re-verify on any {@code sable_version}
 * bump.</b> {@code remap = false}: Sable's own names.</p>
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

    @Unique
    private long dungeontrain$saveStartGcMs;

    @Inject(method = "saveAll", at = @At("HEAD"))
    private void dungeontrain$startSaveTimer(CallbackInfo ci) {
        SableStorageSync.beginSave();
        dungeontrain$saveStartGcMs = SableStorageSync.gcMillisTotal();
        dungeontrain$saveStartNanos = System.nanoTime();
    }

    @Inject(method = "saveAll", at = @At("RETURN"))
    private void dungeontrain$logSlowSave(CallbackInfo ci) {
        long elapsedMs = (System.nanoTime() - dungeontrain$saveStartNanos) / 1_000_000L;
        long gcMs = Math.max(0, SableStorageSync.gcMillisTotal() - dungeontrain$saveStartGcMs);
        String message = "[DT-SableSave] saveAll {} took {} ms (flush={}; {} files synced, {} deferred, "
            + "{} unchanged skipped; serialise {} ms, write {} ms, sync {} ms; {}; gc {} ms)";
        Object[] args = {level.dimension().location(), elapsedMs, SableStorageSync.shouldForce(),
            SableStorageSync.forcedSinceBegin(), SableStorageSync.deferredSinceBegin(),
            SableStorageSync.skippedSinceBegin(), SableStorageSync.serialiseMsSinceBegin(),
            SableStorageSync.writeMsSinceBegin(), SableStorageSync.syncMsSinceBegin(),
            SableStorageSync.formatPhases(elapsedMs, SableStorageSync.phaseMsSinceBegin()), gcMs};
        if (elapsedMs >= SableStorageSync.SLOW_SAVE_LOG_MS) {
            DUNGEONTRAIN$LOGGER.warn(message, args);
        } else {
            DUNGEONTRAIN$LOGGER.debug(message, args);
        }
    }

    // ---- phases: one wrapper per call saveAll makes ----

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/sublevel/storage/holding/SubLevelHoldingChunkMap;processChanges()V"))
    private void dungeontrain$timeChanges(SubLevelHoldingChunkMap self, Operation<Void> original) {
        long t = System.nanoTime();
        try {
            original.call(self);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.CHANGES, System.nanoTime() - t);
        }
    }

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/api/SubLevelHelper;getLoadingDependencyChain(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)Ljava/util/Collection;"))
    private Collection<?> dungeontrain$timeDeps(ServerSubLevel subLevel, Operation<Collection<?>> original) {
        long t = System.nanoTime();
        try {
            return original.call(subLevel);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.DEPS, System.nanoTime() - t);
        }
    }

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/sublevel/storage/holding/SubLevelHoldingChunkMap;moveAndSaveSubLevel(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lnet/minecraft/world/level/ChunkPos;Ljava/util/List;)V"))
    private void dungeontrain$timeMove(SubLevelHoldingChunkMap self, ServerSubLevel subLevel, ChunkPos pos,
                                       List<UUID> dependencies, Operation<Void> original) {
        long t = System.nanoTime();
        try {
            original.call(self, subLevel, pos, dependencies);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.MOVE, System.nanoTime() - t);
        }
    }

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/sublevel/storage/holding/SubLevelHoldingChunkMap;moveAndSaveSubLevel(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;Ldev/ryanhcode/sable/sublevel/storage/holding/GlobalSavedSubLevelPointer;Lnet/minecraft/world/level/ChunkPos;)Ldev/ryanhcode/sable/sublevel/storage/holding/GlobalSavedSubLevelPointer;"))
    private GlobalSavedSubLevelPointer dungeontrain$timeMoveHolding(SubLevelHoldingChunkMap self, ServerSubLevel subLevel,
                                                                    SubLevelData data, GlobalSavedSubLevelPointer pointer,
                                                                    ChunkPos pos, Operation<GlobalSavedSubLevelPointer> original) {
        long t = System.nanoTime();
        try {
            return original.call(self, subLevel, data, pointer, pos);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.MOVE, System.nanoTime() - t);
        }
    }

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;attemptSaveSubLevel(Ldev/ryanhcode/sable/sublevel/storage/holding/GlobalSavedSubLevelPointer;Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;)V"))
    private void dungeontrain$timeSave(SubLevelStorage storage, GlobalSavedSubLevelPointer pointer, SubLevelData data,
                                       Operation<Void> original) {
        long t = System.nanoTime();
        try {
            original.call(storage, pointer, data);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.SAVE, System.nanoTime() - t);
        }
    }

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;attemptSaveHoldingChunk(Lnet/minecraft/world/level/ChunkPos;Ldev/ryanhcode/sable/sublevel/storage/holding/SubLevelHoldingChunk;)V"))
    private void dungeontrain$timeHoldingSave(SubLevelStorage storage, ChunkPos pos, SubLevelHoldingChunk chunk,
                                              Operation<Void> original) {
        long t = System.nanoTime();
        try {
            original.call(storage, pos, chunk);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.HOLDING, System.nanoTime() - t);
        }
    }

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;attemptRemoveHoldingChunk(Lnet/minecraft/world/level/ChunkPos;)V"))
    private void dungeontrain$timeHoldingRemove(SubLevelStorage storage, ChunkPos pos, Operation<Void> original) {
        long t = System.nanoTime();
        try {
            original.call(storage, pos);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.HOLDING, System.nanoTime() - t);
        }
    }

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;flush()V"))
    private void dungeontrain$timeFlush(SubLevelStorage storage, Operation<Void> original) {
        long t = System.nanoTime();
        try {
            original.call(storage);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.FLUSH, System.nanoTime() - t);
        }
    }

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelStorage;pruneCache()V"))
    private void dungeontrain$timePrune(SubLevelStorage storage, Operation<Void> original) {
        long t = System.nanoTime();
        try {
            original.call(storage);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.PRUNE, System.nanoTime() - t);
        }
    }

    @WrapOperation(method = "saveAll", require = 0, at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/sublevel/storage/SubLevelTicketsSavedData;getOrLoad(Lnet/minecraft/server/level/ServerLevel;)Ldev/ryanhcode/sable/sublevel/storage/SubLevelTicketsSavedData;"))
    private SubLevelTicketsSavedData dungeontrain$timeTickets(ServerLevel serverLevel,
                                                              Operation<SubLevelTicketsSavedData> original) {
        long t = System.nanoTime();
        try {
            return original.call(serverLevel);
        } finally {
            SableStorageSync.addPhaseNanos(SavePhase.TICKETS, System.nanoTime() - t);
        }
    }
}
