package games.brennan.dungeontrain.perks.effects.builder;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.tier1.DriftChance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builder tier 2 perk <b>Own Carriage Return</b> ({@value #ID}): carriages you built come back around
 * to you more often.
 *
 * <p>The shared-slot roll already has an OWN bucket (a relay build by a player in this world, see
 * {@code SharedCarriageRolls}). This perk widens that bucket by {@link #OWN_BONUS_PER_RANK} per active
 * point, paid for out of the POOL share so the FRESH share (new canvases entering the pool) is
 * untouched, and the pool share never drops below {@link #MIN_POOL_SHARE}. It also puts the perk
 * holder's uuid first in the owner lists the own buffers are polled with, and in the own-build
 * prefetch rotation, so the widened bucket is actually served with the holder's builds.</p>
 *
 * <p>The highest rank among the players in the level is what widens the roll: everyone aboard rides
 * the same train, and the holder gets first refusal on the own buffer.</p>
 */
public final class OwnCarriageReturn {

    public static final String ID = "own-carriage-return";

    /** How much of the POOL share moves to OWN per active point. Balancing knob. */
    public static final double OWN_BONUS_PER_RANK = 0.10;

    /** The POOL share is never pushed below this, so other players' builds keep arriving. */
    public static final double MIN_POOL_SHARE = 0.10;

    /** A pool/own split for {@code SharedCarriageRolls.bucket}; the remainder is FRESH. */
    public record Split(double pool, double own) {}

    private OwnCarriageReturn() {}

    /**
     * Widen the OWN share by rank, taking from POOL and leaving FRESH alone. Rank 0 returns the inputs
     * unchanged; inputs are expected to already sum to at most 1.
     */
    public static Split split(double pool, double own, int rank) {
        if (rank <= 0) return new Split(pool, own);
        double wanted = OWN_BONUS_PER_RANK * rank;
        double available = Math.max(0.0, pool - MIN_POOL_SHARE);
        double moved = Math.min(wanted, available);
        return new Split(pool - moved, own + moved);
    }

    /**
     * The configured split after the tier 1 Drift Chance perk has scaled it (more drifting slots at
     * all), widened toward OWN by the best Own Carriage Return rank aboard {@code level}.
     */
    public static Split split(ServerLevel level) {
        double driftBoost = DriftChance.multiplierFor(level);
        return split(DriftChance.boosted(DungeonTrainConfig.getSharedCarriagePoolChance(), driftBoost),
                DriftChance.boosted(DungeonTrainConfig.getSharedCarriageOwnChance(), driftBoost), rankIn(level));
    }

    /** The highest active rank among the players in {@code level}; 0 when nobody holds the perk. */
    public static int rankIn(ServerLevel level) {
        int best = 0;
        for (ServerPlayer p : level.players()) best = Math.max(best, Perks.rank(p, ID));
        return best;
    }

    /**
     * The players of {@code level} ordered by rank, highest first, otherwise in their original order,
     * so an own-buffer poll tries the perk holder's builds before anyone else's.
     */
    public static List<ServerPlayer> byRank(List<ServerPlayer> players) {
        List<ServerPlayer> out = new ArrayList<>(players);
        out.sort(Comparator.comparingInt((ServerPlayer p) -> Perks.rank(p, ID)).reversed());
        return out;
    }

    /** Dashless uuids (as the relay stores them) of {@code level}'s players, perk holders first. */
    public static List<String> orderedOnlineUuids(ServerLevel level) {
        List<String> out = new ArrayList<>();
        for (ServerPlayer p : byRank(level.players())) out.add(p.getUUID().toString().replace("-", ""));
        return out;
    }

    /**
     * Which player's builds the own-prefetch tick should ask the relay for. Perk holders take every
     * other tick (rotating among themselves), the rest of the ticks round-robin everyone as before.
     * Pure: {@code ranks} is each player's rank in the same order as {@code players}.
     */
    public static <T> T prefetchOwner(List<T> players, List<Integer> ranks, int cursor) {
        List<T> holders = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            if (ranks.get(i) > 0) holders.add(players.get(i));
        }
        if (!holders.isEmpty() && (cursor & 1) == 0) {
            return holders.get(Math.floorMod(cursor / 2, holders.size()));
        }
        return players.get(Math.floorMod(cursor, players.size()));
    }

    /** {@link #prefetchOwner} over live players. */
    public static ServerPlayer prefetchOwner(List<ServerPlayer> players, int cursor) {
        List<Integer> ranks = new ArrayList<>(players.size());
        for (ServerPlayer p : players) ranks.add(Perks.rank(p, ID));
        return prefetchOwner(players, ranks, cursor);
    }
}
