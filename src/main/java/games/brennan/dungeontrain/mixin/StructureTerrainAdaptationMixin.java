package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.LostCityGroundProcessor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns terrain adaptation off for the Lost City's buildings.
 *
 * <p>The Big Lost City mod's structures ship {@code beard_thin}: the terrain is raised to each piece's pad
 * and carved smoothly above it, so a hillside inside the footprint is always cut away and the building
 * stands on a levelled plane. The Lost City wants the opposite — the stretch's own ground running through
 * and over its ruins — so a jigsaw structure whose start pool is the mod's answers {@code NONE}.
 * {@link LostCityGroundProcessor} then lets the template's air yield to that ground and props up any pad
 * left hanging on the downhill side. Every other structure keeps its own setting.</p>
 */
@Mixin(Structure.class)
public abstract class StructureTerrainAdaptationMixin {

    @Inject(method = "terrainAdaptation", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$lostCityNoAdaptation(CallbackInfoReturnable<TerrainAdjustment> cir) {
        if (cir.getReturnValue() == TerrainAdjustment.NONE) return;
        if (!((Object) this instanceof JigsawStructure jigsaw)) return;
        ResourceKey<StructureTemplatePool> pool = ((JigsawStructureAccessor) (Object) jigsaw).dungeontrain$startPool()
                .unwrapKey().orElse(null);
        if (pool != null && LostCityGroundProcessor.appliesTo(pool.location())) {
            cir.setReturnValue(TerrainAdjustment.NONE);
        }
    }
}
