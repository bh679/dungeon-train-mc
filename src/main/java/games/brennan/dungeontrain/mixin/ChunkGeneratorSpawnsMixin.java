package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.worldgen.VanillaBiomeTwins;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Outside the WWOO stretch a WWOO-changed biome spawns vanilla's mobs (plus anything biome
 * modifiers added), not WWOO's list — see {@link VanillaBiomeTwins#spawnsFor}. Wraps the biome
 * lookup at the end of {@code getMobsAt}, after vanilla has already honoured structure spawn
 * overrides. {@code WrapOperation} so other mods hooking the same call still run.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorSpawnsMixin {

    @WrapOperation(method = "getMobsAt",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getMobSettings()Lnet/minecraft/world/level/biome/MobSpawnSettings;"))
    private MobSpawnSettings dungeontrain$twinSpawns(Biome biome, Operation<MobSpawnSettings> original,
                                                     Holder<Biome> holder, StructureManager structureManager,
                                                     MobCategory category, BlockPos pos) {
        MobSpawnSettings twin = VanillaBiomeTwins.spawnsFor(biome, pos.getX());
        return twin != null ? twin : original.call(biome);
    }
}
