package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Friendliness (Lover): PlayerMobs like you more and animals are easier to tame.
 * <ul>
 *   <li>PlayerMob feeling: {@link #feelingBonus(int)} is added to what a PlayerMob feels toward
 *       the player ({@code mixin/PlayerMobFeelingMixin}), clamped by the caller to the ledger range.</li>
 *   <li>Taming: every vanilla tame roll ({@code random.nextInt(bound) == 0} in Wolf, Cat, Ocelot,
 *       Parrot) gets {@code rank} extra independent rolls ({@link #tameRoll}), so the chance per
 *       feed goes from 1/bound to 1 - (1 - 1/bound)^(1 + rank).</li>
 * </ul>
 */
public final class Friendliness {

    /** Feeling units per rank; the PlayerMob ledger runs {@code FeelingLedger.MIN..MAX}. */
    public static final float FEELING_PER_RANK = 0.1f;

    private Friendliness() {}

    public static float feelingBonus(int rank) {
        return Math.max(0, rank) * FEELING_PER_RANK;
    }

    /** Feeling bonus for {@code target} as seen by a PlayerMob: 0 unless it is a player with rank. */
    public static float feelingBonusFor(LivingEntity target) {
        if (!(target instanceof Player player)) return 0f;
        return feelingBonus(Perks.rank(player, TierOnePerks.FRIENDLINESS));
    }

    /**
     * The value a vanilla tame roll should see. {@code vanilla} is what {@code nextInt(bound)}
     * returned; 0 means success. With rank, re-roll up to {@code rank} more times and return 0 if
     * any of them hits.
     */
    public static int tameRoll(int vanilla, int bound, int rank, RandomSource rng) {
        if (vanilla == 0 || rank <= 0 || bound <= 1) return vanilla;
        for (int i = 0; i < rank; i++) {
            if (rng.nextInt(bound) == 0) return 0;
        }
        return vanilla;
    }

    /** Chance per feed after the perk, for tests and the balance note: 1 - (1 - 1/bound)^(1 + rank). */
    public static double tameChance(int bound, int rank) {
        double miss = 1.0 - 1.0 / bound;
        return 1.0 - Math.pow(miss, 1 + Math.max(0, rank));
    }

    /** Roll helper for the mixins: the interacting player's rank, 0 for anything else. */
    public static int tameRollFor(int vanilla, int bound, Player player, RandomSource rng) {
        return tameRoll(vanilla, bound, Perks.rank(player, TierOnePerks.FRIENDLINESS), rng);
    }
}
