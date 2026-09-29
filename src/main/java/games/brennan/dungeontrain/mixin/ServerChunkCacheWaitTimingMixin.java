package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import games.brennan.dungeontrain.perf.ServerLoadSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.BooleanSupplier;

/**
 * Times the server thread blocked on a chunk it asked for synchronously — the
 * {@code [mspt] chunkWaitMs=} / {@code chunkWaits=} fields (see {@link ServerLoadSampler}). Seven of
 * nine stall dumps in the 2026-09 Lag-log review sat in {@code ServerChunkCache.getChunk →
 * managedBlock}, but a tick that merely ran slow left no trace of it.
 *
 * <p>Pure observation: wraps {@code ServerChunkCache$MainThreadExecutor.managedBlock} and always
 * calls the original. That executor is the chunk cache's private field, and its only
 * {@code managedBlock} callers are {@code getChunk} and {@code getChunkFuture} (bytecode-verified
 * against NeoForge 21.1.228) — a cache hit never reaches it, so only real misses are timed. Worldgen
 * run inside the block can re-enter {@code getChunk} ({@code WorldgenForceGuard}); the depth counter
 * times only the outermost wait so nothing is counted twice. Only the server thread runs this
 * executor's blocks, so a plain static suffices.</p>
 *
 * <p>String target because the class is package-private. {@code require = 0}: if another mod
 * replaces the method the fields read 0 instead of the game failing to start — this is
 * diagnostics, never worth a crash.</p>
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")
public abstract class ServerChunkCacheWaitTimingMixin {

    @Unique
    private static int dungeonTrain$waitDepth;

    @WrapMethod(method = "managedBlock", require = 0)
    private void dungeonTrain$timeChunkWait(BooleanSupplier done, Operation<Void> original) {
        boolean outermost = dungeonTrain$waitDepth++ == 0;
        long start = outermost ? System.nanoTime() : 0L;
        try {
            original.call(done);
        } finally {
            dungeonTrain$waitDepth--;
            if (outermost) ServerLoadSampler.recordChunkWait(System.nanoTime() - start);
        }
    }
}
