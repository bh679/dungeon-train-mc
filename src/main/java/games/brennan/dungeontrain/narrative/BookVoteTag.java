package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.OptionalInt;

/**
 * Stamps and reads the player's current 👍/👎 vote ({@code +1}/{@code -1}) on a votable book via
 * {@link DataComponents#CUSTOM_DATA}. Sibling to {@link RandomBookTag} — same data component,
 * its own {@code dt_book_vote} key, so it co-exists with every book identity tag without collision.
 *
 * <p>Stamped SERVER-side by the {@code BookVotePacket} handler on the held stack — always, even when
 * relay consent is denied — so the vote works offline: it drives the burn flame color (see
 * {@code StartingBookEvents}) and re-seeds the vote page's selected state when the book is reopened.
 * The relay POST is a separate, consent-gated concern layered on top.</p>
 */
public final class BookVoteTag {

    /** The player's current vote: {@code +1} approve, {@code -1} reject. Absent = unvoted. */
    public static final String NBT_VOTE = "dt_book_vote";

    private BookVoteTag() {}

    /** Stamp {@code vote} (±1 only; anything else is ignored) onto {@code stack}'s CUSTOM_DATA. */
    public static void stamp(ItemStack stack, int vote) {
        if (stack == null || stack.isEmpty()) return;
        if (vote != 1 && vote != -1) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(NBT_VOTE, vote));
    }

    /**
     * Decode the current vote from {@code stack}'s CUSTOM_DATA. Empty when unvoted, on empty/null
     * stacks, or when the stored value is not exactly ±1 (defensive against foreign edits).
     */
    public static OptionalInt read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return OptionalInt.empty();
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return OptionalInt.empty();
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(NBT_VOTE, Tag.TAG_INT)) return OptionalInt.empty();
        int v = tag.getInt(NBT_VOTE);
        return (v == 1 || v == -1) ? OptionalInt.of(v) : OptionalInt.empty();
    }
}
