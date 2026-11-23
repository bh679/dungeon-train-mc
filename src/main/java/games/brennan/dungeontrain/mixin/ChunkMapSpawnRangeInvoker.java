package games.brennan.dungeontrain.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code ChunkMap#anyPlayerCloseEnoughForSpawning} (package-private): vanilla's "is a player
 * near enough" check, which decides whether {@code ServerChunkCache#tickChunks} random-ticks a chunk.
 * {@code portal/PortalTwinRandomTicks} asks it so it never ticks a chunk vanilla is already ticking.
 * Calling through the invoker still runs Sable's own injection on that method, so a ship's plot chunk
 * answers the way vanilla's loop sees it.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapSpawnRangeInvoker {

    @Invoker("anyPlayerCloseEnoughForSpawning")
    boolean dungeontrain$anyPlayerCloseEnoughForSpawning(ChunkPos chunkPos);
}
