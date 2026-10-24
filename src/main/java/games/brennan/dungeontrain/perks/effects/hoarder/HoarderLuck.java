package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * Hoarder tier 3 perk <b>Luck</b>: {@code rank} in {@value #LUCK_ROLL_DENOMINATOR} containers roll as if the
 * opener had the Luck effect (rank 1 = 1 in 20, rank 2 = 2 in 20, and so on; rank 20 and up = every one).
 *
 * <p>Plugs into the existing Luck-potion loot bonus ({@code editor/LuckyBonusRoller},
 * {@code event/LuckyLootEvents}): the roll happens once at the moment a chest's pre-baked bonus is spent
 * (first open) or a vase breaks, and on success the opener's luck is lifted to Luck I. A real potion's
 * higher luck is never lowered, so Luck II still gets its bigger range.</p>
 */
public final class HoarderLuck {

    public static final String PERK_ID = "luck";
    /** One rank = one chance in this many containers. */
    public static final int LUCK_ROLL_DENOMINATOR = 20;
    /** The luck a winning roll grants: Luck I. */
    public static final float PERK_LUCK = 1.0f;

    private HoarderLuck() {}

    /** True when this container rolls lucky at {@code rank}: {@code rank} in 20, never at rank 0, always at 20+. */
    public static boolean rollsLucky(int rank, RandomSource random) {
        if (rank <= 0) return false;
        if (rank >= LUCK_ROLL_DENOMINATOR) return true;
        return random.nextInt(LUCK_ROLL_DENOMINATOR) < rank;
    }

    /** The luck the bonus roll should see: lifted to Luck I on a lucky roll, otherwise the player's own. */
    public static float effectiveLuck(float playerLuck, boolean lucky) {
        return lucky ? Math.max(playerLuck, PERK_LUCK) : playerLuck;
    }

    /** {@link #effectiveLuck} for a live player, rolling against their active Luck rank. */
    public static float luckFor(ServerPlayer player, RandomSource random) {
        int rank = Perks.rank(player, PERK_ID);
        return effectiveLuck(player.getLuck(), rollsLucky(rank, random));
    }
}
