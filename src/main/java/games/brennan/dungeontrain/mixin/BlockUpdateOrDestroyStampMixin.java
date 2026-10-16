package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A block that cannot survive where one of Dungeon Train's own writes left it still disappears, but
 * <b>drops nothing</b> — a jungle moss block variant rolled onto a cell with no wall behind it, a
 * flower whose soil a variant replaced, a torch on a block the next pass cleared.
 *
 * <p><b>Why.</b> The variant passes write with {@code UPDATE_CLIENTS | UPDATE_SUPPRESS_DROPS}, but
 * {@code Level.markAndNotifyBlock} strips {@code UPDATE_SUPPRESS_DROPS} out of the flags it hands the
 * neighbour-shape cascade. A plant whose {@code updateShape} answers AIR (BetterNether's
 * {@code BlockPlantWall}, every {@code BushBlock}) is then routed through
 * {@code Block.updateOrDestroy → level.destroyBlock(pos, (flags & UPDATE_SUPPRESS_DROPS) == 0)}, and
 * its item lands in the carriage or the editor plot. That pop is our construction scaffolding, not a
 * player breaking anything, so it should leave no loot behind.</p>
 *
 * <p><b>Why this seam.</b> {@code updateOrDestroy} is the one place the cascade turns "AIR from
 * updateShape" into a destroy, and the drop decision reads straight off its {@code flags}. Putting the
 * suppress bit back there, only while {@link CarriageStampGuard} is held, keeps the removal (the block
 * really cannot stand there) and drops only the item. Outside a stamp the vanilla path is untouched:
 * a player breaking a moss's wall on a running train still gets the moss.</p>
 *
 * <p>Common list: the guard is only ever raised on the server thread, so on the client this is a
 * no-op.</p>
 */
@Mixin(Block.class)
public abstract class BlockUpdateOrDestroyStampMixin {

    @ModifyVariable(
        method = "updateOrDestroy(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;II)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int dungeontrain$noDropsDuringStamp(int flags) {
        return CarriageStampGuard.isActive() ? flags | Block.UPDATE_SUPPRESS_DROPS : flags;
    }
}
