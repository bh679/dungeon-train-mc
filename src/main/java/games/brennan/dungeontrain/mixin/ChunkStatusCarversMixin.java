package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.feature.NetherCoreStamp;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Stamps the Nether band's real-Nether core terrain ({@link NetherCoreStamp}) as the last act of a
 * chunk's {@code CARVERS} status — after the carvers, before any chunk of its 3×3 neighbourhood can reach
 * {@code FEATURES}. The core's decoration spills across chunk borders, so every chunk it can reach must
 * already hold its Nether terrain, or what grew against the old overworld stone is left floating once
 * that stone is replaced.
 *
 * <p>Hooked on the status task rather than {@code ChunkGenerator.applyCarvers} so it runs exactly once
 * per chunk whichever generator owns it: a preset band's second {@code NoiseBasedChunkGenerator} and the
 * legacy bands' HEAD-cancelled carver pass both come through here, and the offline samplers (which call
 * the generator directly, and in other dimensions) never do.</p>
 */
@Mixin(targets = "net.minecraft.world.level.chunk.status.ChunkStatusTasks")
public abstract class ChunkStatusCarversMixin {

    @Inject(method = "generateCarvers", at = @At("RETURN"))
    private static void dungeontrain$stampNetherCore(WorldGenContext context, ChunkStep step,
                                                     StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                                     CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        NetherCoreStamp.stampChunk(context.level(), chunk);
    }
}
