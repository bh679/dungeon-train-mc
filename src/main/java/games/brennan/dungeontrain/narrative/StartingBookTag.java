package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/**
 * Marker + identity tag stamped onto every welcome book at spawn time so client
 * + server can recognise it, AND so the read handler can credit the exact
 * {@code (book, variant)} toward the {@code all_starting_books} advancement.
 * Used by:
 * <ul>
 *   <li><b>Client</b> — {@code StartingBookClientEvents.onScreenClosing} checks
 *       {@link #isStartingBook} on the player's held stack to decide whether a
 *       closing {@code BookViewScreen} was showing a starting book; if so, the
 *       client fires {@code StartingBookClosedPacket}.</li>
 *   <li><b>Server</b> — {@code StartingBookEvents.findAndRemoveBurnableBook}
 *       scans the player's inventory for stamped stacks when the close packet
 *       arrives, so the right book gets dropped + burned, and
 *       {@code StartingBookEvents.handleStartingBookClosed} {@link #read reads
 *       the identity} to mark the book seen — read-tracking for the
 *       Inter-Reality Passenger advancement.</li>
 * </ul>
 *
 * <p>Stored under {@link DataComponents#CUSTOM_DATA} alongside any other
 * mod NBT — uses a unique key prefix ({@code dt_starting_book}) so it doesn't
 * collide with {@link NarrativeBookTag} ({@code dt_narrative_*}) or
 * {@link RandomBookTag} ({@code dt_random_book*}).</p>
 *
 * <p>The tag is intentionally NOT a {@link RandomBookTag} — random books get
 * auto-swapped to unseen content on the second hold (see
 * {@link NarrativeBookEvents#onEquipmentChange}); starting books must keep
 * their original content so the close-and-burn flow makes narrative sense.</p>
 */
public final class StartingBookTag {

    /** Boolean marker — present + true means "this is a starting book". */
    public static final String NBT_KEY = "dt_starting_book";
    /** Source book basename — the world-scoped seen-store key (see {@link NarrativeProgressData}). */
    public static final String NBT_BASENAME = "dt_starting_book_basename";
    /** 0-based variant index of the delivered book. */
    public static final String NBT_VARIANT = "dt_starting_book_variant";

    private StartingBookTag() {}

    /** Recoverable identity of a delivered starting book: which book, which variant. */
    public record StartingBookIdentity(String basename, int variantIndex) {}

    /**
     * Stamp the marker + identity onto {@code stack} (creates / merges into
     * CUSTOM_DATA). The boolean {@link #NBT_KEY} is always set so
     * {@link #isStartingBook} and the close-detection / burn flow keep working.
     */
    public static void stamp(ItemStack stack, String basename, int variantIndex) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
            tag -> writeIdentity(tag, basename, variantIndex));
    }

    /**
     * True when {@code stack} carries the starting-book marker. Used by both
     * the client (close-detection) and server (inventory scan) sides — safe
     * to call on any ItemStack including empty / non-book stacks.
     */
    public static boolean isStartingBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(NBT_KEY, Tag.TAG_BYTE) && tag.getBoolean(NBT_KEY);
    }

    /**
     * Recover the {@link StartingBookIdentity} stamped on {@code stack}, or
     * {@link Optional#empty()} when the stack is not an identity-stamped
     * starting book. Non-books, random books, and legacy welcome books stamped
     * before identity tracking landed (which carry only the boolean marker) all
     * return empty — so callers can treat the seen-mark as a safe no-op.
     */
    public static Optional<StartingBookIdentity> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return Optional.empty();
        return readIdentity(cd.copyTag());
    }

    // ---------------- Pure CompoundTag codec (unit-testable, no ItemStack) ----------------

    /**
     * Write the boolean marker + identity fields into {@code tag}. Package-
     * private and CompoundTag-level so it is unit-testable without bootstrapping
     * Minecraft item registries.
     */
    static void writeIdentity(CompoundTag tag, String basename, int variantIndex) {
        tag.putBoolean(NBT_KEY, true);
        tag.putString(NBT_BASENAME, basename);
        tag.putInt(NBT_VARIANT, variantIndex);
    }

    /**
     * Read the identity back out of {@code tag}. Empty unless the boolean
     * marker is set AND both identity fields are present — so a legacy
     * boolean-only stamp returns empty.
     */
    static Optional<StartingBookIdentity> readIdentity(CompoundTag tag) {
        if (!(tag.contains(NBT_KEY, Tag.TAG_BYTE) && tag.getBoolean(NBT_KEY))) {
            return Optional.empty();
        }
        if (!tag.contains(NBT_BASENAME, Tag.TAG_STRING) || !tag.contains(NBT_VARIANT, Tag.TAG_INT)) {
            return Optional.empty();
        }
        return Optional.of(new StartingBookIdentity(tag.getString(NBT_BASENAME), tag.getInt(NBT_VARIANT)));
    }
}
