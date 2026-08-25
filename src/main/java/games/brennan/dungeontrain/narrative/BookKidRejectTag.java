package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Stamps and reads "this kid-safe tester has pulled this book out of Kid mode" via
 * {@link DataComponents#CUSTOM_DATA}. Sibling to {@link BookReportTag} — same data component, its own
 * {@code dt_book_kid_rejected} key, so the two co-exist on one stack without collision.
 *
 * <p>Deliberately a SEPARATE mark rather than a second meaning for {@link BookReportTag}. The two
 * controls sit side by side on the vote page and answer different questions — "pull this from
 * everyone" and "keep this away from children" — so a reader who has done one must still be able to
 * do the other, and each control has to know which of them is spent.</p>
 *
 * <p>One-way and one-shot, like a report: there is no un-reject here. Stamped SERVER-side by the
 * {@code BookKidRejectPacket} handler on the held stack — always, even when relay consent is denied —
 * so reopening the book shows the control inert. The relay POST is a separate, consent-gated concern
 * layered on top.</p>
 */
public final class BookKidRejectTag {

    /** Present and true = this player has pulled this book from Kid mode. Absent = they have not. */
    public static final String NBT_KID_REJECTED = "dt_book_kid_rejected";

    private BookKidRejectTag() {}

    /** Mark {@code stack} as kid-rejected. Idempotent — the verdict cannot be taken back in-game. */
    public static void stamp(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_KID_REJECTED, true));
    }

    /** True when {@code stack} carries the kid-rejected mark. False on empty/null stacks. */
    public static boolean isKidRejected(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KID_REJECTED, Tag.TAG_BYTE) && tag.getBoolean(NBT_KID_REJECTED);
    }
}
