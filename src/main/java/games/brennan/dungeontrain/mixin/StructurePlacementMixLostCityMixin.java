package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.LostCityMixStarts;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mix-zone chunk that picked the Lost City is a start chunk of DT's Lost City set, whatever its grid says
 * ({@link LostCityMixStarts}). Only during {@code createStructures} ({@link ChunkGeneratorMixLostCityMixin}).
 */
@Mixin(StructurePlacement.class)
public abstract class StructurePlacementMixLostCityMixin {

    @Shadow
    protected abstract int salt();

    @Inject(method = "isStructureChunk", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$mixLostCity(ChunkGeneratorStructureState structureState, int x, int z,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && LostCityMixStarts.forcesStart(salt(), x, z)) cir.setReturnValue(true);
    }
}
