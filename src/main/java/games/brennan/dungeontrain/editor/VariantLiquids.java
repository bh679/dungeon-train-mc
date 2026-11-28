package games.brennan.dungeontrain.editor;

import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
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
 * <p><b>Fire</b> has the same gap (no item form, untargetable), so flint and steel stands for
 * it here too, and the editor's in-world writes treat it like a liquid
 * ({@link #writesSectionLocal}).</p>
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
        // Flint and steel stands for fire, the same way a bucket stands for its liquid: it is
        // not a BlockItem, and fire cannot be targeted to capture it either. Plain fire is
        // stored; vanilla's updateShape turns it into soul fire on soul soil at spawn.
        if (held.is(Items.FLINT_AND_STEEL)) return Blocks.FIRE.defaultBlockState();
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

    /** True when {@code state} is fire or soul fire. */
    public static boolean isFire(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof BaseFireBlock;
    }

    /**
     * True when the editor must write {@code state} into a plot section-local, scheduling no
     * tick: a liquid would flow across the author's build, and fire would spread over it, burn
     * it and age out. Either way the spread would be saved as part of the template.
     */
    public static boolean writesSectionLocal(@Nullable BlockState state) {
        return isLiquid(state) || isFire(state);
    }

    /**
     * The item a liquid or fire state shows as in menus: the fluid's bucket, or flint and steel
     * for fire. {@code Block.asItem()} is AIR for all of them, which would otherwise render as
     * the BARRIER fallback. {@code null} for anything else.
     */
    public static @Nullable Item bucketIcon(@Nullable BlockState state) {
        if (isFire(state)) return Items.FLINT_AND_STEEL;
        if (state == null || !(state.getBlock() instanceof LiquidBlock liquid)) return null;
        return liquid.fluid.getBucket();
    }
}
