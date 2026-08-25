package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * How a book is polling — the 👍/👎 tally, carried on the stack so the vote page can show its author
 * the counters that stand where the buttons used to.
 *
 * <p>Only ever present on a book its own author is holding. The relay hands vote tallies to nobody
 * else (a non-author gets {@code isAuthor:false} from {@code /books/stats} and nothing at all), and
 * {@code SharedBookPool.buildStack} stamps this only for a book off the writer's own shelf — so an
 * ordinary community book stays byte-identical to what it was before any of this existed.</p>
 *
 * <p><b>Present-ness is the signal, not the value.</b> Zero is a real, reportable answer here —
 * "nobody has voted on this yet" — so the page must be able to tell it from "we were never told".
 * That is what {@link #has} is for: {@link #up}/{@link #down} alone cannot distinguish them.</p>
 *
 * <p>Refreshed on every pickup by {@link FamiliarBookGreeter}, which already fetches these numbers
 * for its chat line. A stack that has sat in a chest for a week therefore shows what it was worth
 * when it was shelved, then corrects itself the moment it is held.</p>
 */
public final class BookVoteCountsTag {

    /** Thumbs-up count, as of the last time this stack heard from the relay. */
    public static final String NBT_UP = "dt_book_votes_up";

    /** Thumbs-down count, likewise. */
    public static final String NBT_DOWN = "dt_book_votes_down";

    private BookVoteCountsTag() {}

    /** Stamp the tally onto {@code stack}. Negative inputs are clamped to 0. */
    public static void stamp(ItemStack stack, int up, int down) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putInt(NBT_UP, Math.max(0, up));
            tag.putInt(NBT_DOWN, Math.max(0, down));
        });
    }

    /** Whether this stack carries a tally at all — see the class note on why zero is not the test. */
    public static boolean has(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_UP, Tag.TAG_INT) && tag.contains(NBT_DOWN, Tag.TAG_INT);
    }

    /** Thumbs-up count, or 0 when unstamped. */
    public static int up(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_UP, Tag.TAG_INT) ? Math.max(0, tag.getInt(NBT_UP)) : 0;
    }

    /** Thumbs-down count, or 0 when unstamped. */
    public static int down(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_DOWN, Tag.TAG_INT) ? Math.max(0, tag.getInt(NBT_DOWN)) : 0;
    }

    private static CompoundTag tagOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        return cd == null || cd.isEmpty() ? null : cd.copyTag();
    }
}
