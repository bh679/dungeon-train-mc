package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Boolean marker stamped onto the written book the mod builds when a player SIGNS a book &amp; quill
 * that was opened from a lectern (the player-written "lectern narrative" letters feature). The
 * sign-interception mixin routes such a sign to the letter flow: it uploads the letter to the
 * relay's per-life narrative series, cancels vanilla signing, and spawns this marked stack at the
 * lectern so the existing burn lifecycle in {@code StartingBookEvents} animates it away — see
 * {@link BurnableBookTag#isBurnable}.
 *
 * <p>Deliberately NOT a {@link SharedBookTag} / {@link StartingBookTag} / {@link NarrativeBookTag},
 * so the read-counters in {@code NarrativeBookEvents} / {@code RunStatsEvents} and the
 * "burned unread" milestone never conflate a burning letter with a shared-book contribution or a
 * book the player <em>read</em>. The author + title live in the vanilla {@code WrittenBookContent};
 * this tag carries no identity, only "burn me".</p>
 *
 * <p>Stored under {@link DataComponents#CUSTOM_DATA} with a unique key prefix ({@code dt_letter_book})
 * so it never collides with {@code dt_shared_book} / {@code dt_starting_book*} / {@code dt_narrative_*}.</p>
 */
public final class LetterBookTag {

    /** Boolean marker — present + true means "this is a signed lectern letter; burn it". */
    public static final String NBT_KEY = "dt_letter_book";

    private LetterBookTag() {}

    /** Stamp the marker onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_KEY, true));
    }

    /** True when {@code stack} carries the lectern-letter marker. Safe on any stack. */
    public static boolean isLetter(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KEY, Tag.TAG_BYTE) && tag.getBoolean(NBT_KEY);
    }
}
