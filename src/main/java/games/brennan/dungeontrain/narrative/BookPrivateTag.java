package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Whether the author has withdrawn this book from circulation — see {@code /books/private}.
 *
 * <p>Not a moderation state: a withdrawn book is exactly as approved as it ever was, its writer has
 * simply decided they would rather strangers did not read it. It leaves the loot pool and the public
 * author directory, and stays findable by its author on their own shelves. Kept apart from
 * {@link BookModerationTag} for that reason — the train's verdict and the writer's choice are two
 * different facts about one book, and either can change without the other.</p>
 *
 * <p>Unlike {@link BookReportTag} this is <b>settable both ways</b>: withdrawing is reversible, so the
 * vote page offers Make Public on a book already private. That is also why it writes an explicit
 * {@code false} rather than removing the key — "put back" has to be distinguishable from "never
 * touched" on a stack the server has already stamped.</p>
 */
public final class BookPrivateTag {

    /** True when the author has taken this book out of circulation. */
    public static final String NBT_PRIVATE = "dt_book_private";

    private BookPrivateTag() {}

    /** Stamp the withdrawn state onto {@code stack}. */
    public static void stamp(ItemStack stack, boolean isPrivate) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_PRIVATE, isPrivate));
    }

    /** Whether {@code stack} is a book its author has withdrawn. False when unstamped. */
    public static boolean isPrivate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_PRIVATE, Tag.TAG_BYTE) && tag.getBoolean(NBT_PRIVATE);
    }
}
