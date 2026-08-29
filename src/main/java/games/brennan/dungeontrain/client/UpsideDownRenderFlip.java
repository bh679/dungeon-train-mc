package games.brennan.dungeontrain.client;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;

/**
 * Decides, per block position, whether the upside-down band renders that block visually flipped, and
 * hands back the model to mesh it with. Shared by <b>both</b> chunk-meshing pipelines — vanilla
 * NeoForge ({@code ModelBlockRendererUpsideDownMixin}) and Sodium
 * ({@code mixin.client.sodium.BlockRendererUpsideDownMixin}) — so the two can never disagree about
 * which blocks are upside-down.
 *
 * <p>Flipping happens at the <b>model</b> level ({@link UpsideDownBakedModel}), not by rotating
 * geometry: face-culling has already dropped the hidden faces, so a rotation would swing the one
 * surviving air-adjacent face to the far side and leave the block inside-out. The flip is baked into
 * the compiled section mesh with the same face count as normal — <b>nothing per frame</b>.</p>
 *
 * <p>Purely client-side and cosmetic; the world's block data is never touched.</p>
 */
public final class UpsideDownRenderFlip {

    private UpsideDownRenderFlip() {
    }

    /**
     * The model to mesh {@code pos} with: a vertically-mirrored wrapper in the band, or {@code model}
     * unchanged everywhere else.
     */
    public static BakedModel apply(BakedModel model, BlockPos pos) {
        return shouldFlip(pos) ? UpsideDownBakedModel.of(model) : model;
    }

    /**
     * Whether the block at {@code pos} renders upside-down.
     *
     * <p>The full band and its entry lead-in flip every block; the exit crossfade flips with a
     * Y-split (only at/above the mirror plane) so the dispersing mirror islands stay upside-down
     * while the returning overworld below renders upright.</p>
     *
     * <p>Two things are never flipped. <b>The train:</b> its carriages are Sable sub-levels meshed at
     * plot-space coordinates (near the world border, ~X 20.4M) through the very same meshing path —
     * and the band repeats forever via {@code Math.floorMod}, so those huge coordinates wrap back
     * into the cycle and can land <em>inside</em> the band. A world-X gate alone therefore flips the
     * whole train; {@link #isSubLevelBlock} cancels it so the train stays upright as the stable frame
     * of reference while the world inverts around it. <b>Twin space:</b> the portal system's twin
     * corridors are world blocks that must stay block-for-block identical to the carriage they stand
     * in for — flipping one and not the other makes the crossing visible. Sealed inside their own
     * shell either way, so nothing of the band's look is lost.</p>
     */
    public static boolean shouldFlip(BlockPos pos) {
        if (pos == null) return false;
        boolean bandFlip = ClientUpsideDownBand.isInBand(pos.getX())
                || (ClientUpsideDownBand.isInExitFlip(pos.getX()) && pos.getY() >= ClientUpsideDownBand.plane());
        // Evaluated only when a flip would otherwise happen (rare, meshed-once) — the no-flip hot
        // path stays a single X comparison.
        return bandFlip
                && !ClientUpsideDownBand.isInTwinSpace(pos.getY())
                && !isSubLevelBlock(pos);
    }

    /**
     * True if {@code pos} lies inside the Sable sub-level plot grid — i.e. it is a train/carriage
     * block, not real overworld terrain. {@link ClientSubLevelContainer#inBounds(BlockPos)} is a cheap
     * bit-shift range test (no allocation, no per-plot lookup); the plot grid is a dedicated
     * high-coordinate region overworld terrain never occupies, so a positive result reliably means
     * "sub-level block". Reuses the established client idiom
     * ({@code SubLevelContainer.getContainer(ClientLevel)}) shared with
     * {@code client.snapshot.NearestCarriage} / {@code CarriageOcclusion}. Returns {@code false}
     * (→ flip) when there is no level or container, so the worst case is pre-fix behaviour.
     */
    private static boolean isSubLevelBlock(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        return container != null && container.inBounds(pos);
    }
}
