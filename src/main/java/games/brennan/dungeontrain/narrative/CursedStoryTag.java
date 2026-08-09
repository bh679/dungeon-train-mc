package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.OptionalInt;

/**
 * Identity of the landed Death Note curse a cursed welcome book tells the story of — the relay note
 * id, stamped alongside the usual {@link StartingBookTag} so the close handler knows <em>which</em>
 * story was just read and can retire exactly that one (see {@code StartingBookEvents} →
 * {@link CursedStoryPool#consume} + {@code DeathNoteReporter.markStoryRead}).
 *
 * <p>Stored under {@link DataComponents#CUSTOM_DATA} with its own key prefix
 * ({@code dt_cursed_story}) so it sits beside the starting-book stamp rather than replacing it: a
 * cursed book is still a starting book for close-detection, burning and inventory scanning.</p>
 */
public final class CursedStoryTag {

    /** Relay note id this book's story belongs to. Present only on cursed books. */
    public static final String NBT_NOTE_ID = "dt_cursed_story_id";

    private CursedStoryTag() {}

    /** Stamp {@code noteId} onto {@code stack} (merges into CUSTOM_DATA). Ignores non-positive ids. */
    public static void stamp(ItemStack stack, int noteId) {
        if (noteId <= 0) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> writeNoteId(tag, noteId));
    }

    /**
     * The note id stamped on {@code stack}, or empty when it is not a cursed book. Safe on any stack,
     * including empty ones and ordinary starting books.
     */
    public static OptionalInt read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return OptionalInt.empty();
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return OptionalInt.empty();
        return readNoteId(cd.copyTag());
    }

    /** True when {@code stack} is a cursed story book. */
    public static boolean isCursedBook(ItemStack stack) {
        return read(stack).isPresent();
    }

    // ---------------- Pure CompoundTag codec (unit-testable, no ItemStack) ----------------

    static void writeNoteId(CompoundTag tag, int noteId) {
        tag.putInt(NBT_NOTE_ID, noteId);
    }

    /** Read the note id back out of {@code tag}; empty when absent or non-positive. */
    static OptionalInt readNoteId(CompoundTag tag) {
        if (!tag.contains(NBT_NOTE_ID, Tag.TAG_INT)) return OptionalInt.empty();
        int id = tag.getInt(NBT_NOTE_ID);
        return id > 0 ? OptionalInt.of(id) : OptionalInt.empty();
    }
}
