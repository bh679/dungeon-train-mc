package games.brennan.dungeontrain.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code ChunkMap#getChunks()} (protected): every chunk holder the level has loaded. Used by the
 * difficulty swap ({@code event/DifficultyWorldLootEvents}) to reach every loaded chunk's containers,
 * not just the ones near a player.
 *
 * <p>Also exposes {@code ChunkMap#getVisibleChunkIfPresent(long)} (protected): one holder from the
 * volatile visible snapshot, readable from any thread. Used by
 * {@code worldgen/OffThreadStructureChunks} to answer an off-thread structure lookup without handing
 * a chunk load to the server thread.</p>
 */
@Mixin(ChunkMap.class)
public interface ChunkMapChunksInvoker {

    @Invoker("getChunks")
    Iterable<ChunkHolder> dungeontrain$getChunks();

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder dungeontrain$getVisibleChunkIfPresent(long chunkPos);
}
