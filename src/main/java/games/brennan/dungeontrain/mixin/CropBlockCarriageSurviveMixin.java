package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.CropBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets crops ({@link CropBlock} — wheat, carrots, potatoes, beetroot) live in a carriage without
 * light, so a farm carriage a player saved into a template survives being generated in the train.
 *
 * <p><b>Why.</b> {@code CropBlock.canSurvive} is {@code hasSufficientLight(pos) &&
 * super.canSurvive(...)} — farmland below <em>and</em> {@code getRawBrightness(pos, 0) >= 8} — and
 * {@code BushBlock.updateShape} swaps the crop for air the instant either fails. A carriage interior
 * is a windowless box, so the light half fails whenever the carriage isn't lit, and any
 * neighbour-shape cascade reaching the crop deletes it. The crop is captured into the template NBT
 * correctly and written into the world correctly — {@code SectionLocalStampProcessor} uses a raw
 * palette write that never consults {@code canSurvive} — and is then destroyed a moment later by the
 * cascade from a later pass over the same carriage. Three moments do it, and all three are the
 * <em>light</em> half:</p>
 *
 * <ol>
 *   <li><b>Source world, pre-lift.</b> The shell is stamped section-local ({@code relight=false}), so
 *       the light engine never processes the carriage's own lanterns; the next pass,
 *       {@code applyVariantBlocks}, writes with {@code UPDATE_CLIENTS | UPDATE_SUPPRESS_DROPS}, which
 *       <em>does</em> cascade. The interior reads dark at that instant. (Which is why the bug looked
 *       intermittent: a carriage spawning under open daylight passed the light check; one in a tunnel
 *       or at night did not.) Covered by {@link CarriageStampGuard} — these are ordinary track
 *       coordinates, so the shipyard test below cannot see them.</li>
 *   <li><b>The Sable lift.</b> {@code SubLevelAssemblyHelper.moveBlocks} runs at shipyard
 *       coordinates, so the shipyard test covers it. Note it is <b>not</b> a soil-ordering problem:
 *       {@code moveBlocks} is four <i>sequential</i> passes over the block set — write every
 *       destination cell ({@code LevelChunk.setBlockState}, no cascade), then
 *       {@code markAndNotifyBlock(..., 3, 512)} over all of them, then air the source cells, then
 *       notify clients. Every block, farmland included, is already in place before the first cascade
 *       runs, so only light can fail. (Bytecode-verified against {@code sable-2.0.2+mc1.21.1}:
 *       loop bounds at 303/685/827/939, {@code setBlockState} at 529, {@code markAndNotifyBlock} at
 *       763. <b>Re-verify on any {@code sable_version} bump</b>, as
 *       {@code SableBlockChangeGuardMixin} instructs for its own target.)</li>
 *   <li><b>Afterwards, for the carriage's whole life.</b> Later neighbour updates in an unlit
 *       carriage fail the same check. Also at shipyard coordinates.</li>
 * </ol>
 *
 * <p><b>Why this seam.</b> Injecting on {@code hasSufficientLight} rather than {@code canSurvive}
 * relaxes only the light rule and leaves {@code super.canSurvive} untouched, so the soil requirement
 * is still enforced <em>everywhere, including during our own placement</em>: break the farmland under
 * carriage wheat and it pops exactly like vanilla. It is also far narrower —
 * {@code hasSufficientLight} has two callers in the game, whereas {@code canSurvive} is reached from
 * {@code BlockItem.canPlace}, bonemeal validity and random ticks as well. Growth is untouched:
 * {@code CropBlock.randomTick} gates on its own inline {@code getRawBrightness(pos, 0) >= 9} and never
 * calls this method, so a crop in an unlit carriage persists at its saved age but does not advance
 * until the player lights the carriage — vanilla farming still means something on the train.
 * {@code PitcherCropBlock} calls {@code CropBlock.hasSufficientLight} directly and so is covered too
 * (it extends {@code DoublePlantBlock}, so a {@code canSurvive} inject here would have missed it).</p>
 *
 * <p><b>Why the coordinate test.</b> Sable sub-levels are not a separate dimension — carriages live
 * in the same {@code ServerLevel} at far coordinates, so there is no dimension to compare and the
 * {@link BlockPos} is the only thing that distinguishes a carriage cell.
 * {@link TrackGenerator#isShipyardChunk} is the cutoff DT already uses throughout for exactly this
 * question: a pure integer compare, no allocation and no lookups, which matters on a path reached per
 * block update. Deliberately <em>not</em> {@code Shipyards.isInShip} — that allocates and returns
 * {@code false} on the client, so client and server would disagree and the player would see the crop
 * ghost-break and reappear. The coordinate test evaluates identically on both sides, which is why
 * this mixin belongs in the common list rather than the client one. ({@code CarriageStampGuard} is
 * always {@code false} on the client, correctly — the client never runs DT's placement.)</p>
 *
 * <p><b>Dimensional carriages — twin space.</b> A dimensional carriage's room is not a Sable
 * sub-level: it is stamped once into <em>twin space</em> (the sealed basement under the bedrock, or
 * the attic over the upside-down band's lid — {@link PortalTwinSpace}) and stands there at ordinary
 * world coordinates, so neither clause above sees it. It is pitch dark down there — no sky, and the
 * room's own lanterns are not propagated until the light engine next runs, well after
 * {@code placeInWorld}'s per-cell and final {@code updateFromNeighbourShapes} passes have asked every
 * crop whether it survives. Every crop in the room popped on load; any that were left died to the
 * next neighbour update (harvesting the crop beside it) unless the author had lit the room — which
 * nothing prompted them to, since the editor plot sits in daylight at y≈250. A room is long-lived and
 * never lifted, so a stamp-time guard alone would only move the failure to the first harvest; the
 * position test covers its whole life, exactly as the shipyard test does for a train carriage.
 * Twin space is unreachable except through a portal, so no vanilla farm can be affected. Server and
 * client resolve it from the same rule ({@code PortalTwinRegion.twinSpaceContains}) for the
 * no-ghost-break reason above; the client half only runs when {@code isClientSide()}, so a dedicated
 * server never loads it.</p>
 *
 * <p>Ordinary overworld farms are untouched: outside the shipyard and outside a stamp both clauses
 * are false and the vanilla path runs unchanged, so a crop in a sealed dark room still dies as it
 * should.</p>
 */
@Mixin(CropBlock.class)
public abstract class CropBlockCarriageSurviveMixin {

    /**
     * Static because {@code CropBlock.hasSufficientLight} is {@code public static}. The soil half of
     * {@code canSurvive} is deliberately left alone.
     */
    @Inject(method = "hasSufficientLight", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$carriageCropsNeedNoLight(
            LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (CarriageStampGuard.isActive()
                || TrackGenerator.isShipyardChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || dungeontrain$inPortalTwinSpace(level, pos)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Whether {@code pos} is in a dimensional carriage's twin space. Overworld only — the only level
     * with a basement or a band — and only for real levels; a {@code WorldGenRegion} or any other
     * reader keeps the vanilla rule.
     */
    @Unique
    private static boolean dungeontrain$inPortalTwinSpace(LevelReader level, BlockPos pos) {
        if (!(level instanceof Level real) || !real.dimension().equals(Level.OVERWORLD)) return false;
        if (real.isClientSide()) {
            return ClientUpsideDownBand.isInPortalTwinSpace(pos.getX(), pos.getY());
        }
        return real instanceof ServerLevel server
            && PortalTwinSpace.isInside(server, pos.getX(), pos.getY());
    }
}
