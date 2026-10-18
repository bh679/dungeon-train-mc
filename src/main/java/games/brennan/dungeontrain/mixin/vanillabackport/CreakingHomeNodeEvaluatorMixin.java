package games.brennan.dungeontrain.mixin.vanillabackport;

import com.blackgear.vanillabackport.common.level.entities.creaking.Creaking;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.compat.CreakingHeartPositions;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A heart-bound Creaking may not path more than 32 blocks from its heart. The Creaking walks in
 * world space but its home is the heart's block position — plot space when the heart rides a Sable
 * carriage — so the fence is measured from the heart's world-space block instead
 * ({@link CreakingHeartPositions#worldBlock}). Only this pathing read is translated: the home position
 * itself stays the plot block, which is what the Creaking uses to look its heart up.
 */
@Mixin(targets = "com.blackgear.vanillabackport.common.level.entities.creaking.Creaking$HomeNodeEvaluator", remap = false)
public abstract class CreakingHomeNodeEvaluatorMixin {

    @WrapOperation(method = "getPathType", at = @At(value = "INVOKE",
            target = "Lcom/blackgear/vanillabackport/common/level/entities/creaking/Creaking;getHomePos()Lnet/minecraft/core/BlockPos;"))
    private BlockPos dungeontrain$homeInWorld(Creaking creaking, Operation<BlockPos> original) {
        BlockPos home = original.call(creaking);
        return home == null ? null : CreakingHeartPositions.worldBlock(creaking.level(), home);
    }
}
