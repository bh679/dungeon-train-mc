package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Whether the author has objected to the train's verdict on this book — see {@code /books/protest}.
 *
 * <p>One-way, exactly like {@link BookReportTag}: a protest cannot be taken back or repeated, and the
 * relay dedupes a re-delivery anyway. What it does NOT do is change anything about the book — the
 * verdict stands until a person revisits it. The tag exists so the vote page can show the control as
 * spent rather than inviting a writer to protest the same book every time they open it.</p>
 */
public final class BookProtestTag {

    /** True once the author has protested this book's verdict. */
    public static final String NBT_PROTESTED = "dt_book_protested";

    private BookProtestTag() {}

    /** Mark {@code stack} protested. Idempotent, one-way. */
    public static void stamp(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_PROTESTED, true));
    }

    /** Whether the author has already protested this book. */
    public static boolean isProtested(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_PROTESTED, Tag.TAG_BYTE) && tag.getBoolean(NBT_PROTESTED);
    }
}
