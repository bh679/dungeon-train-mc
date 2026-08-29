package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/**
 * Per-stack identity of a {@link RunStatBookFactory Faulthurst stat book}, stamped via
 * {@link DataComponents#CUSTOM_DATA}.
 *
 * <p>Four fields, each earning its place:</p>
 *
 * <ul>
 *   <li>{@link #NBT_SEED} — the roll seed. Picks the opener and the follow-up, so the book's
 *       wording is decided once at the container and never shifts under the reader. Only the
 *       NUMBER is live.</li>
 *   <li>{@link #NBT_SUBJECT} — which counter the book is about, chosen at the first refresh from
 *       whatever the holder had actually done by then ({@link RunStatSubject#eligible}) and then
 *       fixed. Absent until that first refresh: a container cannot choose it, because at
 *       container-load time there is no reader to have done anything.</li>
 *   <li>{@link #NBT_VALUE} — the number as last RENDERED. A refresh that would produce the same
 *       string skips the write entirely, so a book whose count is steady costs one comparison a
 *       second and never touches the stack (see {@code NarrativeBookEvents.onPlayerTick}).</li>
 *   <li>{@link #NBT_LOCKED} — set the first time the book is opened. From then on the page is
 *       frozen: a memento of the moment it was read, rather than a dial that keeps moving.</li>
 * </ul>
 *
 * <p>Distinct key prefix ({@code dt_stat_book_*}) so it cannot collide with
 * {@link LeaderboardBookPendingTag}'s {@code dt_leaderboard_*}, {@link PlayerBookPendingTag}'s, or
 * the {@code dt_random_book*} / {@code dt_shared_book*} keys.</p>
 */
public final class RunStatBookTag {

    /** "This stack is a Faulthurst stat book" flag. */
    public static final String NBT_MARKER = "dt_stat_book";

    /** Roll seed — fixes the opener and follow-up for the life of the stack. */
    public static final String NBT_SEED = "dt_stat_book_seed";

    /** {@link RunStatSubject#id()} of the counter this book is about. Absent until first refresh. */
    public static final String NBT_SUBJECT = "dt_stat_book_subject";

    /** The number as last rendered into the page — the skip-the-write comparison. */
    public static final String NBT_VALUE = "dt_stat_book_value";

    /** Set on first open. The page never changes again. */
    public static final String NBT_LOCKED = "dt_stat_book_locked";

    /**
     * "Has been held" marker, mirroring {@link SharedBookFoundTag#NBT_HELD}. Set the first time the
     * note lands in a player's mainhand or offhand slot ({@code RunStatBookEvents.onEquipmentChange}),
     * and what {@link BurnableBookTag#isBurnable} gates the burn on — so a note spilled from a broken
     * chest, or shelved in a Stat Room, doesn't ignite before anyone has picked it up.
     *
     * <p>Separate from {@link #NBT_LOCKED}, which answers a different question: held is "a player has
     * this", locked is "a player has READ this and the number must stop moving". A note can be held
     * and unlocked for as long as its finder carries it around unopened.</p>
     */
    public static final String NBT_HELD = "dt_stat_book_held";

    private RunStatBookTag() {}

    /** Idempotently mark {@code stack} a stat book rolled with {@code seed}, subject not yet chosen. */
    public static void stamp(ItemStack stack, long seed) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putBoolean(NBT_MARKER, true);
            tag.putLong(NBT_SEED, seed);
        });
    }

    /** True when {@code stack} is a stat book. Safe on any stack. */
    public static boolean is(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_MARKER, Tag.TAG_BYTE) && tag.getBoolean(NBT_MARKER);
    }

    /** The roll seed, or {@code fallback} when the stack carries none. */
    public static long seed(ItemStack stack, long fallback) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_SEED, Tag.TAG_LONG) ? tag.getLong(NBT_SEED) : fallback;
    }

    /** The subject this book has settled on, or empty while it is still unchosen (or unrecognised). */
    public static Optional<RunStatSubject> subject(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        if (tag == null || !tag.contains(NBT_SUBJECT, Tag.TAG_STRING)) return Optional.empty();
        return RunStatSubject.byId(tag.getString(NBT_SUBJECT));
    }

    /** The number as last rendered into the page, or {@code ""} when nothing has been baked yet. */
    public static String renderedValue(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_VALUE, Tag.TAG_STRING) ? tag.getString(NBT_VALUE) : "";
    }

    /** Record what this book now says, so the next refresh can tell whether anything moved. */
    public static void recordBaked(ItemStack stack, RunStatSubject subject, String renderedValue) {
        if (stack == null || stack.isEmpty() || subject == null) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(NBT_SUBJECT, subject.id());
            tag.putString(NBT_VALUE, renderedValue == null ? "" : renderedValue);
        });
    }

    /** True once the book has been opened — the page is final and must not be re-baked. */
    public static boolean isLocked(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_LOCKED, Tag.TAG_BYTE) && tag.getBoolean(NBT_LOCKED);
    }

    /** Freeze the page. Called the first time the book is right-clicked open. */
    public static void lock(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_LOCKED, true));
    }

    /** Idempotently flag {@code stack} as "has been held by a player" — later drops/reads burn. */
    public static void markHeld(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_HELD, true));
    }

    /** True once the note has reached a player's hand at least once (see {@link #markHeld}). */
    public static boolean isHeld(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_HELD, Tag.TAG_BYTE) && tag.getBoolean(NBT_HELD);
    }

    private static CompoundTag tagOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return null;
        return cd.copyTag();
    }
}
