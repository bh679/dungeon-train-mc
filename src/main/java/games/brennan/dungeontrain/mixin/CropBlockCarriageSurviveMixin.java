package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps crops ({@link CropBlock} — wheat, carrots, potatoes, beetroot) that a player saved into a
 * carriage template from breaking the moment that carriage is generated in the train.
 *
 * <p><b>Why.</b> {@code CropBlock.canSurvive} is {@code hasSufficientLight(pos) &&
 * super.canSurvive(...)} — farmland below <em>and</em> {@code getRawBrightness(pos, 0) >= 8} — and
 * {@code BushBlock.updateShape} swaps the crop for air the instant either fails. A carriage
 * interior is a windowless box, so the light half fails whenever the carriage isn't lit, and any
 * neighbour-shape cascade reaching the crop silently deletes it. The crop is captured into the
 * template NBT correctly and is written into the world correctly (a raw palette write via
 * {@code SilentBlockOps.setBlockSectionLocal}, which never consults {@code canSurvive}); it is
 * destroyed a moment later, by the cascade from a subsequent pass over the same carriage.</p>
 *
 * <p>Two rules, covering two different lifetimes:</p>
 *
 * <ol>
 *   <li><b>While DT is placing ({@code canSurvive} → {@code true}).</b> During our own stamp and
 *       Sable lift the template is authoritative and the carriage is mid-construction: light has not
 *       been computed yet and the farmland under a crop may not have been moved into the sub-level
 *       yet. Neither is a real condition — they are intermediate states of our own scaffolding — so
 *       nothing may delete a cell the author saved. This arm ignores the soil requirement as well as
 *       the light one, which is why it is scoped to a thread-local held only across placement; see
 *       {@link CarriageStampGuard}.</li>
 *   <li><b>Permanently, inside the shipyard ({@code hasSufficientLight} → {@code true}).</b> A crop
 *       that lives in a carriage does not need light to <em>stay</em> planted. Injecting here rather
 *       than on {@code canSurvive} means the soil requirement is still enforced by the untouched
 *       {@code super.canSurvive} call: break the farmland under carriage wheat and it pops exactly
 *       like vanilla. Growth is untouched — {@code CropBlock.randomTick} gates on its own inline
 *       {@code getRawBrightness(pos, 0) >= 9} and never calls {@code hasSufficientLight} — so a crop
 *       in an unlit carriage persists at its saved age but does not advance until the player lights
 *       the carriage, which keeps vanilla farming rules meaningful on the train.</li>
 * </ol>
 *
 * <p><b>Why the coordinate test.</b> Sable sub-levels are not a separate dimension — carriages live
 * in the same {@code ServerLevel} at far coordinates, so there is no dimension to compare and the
 * {@link BlockPos} is the only thing that distinguishes a carriage cell.
 * {@link TrackGenerator#isShipyardChunk} is the cutoff DT already uses throughout for exactly this
 * question: a pure integer compare, no allocation and no lookups, which matters on a path called
 * per block update. Deliberately <em>not</em> {@code Shipyards.isInShip} — that allocates and returns
 * {@code false} on the client, so client and server would disagree about whether a crop survives and
 * the player would see it ghost-break and reappear. The coordinate test evaluates identically on
 * both sides.</p>
 *
 * <p>Ordinary overworld farms are untouched: outside the shipyard and outside a stamp both gates are
 * false and the vanilla path runs unchanged, so a crop in a sealed dark room still dies as it
 * should. {@code PitcherCropBlock} delegates to {@code CropBlock.hasSufficientLight} and so inherits
 * rule 2 for free.</p>
 */
@Mixin(CropBlock.class)
public abstract class CropBlockCarriageSurviveMixin {

    /**
     * Rule 1 — while DT is stamping or lifting a carriage, the saved template wins outright.
     * Deliberately broader than rule 2 (soil included), and safe only because the guard is held
     * across placement alone.
     */
    @Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$keepTemplateCropDuringStamp(
            BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (CarriageStampGuard.isActive()) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Rule 2 — crops inside a carriage never need light to stay planted. Static, because
     * {@code CropBlock.hasSufficientLight} is {@code public static}. The soil half of
     * {@code canSurvive} is untouched.
     */
    @Inject(method = "hasSufficientLight", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$carriageCropsNeedNoLight(
            LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            cir.setReturnValue(true);
        }
    }
}
