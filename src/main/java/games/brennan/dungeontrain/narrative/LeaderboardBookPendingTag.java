package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Per-stack "this slot rolled a leaderboard book" marker, stamped via
 * {@link DataComponents#CUSTOM_DATA} and carrying the seed the roll used.
 *
 * <p>Written by the loot intercept in {@code ContainerContentsRoller.rollItemStack} and resolved
 * when the stack first reaches a player's hand ({@code NarrativeBookEvents.onEquipmentChange}),
 * for two reasons that both need a specific reader: the closing "where you stand" line is that
 * player's rank, and the board itself may not have been fetched yet when the container loaded.</p>
 *
 * <p>The seed rides along so the category is decided once and stays decided. Without it the same
 * chest would offer a different board to each player who picked it up, and re-rolling on every hand
 * change would make a book about carriages become a book about donations mid-inventory.</p>
 *
 * <p>Distinct key prefix ({@code dt_leaderboard_*}) so it cannot collide with
 * {@link PlayerBookPendingTag}'s or the {@code dt_random_book*} / {@code dt_shared_book*} keys.</p>
 */
public final class LeaderboardBookPendingTag {

    /** "Awaiting resolution into a leaderboard book for whoever picks it up" flag. */
    public static final String NBT_PENDING = "dt_leaderboard_pending";

    /** The roll seed, so the category is stable across hands and reloads. */
    public static final String NBT_SEED = "dt_leaderboard_seed";

    private LeaderboardBookPendingTag() {}

    /** Idempotently flag {@code stack} as an unresolved leaderboard book rolled with {@code seed}. */
    public static void markPending(ItemStack stack, long seed) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putBoolean(NBT_PENDING, true);
            tag.putLong(NBT_SEED, seed);
        });
    }

    /** True when {@code stack} is an unresolved leaderboard book. Safe on any stack. */
    public static boolean isPending(ItemStack stack) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_PENDING, Tag.TAG_BYTE) && tag.getBoolean(NBT_PENDING);
    }

    /** The seed stamped at roll time, or {@code fallback} when the stack carries none. */
    public static long seed(ItemStack stack, long fallback) {
        CompoundTag tag = tagOf(stack);
        return tag != null && tag.contains(NBT_SEED, Tag.TAG_LONG) ? tag.getLong(NBT_SEED) : fallback;
    }

    /** Remove the marker and its seed (after a successful resolution). */
    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.remove(NBT_PENDING);
            tag.remove(NBT_SEED);
        });
    }

    private static CompoundTag tagOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return null;
        return cd.copyTag();
    }
}
