package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.event.WitherRoseCredit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes who is standing in a wither rose, so a Wither death credits the player who placed it
 * ({@link WitherRoseCredit}). Observes only; the rose behaves as vanilla.
 */
@Mixin(WitherRoseBlock.class)
public abstract class WitherRoseBlockCreditMixin {

    @Inject(method = "entityInside", at = @At("HEAD"))
    private void dungeontrain$noteRoseContact(BlockState state, Level level, BlockPos pos, Entity entity,
                                              CallbackInfo ci) {
        WitherRoseCredit.onEntityInside(level, pos, entity);
    }
}
