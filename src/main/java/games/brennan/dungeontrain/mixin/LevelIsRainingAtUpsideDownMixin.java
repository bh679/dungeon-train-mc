package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rain reaches you from <b>below</b> in the upside-down band, so what shelters you is what is under
 * you.
 *
 * <p>Vanilla {@code Level.isRainingAt} asks two questions that both assume rain falls: can this
 * position see the sky, and is the {@code MOTION_BLOCKING} heightmap above it. In the band the world is
 * mirrored — terrain hangs from a ceiling and the sky sits under the train — so both answers are wrong,
 * and in practice both say "sheltered" everywhere under the mirrored ceiling. This replaces them with
 * the flipped test: the column below the position must be open.</p>
 *
 * <p>That is gameplay, not decoration: this predicate is what fills cauldrons, puts fires out, hydrates
 * farmland and marks a mob as wet ({@code Entity#isInRain} calls it for the feet and again for the top
 * of the bounding box).</p>
 *
 * <p><b>Server side only.</b> Rain exposure is server-authoritative and the client follows it, and a
 * common mixin must not reach for {@code ClientUpsideDownBand} — that would drag client-only classes
 * onto a dedicated server. Band membership therefore comes from
 * {@link UpsideDownBand#isInBandOrEntryLead}, the same server-side predicate
 * {@link SnowLayerBlockUpsideDownMixin} uses. Everything outside the band, and the exit crossfade where
 * the overworld returns upright, keeps vanilla behaviour.</p>
 */
@Mixin(Level.class)
public abstract class LevelIsRainingAtUpsideDownMixin {

    /**
     * How far below a position a block still counts as shelter. Bounded because this runs twice per
     * entity per tick; it costs nothing real, because the mirror leaves open void under the train and
     * anything that reads as shelter in a flipped world is a block or two beneath your feet.
     */
    private static final int DUNGEONTRAIN_SHELTER_SCAN_DEPTH = 16;

    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$rainFromBelowInUpsideDownBand(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerLevel server)) return;
        if (!server.dimension().equals(Level.OVERWORLD)) return;
        if (!UpsideDownBand.isInBandOrEntryLead(server, pos.getX())) return;

        if (!server.isRaining() || !dungeontrain$isOpenBelow(server, pos)) {
            cir.setReturnValue(false);
            return;
        }
        Biome biome = server.getBiome(pos).value();
        cir.setReturnValue(biome.getPrecipitationAt(pos) == Biome.Precipitation.RAIN);
    }

    /** True while nothing within the scan depth under {@code pos} blocks the rising rain. */
    private static boolean dungeontrain$isOpenBelow(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int bottom = Math.max(level.getMinBuildHeight(), pos.getY() - DUNGEONTRAIN_SHELTER_SCAN_DEPTH);
        for (int y = pos.getY() - 1; y >= bottom; y--) {
            cursor.set(pos.getX(), y, pos.getZ());
            BlockState state = level.getBlockState(cursor);
            if (state.blocksMotion() || !state.getFluidState().isEmpty()) return false;
        }
        return true;
    }
}
