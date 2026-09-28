package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.compat.ModernFixStrongholdCache;
import games.brennan.dungeontrain.worldgen.StrongholdRingGate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the {@code ServerLevel} constructor's eager stronghold ring search for the overworld, so
 * {@link StrongholdRingGate#start} can run it once DT's biome context is published. Other dimensions
 * keep vanilla's eager start. Any error keeps the vanilla call, so no world is left without rings.
 *
 * <p><b>Priority 900 is load-bearing.</b> ModernFix ({@code perf.cache_strongholds}) wraps the same
 * call to record the dimension folder its ring cache lives in, and its ring search crashes with a
 * {@code null} executor if that never happened. MixinExtras nests wraps in application order — lower
 * priority applies first and sits innermost — so at 900 DT's skip runs <em>inside</em> ModernFix's
 * wrap: ModernFix records its path, then DT declines the eager search. {@link ModernFixStrongholdCache}
 * records the path again from the constructor's own arguments as a backstop that does not depend on
 * that ordering. See v0.982.0's "Exception generating new chunk" / stuck-at-33% reports.</p>
 */
@Mixin(value = ServerLevel.class, priority = 900)
public abstract class ServerLevelDeferRingGenMixin {

    @WrapWithCondition(
        method = "<init>",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;ensureStructuresGenerated()V"))
    private boolean dungeontrain$deferOverworldRings(ChunkGeneratorStructureState state,
                                                     @Local(argsOnly = true) MinecraftServer server,
                                                     @Local(argsOnly = true) LevelStorageSource.LevelStorageAccess storage,
                                                     @Local(argsOnly = true) ResourceKey<Level> dimension) {
        try {
            if (!Level.OVERWORLD.equals(dimension)) {
                return true;
            }
            ModernFixStrongholdCache.prime(state, storage, dimension, server);
            return false;
        } catch (Throwable t) {
            return true;
        }
    }
}
