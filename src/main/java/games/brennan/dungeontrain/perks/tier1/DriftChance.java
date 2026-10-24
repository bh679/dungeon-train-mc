package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Drift Chance (Builder): more drifting (shared / community) carriages roll into the train. The
 * train is one object shared by everyone aboard, so the best rank among the level's online
 * players counts; both the per-slot pool/own chances and the group chance are multiplied by
 * {@link #multiplier(int)} right before the seeded roll ({@code TrainAssembler.tryLeaseShared},
 * {@code SharedGroupDrift}). Pure functions so the numbers are unit-tested.
 */
public final class DriftChance {

    /** +15% of the configured chance per rank. */
    public static final double PER_RANK = 0.15;

    private DriftChance() {}

    public static double multiplier(int rank) {
        return 1.0 + Math.max(0, rank) * PER_RANK;
    }

    /** A chance scaled by the multiplier, never above 1. */
    public static double boosted(double chance, double multiplier) {
        if (chance <= 0) return 0;
        return Math.min(1.0, chance * Math.max(1.0, multiplier));
    }

    /** Best Drift Chance rank among the level's players, 0 with nobody online. */
    public static int bestRank(ServerLevel level) {
        int best = 0;
        if (level == null) return 0;
        for (ServerPlayer p : level.players()) {
            best = Math.max(best, Perks.rank(p, TierOnePerks.DRIFT_CHANCE));
        }
        return best;
    }

    public static double multiplierFor(ServerLevel level) {
        return multiplier(bestRank(level));
    }
}
