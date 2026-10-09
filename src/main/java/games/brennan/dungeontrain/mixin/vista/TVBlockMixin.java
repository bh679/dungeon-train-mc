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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import games.brennan.dungeontrain.registry.ModItems;
import net.mehvahdjukaar.vista.common.connection.IConnectedBlock;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.world.level.block.Block;

/**
 * Vista TVs as latches: a redstone pulse or an empty-hand click toggles them, a steady signal is not
 * required — see {@link TvPowerToggle}. Vista's own grid propagation, sounds and screen merging are
 * untouched; only the one {@code hasNeighborSignal} read its neighbour handler makes is answered by
 * the latch instead of the wire.
 *
 * <p>Click rules: empty hand → toggle power. Sneak + empty hand keeps Vista's behaviour (pause while
 * playing, eject the cassette while off). A cassette in hand still inserts.</p>
 *
 * <p>A TV that comes on with an empty slot tunes itself: the server puts a Live Feed Cassette in, so
 * switching any TV on shows the broadcast (or the replay) with no setup at all.</p>
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

    @Inject(method = "neighborChanged", at = @At("TAIL"))
    private void dungeontrain$autoTune(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                       boolean movedByPiston, CallbackInfo ci) {
        if (level.isClientSide) return;
        BlockState now = level.getBlockState(pos);
        if (!now.is(state.getBlock()) || !now.getValue(TVBlock.POWER_STATE).isOn()) return;
        if (((IConnectedBlock) (Object) this).findMasterBlockEntity(level, pos, now) instanceof TVBlockEntity tv
                && tv.getItem(0).isEmpty()) {
            tv.setItem(0, new ItemStack(ModItems.LIVE_CASSETTE.get()));
            tv.setChanged();
            level.sendBlockUpdated(tv.getBlockPos(), tv.getBlockState(), tv.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
