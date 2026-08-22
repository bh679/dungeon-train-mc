package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Stamps and reads "this player reported this book" via {@link DataComponents#CUSTOM_DATA}. Sibling
 * to {@link BookVoteTag} — same data component, its own {@code dt_book_reported} key, so it
 * co-exists with every book identity and vote tag without collision.
 *
 * <p>A report is one-way and one-shot: unlike a vote there is no un-report and no changing your
 * mind, so this is a plain boolean rather than a value. Stamped SERVER-side by the
 * {@code BookReportPacket} handler on the held stack — always, even when relay consent is denied —
 * so reopening the book shows the reported state and the control goes inert. The relay POST is a
 * separate, consent-gated concern layered on top.</p>
 */
public final class BookReportTag {

    /** Present and true = this player has reported this book. Absent = not reported. */
    public static final String NBT_REPORTED = "dt_book_reported";

    private BookReportTag() {}

    /** Mark {@code stack} as reported. Idempotent — a report cannot be taken back. */
    public static void stamp(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_REPORTED, true));
    }

    /** True when {@code stack} carries the reported mark. False on empty/null stacks. */
    public static boolean isReported(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_REPORTED, Tag.TAG_BYTE) && tag.getBoolean(NBT_REPORTED);
    }
}
