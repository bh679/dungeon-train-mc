package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.building.BuildingWorldgen;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fills the new-building slot: when a pool's roll lands on {@link BuildingWorldgen#SLOT_TEMPLATE} — the one
 * placeholder the {@code dungeontrain:lost_city/player_building} pool holds — it becomes one of this world's
 * new buildings ({@link BuildingWorldgen#pick}). The roster is read at worldgen time, not baked into the pool,
 * so a building saved in the editor joins the city without a datapack reload.
 */
@Mixin(StructureTemplatePool.class)
public abstract class StructureTemplatePoolBuildingMixin {

    @Inject(method = "getRandomTemplate", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$pickNewBuilding(RandomSource random, CallbackInfoReturnable<StructurePoolElement> cir) {
        if (BuildingWorldgen.isSlot(cir.getReturnValue())) {
            cir.setReturnValue(BuildingWorldgen.pick(random));
        }
    }
}
