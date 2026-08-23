package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Stamps and reads the moderation state on a book the relay served back to its own author
 * ({@code /books/pool?mine=1}) — see {@link BookModerationState}.
 *
 * <p>Baked onto the stack rather than looked up when needed, because the stack outlives the room it
 * came from: a writer takes their own book off the shelf, carries it, stores it, and reads it later,
 * long after the catalogue that produced it has been evicted. The state has to travel with the book,
 * and this is the same CUSTOM_DATA idiom {@link SharedBookReadTag} and {@link SharedBookFoundTag}
 * already use for the other facts a discovered book carries.</p>
 *
 * <p>Only ever present on a WITHHELD book. An approved one is an ordinary community book and stays
 * byte-identical to what it was before this existed.</p>
 */
public final class BookModerationTag {

    /** The relay's moderation status for this book, as a string — see {@link BookModerationState}. */
    public static final String NBT_STATUS = "dt_book_moderation";

    private BookModerationTag() {}

    /** Stamp {@code state} onto {@code stack}. {@link BookModerationState#APPROVED} stamps nothing. */
    public static void stamp(ItemStack stack, BookModerationState state) {
        if (stack == null || stack.isEmpty() || state == null || !state.isWithheld()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
            tag -> tag.putString(NBT_STATUS, state.name()));
    }

    /** The stamped state, or {@link BookModerationState#APPROVED} when there is none. */
    public static BookModerationState read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return BookModerationState.APPROVED;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return BookModerationState.APPROVED;
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(NBT_STATUS, Tag.TAG_STRING)) return BookModerationState.APPROVED;
        try {
            return BookModerationState.valueOf(tag.getString(NBT_STATUS));
        } catch (IllegalArgumentException e) {
            // A stack written by a newer jar that knows a state this one does not. Treat it as an
            // ordinary book rather than inventing a verdict for it.
            return BookModerationState.APPROVED;
        }
    }
}
