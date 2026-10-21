package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.editor.EditorRedstone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.SignalGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The editor's <b>Redstone Off</b> setting ({@link EditorRedstone}): a signal read inside an editor
 * plot comes back 0.
 *
 * <p><b>Why this seam.</b> {@code getSignal}, {@code getDirectSignal} and {@code getControlInputSignal}
 * are where every redstone read ends up — {@code hasNeighborSignal}, {@code getBestNeighborSignal},
 * {@code getDirectSignalTo}, a wire's strength from its sources, a lamp, a piston, a door, a
 * comparator, a repeater's lock. They are default methods on {@link SignalGetter}, which {@code Level}
 * inherits without overriding, so the interface is the target. Wire-to-wire strength is read from
 * block states rather than signals, so with every source silenced a wire decays to 0 and stays
 * there. Cancelling at the read leaves the blocks themselves alone, so a lever still flips and a
 * door still opens by hand.</p>
 *
 * <p>All three are hot. {@link EditorRedstone#isMuted} is one volatile read while the setting is
 * On, which is every play world and the default.</p>
 */
@Mixin(SignalGetter.class)
public interface SignalGetterMuteMixin {

    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$muteSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        if (EditorRedstone.isMuted(this, pos)) cir.setReturnValue(0);
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$muteDirectSignal(BlockPos pos, Direction direction, CallbackInfoReturnable<Integer> cir) {
        if (EditorRedstone.isMuted(this, pos)) cir.setReturnValue(0);
    }

    @Inject(method = "getControlInputSignal", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$muteControlInput(BlockPos pos, Direction direction, boolean diodesOnly,
                                               CallbackInfoReturnable<Integer> cir) {
        if (EditorRedstone.isMuted(this, pos)) cir.setReturnValue(0);
    }
}
