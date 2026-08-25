package games.brennan.dungeontrain.editor;

import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;

/**
 * The one place the block-variant system knows what a liquid is.
 *
 * <p>A variant cell can hold blocks, mobs, armour stands, the empty-placeholder sentinel, and
 * lock-group references. Liquids were the gap: a water / lava bucket is a {@link BucketItem},
 * not a {@code BlockItem}, so both authoring paths ({@link BlockVariantMenuController}'s ADD op
 * and {@link VariantBlockInteractions}' variant-key gesture) rejected it before it could reach a
 * {@link VariantState}. Nor could an author route around that by placing the liquid and capturing
 * it — the player raycast uses {@code ClipContext.Fluid.NONE}, so a water block cannot even be
 * targeted.</p>
 *
 * <p><b>Sources only.</b> A {@link LiquidBlock} state carries {@link LiquidBlock#LEVEL}, and only
 * {@code level=0} is a source. A flowing state stamped into a carriage has nothing feeding it and
 * drains to air within a few ticks, so every liquid that reaches a variant cell is normalised to
 * its source state — both on the way in from a bucket ({@link #sourceStateFrom}) and when captured
 * from a block already in the world ({@link #toSource}).</p>
 *
 * <p>Powder-snow buckets need no case here: {@code SolidBucketItem extends BlockItem}, so they
 * already travel the ordinary block path.</p>
 */
public final class VariantLiquids {

    private VariantLiquids() {}

    /**
     * Source {@link BlockState} for the fluid in {@code held}, or {@code null} when the stack is
     * not a filled bucket. Callers rely on the {@code null} to fall through to their existing
     * branches unchanged — an empty bucket, a milk bucket (not a {@link BucketItem}) and every
     * non-bucket item all return {@code null}.
     */
    public static @Nullable BlockState sourceStateFrom(ItemStack held) {
        if (held == null || held.isEmpty()) return null;
        if (!(held.getItem() instanceof BucketItem bucket)) return null;
        if (bucket.content == Fluids.EMPTY) return null;
        return bucket.content.defaultFluidState().createLegacyBlock();
    }

    /**
     * Normalise a flowing liquid state to its source. Non-liquid states — and liquids already at
     * {@code level=0} — are returned unchanged, so this is safe to wrap around any captured state.
     *
     * <p>Deliberately keyed on the block being a {@link LiquidBlock} rather than on
     * {@code getFluidState()}: a waterlogged fence also reports a water fluid state, and turning
     * that into a bare water source would silently discard the fence.</p>
     */
    public static BlockState toSource(BlockState state) {
        if (state == null || !(state.getBlock() instanceof LiquidBlock liquid)) return state;
        if (state.getValue(LiquidBlock.LEVEL) == 0) return state;
        return liquid.fluid.getSource().defaultFluidState().createLegacyBlock();
    }

    /**
     * True when {@code state} is a fluid block (water, lava, modded). Used by the editor's
     * in-world write sites, which skip liquids: a source stamped into a plot would flow across the
     * author's build, and lava would burn it.
     */
    public static boolean isLiquid(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof LiquidBlock;
    }

    /**
     * The bucket item for a liquid state, for menu icons — {@code Block.asItem()} is AIR for
     * water / lava, which would otherwise render as the BARRIER fallback. {@code null} for
     * non-liquids.
     */
    public static @Nullable Item bucketIcon(@Nullable BlockState state) {
        if (state == null || !(state.getBlock() instanceof LiquidBlock liquid)) return null;
        return liquid.fluid.getBucket();
    }
}
