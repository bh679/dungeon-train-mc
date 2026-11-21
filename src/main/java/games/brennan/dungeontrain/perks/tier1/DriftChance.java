package games.brennan.dungeontrain.perks.tier1;

import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.TierOnePerks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Drift Chance (Builder): drifting (shared / community) builds turn up more often in the two places
 * where drift is still rare: whole-group carriages ({@code SharedGroupDrift}, 10% by default) and
 * dimensional rooms ({@code PortalRoomDriftPlanner}, one in fifteen). Single carriage slots are
 * left alone: by default 95% of them already lease a drifting build. Each rank closes
 * {@link #GAP_CLOSED_PER_RANK} of the gap between the configured chance and certainty
 * ({@link #boosted}), so the chance climbs slowly and never reaches 100%. The train is shared by
 * everyone aboard, so the best rank among the level's players counts.
 */
public final class DriftChance {

    /** Share of the gap to 100% that one rank closes. */
    public static final double GAP_CLOSED_PER_RANK = 0.02;

    private DriftChance() {}

    /** {@code 1 - (1 - chance) * 0.98^rank}: the configured chance at rank 0, never 1. */
    public static double boosted(double chance, int rank) {
        if (chance <= 0) return 0;
        if (chance >= 1 || rank <= 0) return Math.min(1.0, chance);
        return 1.0 - (1.0 - chance) * Math.pow(1.0 - GAP_CLOSED_PER_RANK, rank);
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

    /** {@code chance} boosted by the best rank on {@code level}. */
    public static double boostedFor(ServerLevel level, double chance) {
        return boosted(chance, bestRank(level));
    }
}
