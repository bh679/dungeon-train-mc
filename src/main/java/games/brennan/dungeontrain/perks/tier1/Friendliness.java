package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Friendliness (Lover): PlayerMobs like you more and animals are easier to tame.
 * <ul>
 *   <li>PlayerMob feeling: each rank closes {@link #FEELING_GAP_CLOSED_PER_RANK} of the remaining
 *       gap between what the mob recorded and the ledger's top ({@link #feelingFor}), so the
 *       feeling climbs toward full love without ever reaching it ({@code mixin/PlayerMobFeelingMixin}).</li>
 *   <li>Taming: every vanilla tame roll ({@code random.nextInt(bound) == 0} in Wolf, Cat, Ocelot,
 *       Parrot) gets {@code rank} extra independent rolls ({@link #tameRoll}), so the chance per
 *       feed goes from 1/bound to 1 - (1 - 1/bound)^(1 + rank), never 100%.</li>
 * </ul>
 */
public final class Friendliness {

    /** Share of the gap to the ledger's top that one rank closes. */
    public static final double FEELING_GAP_CLOSED_PER_RANK = 0.06;

    private Friendliness() {}

    /** Fraction of the gap closed at {@code rank}: {@code 1 - 0.94^rank}. */
    public static double gapClosed(int rank) {
        return 1.0 - Math.pow(1.0 - FEELING_GAP_CLOSED_PER_RANK, Math.max(0, rank));
    }

    /** The feeling a mob shows toward a player at {@code rank}, from what it recorded and the ledger's top. */
    public static float feelingFor(float recorded, int rank, float max) {
        if (rank <= 0 || recorded >= max) return recorded;
        return (float) (recorded + (max - recorded) * gapClosed(rank));
    }

    /** Friendliness rank of {@code target} as seen by a PlayerMob: 0 unless it is a player. */
    public static int rankOf(LivingEntity target) {
        return target instanceof Player player ? Perks.rank(player, TierOnePerks.FRIENDLINESS) : 0;
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
