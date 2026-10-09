package games.brennan.dungeontrain.mixin.vista;

import games.brennan.dungeontrain.compat.vista.TvPowerToggle;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vista TVs as latches: a redstone pulse or an empty-hand click toggles them, a steady signal is not
 * required — see {@link TvPowerToggle}. Vista's own grid propagation, sounds and screen merging are
 * untouched; only the one {@code hasNeighborSignal} read its neighbour handler makes is answered by
 * the latch instead of the wire.
 *
 * <p>Click rules: empty hand → toggle power. Sneak + empty hand keeps Vista's behaviour (pause while
 * playing, eject the cassette while off). A cassette in hand still inserts.</p>
 */
@Mixin(value = TVBlock.class, remap = false)
public abstract class TVBlockMixin {

    @Redirect(method = "neighborChanged",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean dungeontrain$latchedSignal(Level level, BlockPos pos, BlockState state, Level level2, BlockPos pos2) {
        boolean actual = level.hasNeighborSignal(pos);
        boolean currentlyOn = state.getValue(TVBlock.POWER_STATE).isOn();
        return TvPowerToggle.desiredSignal(level, pos, actual, currentlyOn);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$clickToggles(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                           InteractionHand hand, BlockHitResult hit,
                                           CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (!stack.isEmpty() || player.isSecondaryUseActive()) return;
        TvPowerToggle.toggle(level, pos);
        cir.setReturnValue(ItemInteractionResult.sidedSuccess(level.isClientSide));
    }
}
