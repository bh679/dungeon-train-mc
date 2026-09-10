package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.ObserverBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps observers quiet while Dungeon Train's own systems are writing blocks — a template being
 * stamped into an editor plot, a carriage being spawned onto the train and lifted into its Sable
 * sub-level, the contents / parts / variant passes that fill it in afterwards, and the loaders that
 * restore a saved carriage. Only a player or a gameplay cause (a block broken or placed, a piston,
 * a door) should pulse an observer; the mod's construction scaffolding is not a gameplay event.
 *
 * <p><b>Why.</b> Vanilla fires an observer from {@code ObserverBlock.updateShape}, which every
 * neighbour-shape cascade reaches: {@code Level.setBlock} runs one for any write without
 * {@code UPDATE_KNOWN_SHAPE}, so the flag-3 {@code placeInWorld} loaders and the flag-34
 * {@code SilentBlockOps.setBlockSilent} passes all cascade, and Sable's lift runs
 * {@code markAndNotifyBlock(..., 3, 512)} over every moved cell. An observer facing any block those
 * passes touch — which in a carriage is every block — pulses as the carriage is built, so a
 * saved redstone contraption fires itself on load. Section-local writes and the shared-carriage
 * snapshot (which carries {@code UPDATE_KNOWN_SHAPE}) never cascade and were already quiet.</p>
 *
 * <p><b>Why this seam.</b> {@code updateShape} must still run — it returns
 * {@code super.updateShape(...)}, which is where waterlogging and shape state are kept correct.
 * {@code startSignal} is the one place the pulse is scheduled ({@code scheduleTick(pos, this, 2)}),
 * so cancelling it schedules nothing, and nothing fires after the guard drops. Same injection
 * point Carpet TIS Addition's {@code observerNoDetection} rule uses, gated on
 * {@link CarriageStampGuard} instead of a global switch: outside a stamp the vanilla path is
 * untouched, so an observer on a running carriage still sees the player open a door.</p>
 *
 * <p>Common list: the guard is only ever raised on the server thread, so on the client this is a
 * no-op, and the observer's state is authoritative from the server either way.</p>
 */
@Mixin(ObserverBlock.class)
public abstract class ObserverBlockStampMixin {

    @Inject(method = "startSignal", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$noPulseDuringStamp(LevelAccessor level, BlockPos pos, CallbackInfo ci) {
        if (CarriageStampGuard.isActive()) {
            ci.cancel();
        }
    }
}
