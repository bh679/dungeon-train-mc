package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.UntouchedChunkSaves;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves an untouched overworld chunk off the disk once no player is near it ({@link UntouchedChunkSaves}).
 * Answering {@code false} is vanilla's own "nothing written": the unload path drops the chunk anyway, and
 * the chunk stays marked unsaved, so it is still written if a player comes back before it unloads.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapUntouchedSaveSkipMixin {

    @Shadow @Final ServerLevel level;

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipUntouched(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (chunk.isUnsaved() && UntouchedChunkSaves.shouldSkipSave(this.level, chunk)) cir.setReturnValue(false);
    }
}
