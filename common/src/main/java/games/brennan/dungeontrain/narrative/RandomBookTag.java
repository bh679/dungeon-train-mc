package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/**
 * Stamps and reads the {@code (bookBasename, variantIndex)} identifier on
 * random books via {@link DataComponents#CUSTOM_DATA}. Sibling to
 * {@link NarrativeBookTag} — same data component, different key prefix
 * ({@code dt_random_book*} vs {@code dt_narrative_*}) so the two systems
 * co-exist without collision.
 *
 * <p>Used by the equipment-change handler in
 * {@link NarrativeBookEvents} to detect "our" books and, when the player
 * has already seen the tuple, swap the stack's content to an unseen pick
 * before they right-click to open.</p>
 */
public final class RandomBookTag {

    /** Book basename, e.g. {@code "musings_of_faulthurst"}. */
    public static final String NBT_BOOK = "dt_random_book";
    /** Variant index inside the book, 1-based to match the {@code Letter} convention. */
    public static final String NBT_VARIANT = "dt_random_book_variant";
    /**
     * "Has been held" marker. Set on a random book the first time it lands
     * in a player's mainhand or offhand slot (see {@code NarrativeBookEvents
     * .onEquipmentChange}). The burn flow gates on this so a random book
     * dropping out of a broken pot / chest / hopper doesn't ignite until a
     * player has actually picked it up.
     *
     * <p>Sticky: never cleared once set. Survives inventory moves, so a book
     * that was held then tucked into a backpack still burns when later
     * Q-thrown or dropped on death.</p>
     */
    public static final String NBT_HELD = "dt_random_book_held";

    private RandomBookTag() {}

    /** Stamp the identifier onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack, String bookBasename, int variantIndex) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(NBT_BOOK, bookBasename);
            tag.putInt(NBT_VARIANT, variantIndex);
        });
    }

    /**
     * Idempotently flag {@code stack} as "has been held by a player".
     * Subsequent drops will burn. No-op if the stack already carries the
     * marker.
     */
    public static void markHeld(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_HELD, true));
    }

    /**
     * True when {@code stack} has been held by a player at least once (see
     * {@link #markHeld}). Safe to call on any stack — returns {@code false}
     * for empty / non-random-book stacks.
     */
    public static boolean isHeld(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_HELD, Tag.TAG_BYTE) && tag.getBoolean(NBT_HELD);
    }

    /**
     * Decode the identifier from {@code stack}'s CUSTOM_DATA. Empty when the
     * stack has no random-book tag (vanilla written book, narrative book,
     * foreign book, etc.).
     */
    public static Optional<RandomBookIdentity> read(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return Optional.empty();
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(NBT_BOOK, Tag.TAG_STRING) || !tag.contains(NBT_VARIANT, Tag.TAG_INT)) {
            return Optional.empty();
        }
        return Optional.of(new RandomBookIdentity(tag.getString(NBT_BOOK), tag.getInt(NBT_VARIANT)));
    }

    /** {@code (bookBasename, variantIndex)} pair as stamped on a random book. */
    public record RandomBookIdentity(String basename, int variantIndex) {}
}
