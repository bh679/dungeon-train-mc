package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps ground plants ({@link BushBlock} and its subclasses: VanillaBackport's leaf litter, short grass,
 * ferns, flowers, saplings) from breaking inside the upside-down band when nothing supports them from
 * below.
 *
 * <p><b>Why.</b> The band mirrors overworld terrain vertically ({@code WorldUpsideDownEvents}): the
 * surface the plants grew on becomes a <em>ceiling</em>, so they end up hanging from it with
 * <em>air below them</em>. The mirror writes raw, so they sit there until a neighbour update reaches one;
 * then vanilla {@code BushBlock.updateShape} sees {@code !state.canSurvive(...)} (a solid face directly
 * below) and swaps it for air, dropping it as an item. That pop updates the next plant, which pops in
 * turn, so a dense leaf-litter carpet chain-breaks and rains down in one go. Returning the current
 * {@code state} at HEAD keeps the plant exactly as mirrored, ignoring the missing support.</p>
 *
 * <p>Only the support check is skipped: subclasses that override {@code updateShape} with their own
 * rules (e.g. {@code DoublePlantBlock}'s half pairing) still apply them before reaching
 * {@code super}. Server-side and gated on overworld + band/entry lead-in exactly like
 * {@link SnowLayerBlockUpsideDownMixin}, so every column outside the band keeps vanilla behaviour.</p>
 */
@Mixin(BushBlock.class)
public abstract class BushBlockUpsideDownMixin {

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$keepPlantInUpsideDownBand(
            BlockState state, Direction facing, BlockState facingState, LevelAccessor level,
            BlockPos currentPos, BlockPos facingPos, CallbackInfoReturnable<BlockState> cir) {
        if (!(level instanceof ServerLevel server)) return;
        if (!server.dimension().equals(Level.OVERWORLD)) return;
        if (UpsideDownBand.isInBandOrEntryLead(server, currentPos.getX(), currentPos.getZ())) {
            cir.setReturnValue(state); // in-band or lead-in: keep the plant, ignore missing support
        }
    }
}
