package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Whether the author spent a Priority Book on this book, so the relay serves it far more often in
 * other players' trains (bookcredits.js on the relay, a bounded boost within the book's tier).
 *
 * <p>Stamped only on the author's own copies, from what the relay said: the author's-own-shelf pool
 * path ({@code priority} on a {@code mine=1} row), {@code /books/stats}, and a successful spend. The
 * vote page reads it to show the passive "Priority Book" mark instead of offering to spend again.
 * Like {@link BookPrivateTag} it writes an explicit {@code false}, so a refunded book (its spend
 * released when review rejected it) reads differently from one nobody told us about.</p>
 */
public final class BookPriorityTag {

    /** True when the author has made this book a Priority Book. */
    public static final String NBT_PRIORITY = "dt_book_priority";

    private BookPriorityTag() {}

    /** Stamp the priority state onto {@code stack}. */
    public static void stamp(ItemStack stack, boolean priority) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_PRIORITY, priority));
    }

    /** Whether {@code stack} is a Priority Book. False when unstamped. */
    public static boolean isPriority(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_PRIORITY, Tag.TAG_BYTE) && tag.getBoolean(NBT_PRIORITY);
    }
}
