package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.VanillaBiomeTwins;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The chunk-generation animal pass ({@code spawnOriginalMobs} → {@code spawnMobsForChunkGeneration})
 * reads the biome's spawn list itself and never asks {@code getMobsAt}, so
 * {@link ChunkGeneratorSpawnsMixin} doesn't reach it. Outside the WWOO stretch the fresh chunk's
 * herds come from the vanilla twin instead — see {@link VanillaBiomeTwins#mobSettingsAt}. Also covers
 * a dimensional carriage room, which runs the same pass on its sampled chunk.
 *
 * <p>Plain {@code @Redirect}: MixinExtras isn't on the unit-test harness's classpath.</p>
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerChunkGenSpawnsMixin {

    @Redirect(method = "spawnMobsForChunkGeneration",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getMobSettings()Lnet/minecraft/world/level/biome/MobSpawnSettings;"))
    private static MobSpawnSettings dungeontrain$twinChunkGenSpawns(Biome biome, ServerLevelAccessor level,
                                                                    Holder<Biome> holder, ChunkPos chunkPos,
                                                                    RandomSource random) {
        return VanillaBiomeTwins.mobSettingsAt(biome, chunkPos.getMiddleBlockX());
    }
}
