package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Fast Paintings' block paintings whole, and their items off the floor, while Dungeon Train
 * is building the room they hang in — the {@link CarriageStampGuard} window.
 *
 * <p><b>No pop.</b> {@code PaintingBlock.updateShape} turns a cell to air the moment the block
 * behind it is not solid, and {@code neighborChanged} then takes the rest of the picture with it. A
 * carriage is written in passes, so a picture's wall is often not there yet when the picture lands:
 * a variant cell is air until the variant pass fills it, and a wall that comes from a part arrives
 * after the shell. Test the Carriage, the editor plots and in-carriage part swaps stamp relit
 * ({@code placeInWorld}'s final shape pass, then {@code UPDATE_ALL} variant writes), so every such
 * picture popped there. {@code neighborChanged} is left alone: its {@code canSurvive} only asks
 * whether the picture's own cells are all present, so a picture a later pass genuinely cut into
 * still clears away whole instead of leaving half a frame.</p>
 *
 * <p><b>No drop.</b> {@code onRemove} drops the painting's item whenever the master cell goes. A
 * train carriage is stamped in the source world and lifted into its Sable sub-level, which airs
 * the source cells — so every picture on every spawned carriage left a painting item lying on the
 * track. Anything we remove while building is scaffolding, never a player breaking a picture.</p>
 *
 * <p>Same rule as {@code CropBlockCarriageSurviveMixin}: the template is authoritative. Outside the
 * guard the mod is untouched — break the wall behind a hung picture in play and it still drops.
 * Common list: the guard is only raised on the server thread. Fast Paintings is a hard dependency,
 * so the target always exists.</p>
 */
@Mixin(targets = "net.mehvahdjukaar.fastpaintings.PaintingBlock")
public abstract class FastPaintingStampSurviveMixin {

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$keepDuringStamp(BlockState state, Direction direction, BlockState neighbor,
                                              LevelAccessor level, BlockPos pos, BlockPos neighborPos,
                                              CallbackInfoReturnable<BlockState> cir) {
        if (CarriageStampGuard.isActive()) cir.setReturnValue(state);
    }

    @WrapWithCondition(method = "onRemove", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V"))
    private boolean dungeontrain$noDropDuringStamp(Level level, double x, double y, double z, ItemStack stack) {
        return !CarriageStampGuard.isActive();
    }
}
