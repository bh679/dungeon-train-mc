package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.util.LevelAccelerator;
import games.brennan.dungeontrain.ship.sable.NoSyncLoadChunkAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes Sable's {@link LevelAccelerator} optionally <b>non-loading</b>, so a collision sweep
 * touching a not-yet-loaded chunk reads air instead of synchronously loading/generating the
 * chunk on the server thread.
 *
 * <p><b>Why:</b> {@code LevelAccelerator.grabChunkFast} first tries the non-blocking
 * visible-chunk-holder path, but falls back to {@code Level.getChunk(x, z)} — which on the
 * server blocks the main thread on a full chunk load <i>and generation</i>
 * ({@code ServerChunkCache.getChunkFutureMainThread}). Train sub-level plot chunks use DT's
 * expensive worldgen, so {@code SubLevelEntityCollision.collide} sweeping an item entity
 * against an unloaded plot chunk froze the whole server for up to 18s+ (captured by the
 * server stall watchdog). See {@link SubLevelEntityCollisionNoLoadMixin}, which opts the
 * per-sweep accelerator instance into this behaviour.</p>
 *
 * <p>The flag is <b>per-instance and off by default</b>: every other {@code LevelAccelerator}
 * user in Sable — including {@code setBlockFast} block writes — keeps the vanilla loading
 * behaviour, so no block write can be silently dropped into an empty chunk.</p>
 *
 * <p>Missing chunks are represented by a lazily-built {@link EmptyLevelChunk}: the
 * accelerator reads block states directly off {@code chunk.getSection(...)} (empty sections →
 * air) and fluid states via the overridden {@code getFluidState} (→ empty), so all reads are
 * safe and the sweep treats the chunk as pure air.</p>
 *
 * <p>{@code remap = false}: the target class and {@code grabChunkFast} are Sable's own names.
 * The wrapped {@code Level.getChunk(II)} call is written in Mojang mappings, which is what the
 * Sable NeoForge jar uses at runtime. Bytecode-verified against {@code sable-2.0.2+mc1.21.1}
 * ({@code grabChunkFast(IIJ)} has exactly two {@code Level.getChunk(II)} call sites: the
 * client-side branch and the server fallback — both are wrapped; the client branch passes
 * through untouched via the {@code isClientSide} guard). <b>Re-verify on any
 * {@code sable_version} bump.</b></p>
 */
@Mixin(value = LevelAccelerator.class, remap = false)
public abstract class LevelAcceleratorNoSyncLoadMixin implements NoSyncLoadChunkAccess {

    @Unique
    private boolean dungeontrain$noSyncLoad = false;

    /** Lazily-built stand-in for chunks that aren't loaded; all reads yield air/empty fluid. */
    @Unique
    private EmptyLevelChunk dungeontrain$emptyChunk = null;

    @Override
    public void dungeontrain$setNoSyncLoad(final boolean noSyncLoad) {
        this.dungeontrain$noSyncLoad = noSyncLoad;
    }

    @WrapOperation(
            method = "grabChunkFast",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"
            )
    )
    private LevelChunk dungeontrain$nonLoadingChunkLookup(final Level level, final int chunkX, final int chunkZ,
                                                          final Operation<LevelChunk> original) {
        if (!this.dungeontrain$noSyncLoad || level.isClientSide()) {
            return original.call(level, chunkX, chunkZ);
        }

        final LevelChunk loaded = ((ServerChunkCache) level.getChunkSource()).getChunkNow(chunkX, chunkZ);
        if (loaded != null) {
            return loaded;
        }

        // Chunk not loaded: treat it as air rather than sync-loading/generating on the
        // server thread. Accepted tradeoff: an item/mob may fall through train geometry
        // whose chunk hasn't loaded yet — that geometry doesn't exist server-side anyway.
        if (this.dungeontrain$emptyChunk == null) {
            final Holder<Biome> plains = level.registryAccess()
                    .registryOrThrow(Registries.BIOME)
                    .getHolderOrThrow(Biomes.PLAINS);
            this.dungeontrain$emptyChunk = new EmptyLevelChunk(level, new ChunkPos(chunkX, chunkZ), plains);
        }
        return this.dungeontrain$emptyChunk;
    }
}
