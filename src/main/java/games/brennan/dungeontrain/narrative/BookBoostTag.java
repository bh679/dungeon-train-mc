package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * How many times the READER has boosted this book, somebody else's, from its vote page (relay
 * {@code /books/credits/boost}). A boost costs a tenth of a Priority Book and lifts the book like a
 * whole one; a reader may boost the same book again, and boosts from every reader stack.
 *
 * <p>Deliberately separate from {@link BookPriorityTag}, which is the AUTHOR's state on their own
 * copies and drives the author-side page. This one lives only on the booster's copy, so the vote page
 * can say "you boosted this" with a filled star. Incremented when the relay confirms (see
 * {@code PriorityBookResponses}), never optimistically on the server.</p>
 */
public final class BookBoostTag {

    /** How many of the holder's boosts this book carries. Absent = none. */
    public static final String NBT_BOOSTS = "dt_book_boosts";

    private BookBoostTag() {}

    /** Count one more confirmed boost on {@code stack}. */
    public static void increment(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
            tag -> tag.putInt(NBT_BOOSTS, Math.max(0, tag.getInt(NBT_BOOSTS)) + 1));
    }

    /** How many times the holder has boosted {@code stack}. Zero when unstamped. */
    public static int count(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return 0;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_BOOSTS, Tag.TAG_INT) ? Math.max(0, tag.getInt(NBT_BOOSTS)) : 0;
    }
}
