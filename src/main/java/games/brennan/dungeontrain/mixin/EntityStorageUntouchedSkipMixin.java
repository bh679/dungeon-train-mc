package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.UntouchedChunkSaves;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Entities follow their chunk's save decision ({@link UntouchedChunkSaves#entitiesToKeep}): a dropped chunk
 * regenerates with fresh mobs, so a saved copy of the old ones would stand beside them. Only tamed and
 * named mobs are written for it, and when there are none nothing is written, so an older copy on disk is
 * left exactly as it was.
 */
@Mixin(EntityStorage.class)
public abstract class EntityStorageUntouchedSkipMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "storeEntities", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$keepOnlyPlayersOwn(ChunkEntities<Entity> entities, CallbackInfo ci) {
        if (entities.isEmpty()) return;
        List<Entity> all = entities.getEntities().toList();
        List<Entity> kept = UntouchedChunkSaves.entitiesToKeep(this.level, entities.getPos(), all);
        if (kept.size() == all.size()) return;
        ci.cancel();
        if (!kept.isEmpty()) ((EntityStorage) (Object) this).storeEntities(new ChunkEntities<>(entities.getPos(), kept));
    }
}
