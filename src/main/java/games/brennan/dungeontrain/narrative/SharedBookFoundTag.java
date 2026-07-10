package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Boolean marker stamped onto the written book {@link SharedBookPool#rollShared} builds for the
 * community-books DISCOVERY half — a chest-loot book credited to a real player, pulled from the
 * relay's approved pool. Gates the "read a stranger's book" advancement in
 * {@code NarrativeBookEvents}, AND — together with the {@link #NBT_HELD} marker below — the
 * burn-after-reading lifecycle via {@link BurnableBookTag#isBurnable}: a stranger's real book
 * burns once read, the same as a random loot book, since it was genuinely written by another
 * player rather than generated mod content.
 *
 * <p>Deliberately a separate tag from {@link SharedBookTag} (the CONTRIBUTION half's "burn
 * immediately" marker on the book dropped when a player signs one): the two mark opposite
 * directions of the same feature and must never be confused. This tag's burn timing differs from
 * {@link SharedBookTag} too — it burns on read/drop like {@link RandomBookTag}, not immediately on
 * discovery. It still carries no "story read" semantics — see the guarantee documented on
 * {@link BookFactory#buildPlainBook} / {@link SharedBookPool#rollShared}; burning is unrelated to
 * narrative-arc progression.</p>
 *
 * <p>Stored under {@link DataComponents#CUSTOM_DATA} with key {@code dt_shared_book_found},
 * distinct from the existing {@code dt_shared_book} prefix so the two meanings never collide.</p>
 */
public final class SharedBookFoundTag {

    /** Boolean marker — present + true means "this book was discovered from the community pool". */
    public static final String NBT_KEY = "dt_shared_book_found";

    /**
     * "Has been held" marker, mirroring {@link RandomBookTag#NBT_HELD}. Set the first time a
     * discovered book lands in a player's mainhand or offhand slot (see
     * {@code NarrativeBookEvents.onEquipmentChange}). The burn flow gates on this so a discovered
     * book falling out of a broken pot / chest / hopper doesn't ignite until a player has actually
     * picked it up. Sticky: never cleared once set.
     */
    public static final String NBT_HELD = "dt_shared_book_found_held";

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

    /**
     * Idempotently flag {@code stack} as "has been held by a player". Subsequent drops/reads will
     * burn. No-op if the stack already carries the marker.
     */
    public static void markHeld(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_HELD, true));
    }

    /**
     * True when {@code stack} has been held by a player at least once (see {@link #markHeld}).
     * Safe to call on any stack — returns {@code false} for empty / non-discovered-book stacks.
     */
    public static boolean isHeld(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_HELD, Tag.TAG_BYTE) && tag.getBoolean(NBT_HELD);
    }
}
