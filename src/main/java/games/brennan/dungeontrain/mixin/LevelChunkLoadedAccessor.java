package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Whether a chunk is still in its level. {@code ChunkMap}'s unload clears it just before the final save,
 * which is how {@link ChunkMapUntouchedSaveSkipMixin} tells a chunk leaving from a periodic save.
 */
@Mixin(LevelChunk.class)
public interface LevelChunkLoadedAccessor {

    @Accessor("loaded")
    boolean dungeontrain$isLoaded();
}
