package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Boolean marker stamped onto the written book {@link SharedBookPool#rollShared} builds for the
 * community-books DISCOVERY half — a chest-loot book credited to a real player, pulled from the
 * relay's approved pool. Used only to gate the "read a stranger's book" advancement in
 * {@code NarrativeBookEvents}.
 *
 * <p>Deliberately a separate tag from {@link SharedBookTag} (the CONTRIBUTION half's "burn me"
 * marker on the book dropped when a player signs one): the two mark opposite directions of the
 * same feature and must never be confused. This tag carries no "burn" semantics and must not
 * affect {@link BurnableBookTag} or the "never counts as a story read" guarantee documented on
 * {@link BookFactory#buildPlainBook} / {@link SharedBookPool#rollShared} — it exists purely so the
 * read-event handler can tell "an ordinary written book" apart from "a stranger's shared book".</p>
 *
 * <p>Stored under {@link DataComponents#CUSTOM_DATA} with key {@code dt_shared_book_found},
 * distinct from the existing {@code dt_shared_book} prefix so the two meanings never collide.</p>
 */
public final class SharedBookFoundTag {

    /** Boolean marker — present + true means "this book was discovered from the community pool". */
    public static final String NBT_KEY = "dt_shared_book_found";

    private SharedBookFoundTag() {}

    /** Stamp the marker onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_KEY, true));
    }

    /** True when {@code stack} carries the shared-book discovery marker. Safe on any stack. */
    public static boolean isFound(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KEY, Tag.TAG_BYTE) && tag.getBoolean(NBT_KEY);
    }
}
