package games.brennan.dungeontrain.mixin;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link NoiseBasedChunkGenerator}'s private {@code doFill} — the noise fill itself, without the
 * {@code fillFromNoise} wrapper that schedules it on {@code Util.backgroundExecutor()} and hands back a
 * future. {@code OfflineChunkSampler.fillGroundInline} calls it on the current thread so an End-band sample
 * generated <em>inside</em> a display chunk's own worldgen step never waits on the pool it is running on
 * (the portal-room deadlock). The two ints are the noise cell range {@code fillFromNoise} computes.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseBasedChunkGeneratorInvoker {

    @Invoker("doFill")
    ChunkAccess dungeontrain$doFill(Blender blender, StructureManager structureManager, RandomState random,
                                    ChunkAccess chunk, int cellMinY, int cellCount);
}
