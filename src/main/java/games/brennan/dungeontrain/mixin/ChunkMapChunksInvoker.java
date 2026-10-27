package games.brennan.dungeontrain.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code ChunkMap#getChunks()} (protected): every chunk holder the level has loaded. Used by the
 * difficulty swap ({@code event/DifficultyWorldLootEvents}) to reach every loaded chunk's containers,
 * not just the ones near a player.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapChunksInvoker {

    @Invoker("getChunks")
    Iterable<ChunkHolder> dungeontrain$getChunks();
}
