package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Identity of a finished {@link LeaderboardBookFactory leaderboard book} — one board, its top ranks,
 * and the reader's own standing — plus the "has been held" marker that arms the burn-after-reading
 * lifecycle in {@link BurnableBookTag#isBurnable}.
 *
 * <p>Deliberately a separate tag from {@link LeaderboardBookPendingTag}: that one marks a slot that
 * has ROLLED a board and is still carrying a random book in its place, and it is cleared the moment
 * the real thing is built. This one marks the real thing, and is never cleared. A stack briefly
 * carries neither ({@code LeaderboardBookFactory.build} stamps it, the pending marker comes off in
 * the same call in {@code NarrativeBookEvents.resolveLeaderboardPending}).</p>
 *
 * <p>The held gate matters here for the same reason it does on {@link RandomBookTag} and
 * {@link SharedBookFoundTag}: a Stat Room shelves boards, and a chest can spill one, and neither
 * should catch fire before a player has picked it up. Resolution alone is not "held" — the inventory
 * sweep in {@code NarrativeBookEvents.onPlayerTick} resolves placeholders sitting in any slot.</p>
 *
 * <p>Keys are {@code dt_leaderboard_book*}, distinct from the pending tag's
 * {@code dt_leaderboard_pending} / {@code dt_leaderboard_seed}, from {@code dt_stat_book*}, and from
 * the {@code dt_random_book*} / {@code dt_shared_book*} families.</p>
 */
public final class LeaderboardBookTag {

    /** "This stack is a built leaderboard book" flag. */
    public static final String NBT_MARKER = "dt_leaderboard_book";

    /**
     * "Has been held" marker, mirroring {@link SharedBookFoundTag#NBT_HELD}. Set the first time the
     * book lands in a player's mainhand or offhand slot. Sticky: never cleared once set.
     */
    public static final String NBT_HELD = "dt_leaderboard_book_held";

    private LeaderboardBookTag() {}

    /** Idempotently mark {@code stack} a leaderboard book. Safe on any stack. */
    public static void stamp(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_MARKER, true));
    }

    /** True when {@code stack} is a built leaderboard book. Safe on any stack. */
    public static boolean is(ItemStack stack) {
        return flag(stack, NBT_MARKER);
    }

    /** Idempotently flag {@code stack} as "has been held by a player" — later drops/reads burn. */
    public static void markHeld(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(NBT_HELD, true));
    }

    /** True when {@code stack} has reached a player's hand at least once (see {@link #markHeld}). */
    public static boolean isHeld(ItemStack stack) {
        return flag(stack, NBT_HELD);
    }

    private static boolean flag(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(key, Tag.TAG_BYTE) && tag.getBoolean(key);
    }
}
