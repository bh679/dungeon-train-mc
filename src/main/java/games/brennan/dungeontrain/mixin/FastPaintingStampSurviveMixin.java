package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Fast Paintings' block paintings on the wall while Dungeon Train is building the room they
 * hang in — the {@link CarriageStampGuard} window.
 *
 * <p><b>Why.</b> {@code PaintingBlock.updateShape} turns a cell to air the moment the block behind
 * it is not solid, and {@code neighborChanged} then removes every other cell of the picture (its
 * {@code canSurvive} wants all of them) and drops the item. A carriage is written in passes, so a
 * picture's wall is often not there yet when the picture lands: a variant cell is air until the
 * variant pass fills it, and a wall that comes from a part arrives after the shell. The train never
 * sees this — its shell and parts are written section-local, with no shape updates — but Test the
 * Carriage, the editor plots and in-carriage part swaps stamp relit ({@code placeInWorld}'s final
 * shape pass, then {@code UPDATE_ALL} variant writes), and every such picture popped.</p>
 *
 * <p>Same rule as {@code CropBlockCarriageSurviveMixin}: the template is authoritative and our own
 * construction scaffolding is not a gameplay state. Outside the guard the mod's behaviour is
 * untouched — break the wall behind a hung picture in play and it still drops.</p>
 *
 * <p>Common list: the guard is only raised on the server thread, so on the client this is a no-op.
 * Fast Paintings is a hard dependency, so the target always exists.</p>
 */
@Mixin(targets = "net.mehvahdjukaar.fastpaintings.PaintingBlock")
public abstract class FastPaintingStampSurviveMixin {

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$keepDuringStamp(BlockState state, Direction direction, BlockState neighbor,
                                              LevelAccessor level, BlockPos pos, BlockPos neighborPos,
                                              CallbackInfoReturnable<BlockState> cir) {
        if (CarriageStampGuard.isActive()) cir.setReturnValue(state);
    }

    @Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$noPopDuringStamp(BlockState state, Level level, BlockPos pos, Block neighbor,
                                               BlockPos neighborPos, boolean movedByPiston, CallbackInfo ci) {
        if (CarriageStampGuard.isActive()) ci.cancel();
    }
}
