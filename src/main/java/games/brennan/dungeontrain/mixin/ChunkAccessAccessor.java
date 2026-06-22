package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link ChunkAccess}'s raw {@code levelHeightAccessor} field. During fresh chunk
 * generation that field IS the owning {@link net.minecraft.server.level.ServerLevel} (the
 * {@code ChunkMap} passes its level into the {@code ProtoChunk} ctor), so
 * {@link NoiseBasedChunkGeneratorMixin} reads it to reach the server level from inside
 * {@code fillFromNoise}, which gets no level handle of its own.
 *
 * <p>The public {@code getHeightAccessorForGeneration()} is NOT a substitute: it returns the
 * ProtoChunk itself (or the below-zero-retrogen upgrade accessor), never the level — so the raw
 * field is the correct source.</p>
 */
@Mixin(ChunkAccess.class)
public interface ChunkAccessAccessor {

    @Accessor("levelHeightAccessor")
    LevelHeightAccessor dungeontrain$getLevelHeightAccessor();
}
