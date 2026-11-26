package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.perks.PerkOverflow;
import games.brennan.dungeontrain.perks.Perks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * Hoarder tier 3 "Luck": a container the player opens (a lucky chest or a vase, see
 * {@code event/LuckyLootEvents}) rolls its bonus as if the player had Luck. Each point is
 * {@value #PERCENT_PER_RANK}% on the overflow curve ({@link PerkOverflow}): up to 20 points that is the
 * chance of Luck I; past 20 Luck I is certain and the rest is the chance of Luck II, and so on up.
 * The player's own Luck (potions, attribute) still counts when it is higher.
 */
public final class HoarderLuck {

    public static final String PERK_ID = "luck";
    public static final int PERCENT_PER_RANK = 5;

    private HoarderLuck() {}

    /** The overflow percentage at {@code rank}: 5% per point, uncapped. */
    public static int percent(int rank) {
        return rank <= 0 ? 0 : rank * PERCENT_PER_RANK;
    }

    /** Luck levels one container roll gets at {@code rank}. */
    public static int rollLevels(int rank, RandomSource random) {
        return PerkOverflow.rollSteps(percent(rank), random);
    }

    /** The luck a roll uses: the higher of the player's own and the perk's levels. */
    public static float effectiveLuck(float playerLuck, int perkLevels) {
        return Math.max(playerLuck, perkLevels);
    }

    /** The luck {@code player} opens a container with, rolling the perk once. */
    public static float luckFor(ServerPlayer player, RandomSource random) {
        int rank = Perks.rank(player, PERK_ID);
        if (rank <= 0) return player.getLuck();
        return effectiveLuck(player.getLuck(), rollLevels(rank, random));
    }
}
