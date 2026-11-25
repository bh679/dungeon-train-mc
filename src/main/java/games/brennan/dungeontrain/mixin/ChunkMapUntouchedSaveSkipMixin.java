package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.UntouchedChunkSaves;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves an untouched overworld chunk off the disk ({@link UntouchedChunkSaves}). Answering {@code false}
 * is vanilla's own "nothing written": the chunk stays marked unsaved, so it is still written if it is
 * touched, held by a dimensional carriage, or near a player at shutdown when it is next judged.
 *
 * <p><b>Judged once per cooldown, not every tick.</b> Vanilla's {@code processUnloads} walks the visible
 * chunks every tick until 20 saves succeed, and only a successful save sets the chunk's 10 s save
 * cooldown. A skipped chunk got none, so every loaded untouched chunk was judged every tick. A skip from a
 * periodic save now sets the same cooldown vanilla gives a saved chunk. The unload save and the flush
 * save at shutdown call {@code save} directly and ignore cooldowns, so neither is delayed.</p>
 *
 * <p><b>Unloading.</b> Vanilla's unload clears {@code LevelChunk#loaded} just before its final save; a
 * {@code ProtoChunk} reaches {@code save} only from there ({@code ImposterProtoChunk} is never unsaved).
 * Only a skip on that save records a drop, and the chunk's cached terrain verdict is forgotten then.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapUntouchedSaveSkipMixin {

    /** Vanilla's own save cooldown, in ms ({@code ChunkMap#saveChunkIfNeeded}). */
    @Unique
    private static final long DUNGEONTRAIN$SKIP_COOLDOWN_MS = 10_000L;

    @Shadow @Final ServerLevel level;

    @Shadow @Final private Long2LongMap chunkSaveCooldowns;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipUntouched(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        boolean unloading = !(chunk instanceof LevelChunk levelChunk)
                || !((LevelChunkLoadedAccessor) levelChunk).dungeontrain$isLoaded();
        long key = chunk.getPos().toLong();
        if (chunk.isUnsaved() && UntouchedChunkSaves.shouldSkipSave(this.level, chunk, unloading)) {
            if (!unloading) this.chunkSaveCooldowns.put(key, System.currentTimeMillis() + DUNGEONTRAIN$SKIP_COOLDOWN_MS);
            cir.setReturnValue(false);
        }
        if (unloading) UntouchedChunkSaves.onUnload(key);
    }
}
