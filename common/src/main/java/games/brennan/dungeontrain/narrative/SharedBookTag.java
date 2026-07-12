package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Boolean marker stamped onto the written book the mod drops when a player SIGNS a book &amp; quill for
 * the community-books feature. The sign-interception mixin cancels vanilla signing (so the player
 * keeps no written book) and instead drops this marked stack, so the existing burn lifecycle in
 * {@code StartingBookEvents} animates it away — see {@link BurnableBookTag#isBurnable}.
 *
 * <p>Deliberately NOT a {@link StartingBookTag} / {@link RandomBookTag} / {@link NarrativeBookTag}, so
 * the read-counters in {@code NarrativeBookEvents} and {@code RunStatsEvents} never mistake a burning
 * contribution for a book the player <em>read</em>. The author's name lives in the vanilla
 * {@code WrittenBookContent}, not here — this tag carries no identity, only "burn me".</p>
 *
 * <p>Stored under {@link DataComponents#CUSTOM_DATA} with a unique key prefix ({@code dt_shared_book})
 * so it never collides with {@code dt_starting_book*} / {@code dt_random_book*} / {@code dt_narrative_*}.</p>
 */
public final class SharedBookTag {

    /** Boolean marker — present + true means "this is a signed community contribution; burn it". */
    public static final String NBT_KEY = "dt_shared_book";

    private SharedBookTag() {}

    /** Stamp the marker onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_KEY, true));
    }

    /** True when {@code stack} carries the shared-book contribution marker. Safe on any stack. */
    public static boolean isSharedBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KEY, Tag.TAG_BYTE) && tag.getBoolean(NBT_KEY);
    }
}
