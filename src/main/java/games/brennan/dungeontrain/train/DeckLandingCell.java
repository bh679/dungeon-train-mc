package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Picks a ship-local cell a mob can land on near a column of a carriage: the carriage roof or its
 * interior floor. Used by {@code EndermanTrainTeleportEvents} so an enderman hopping over the train
 * lands on it rather than dropping through to the ground (carriage blocks only exist in the Sable
 * plot, so vanilla's ground search never sees them).
 *
 * <p>The column under {@code centre} is tried first; when it holds no standable cell (a flatbed gap,
 * a wall the full height), rings of growing radius around it are tried, nearest ring first. Within
 * a ring the standable cell whose Y is nearest {@code preferY} wins, so a mob already inside a
 * carriage keeps to the floor and one outside lands on the roof.</p>
 */
public final class DeckLandingCell {

    /** How far (in cells, Chebyshev) the search spreads out from the target column. */
    public static final int MAX_RADIUS = 2;

    private DeckLandingCell() {}

    /**
     * @param centre    ship-local target column (its Y is ignored)
     * @param minY      lowest feet Y to try, inclusive
     * @param maxY      highest feet Y to try, inclusive
     * @param preferY   feet Y to land nearest to
     * @param standable true when a mob's feet can go at this ship-local cell
     */
    public static Optional<BlockPos> find(BlockPos centre, int minY, int maxY, int preferY,
                                          Predicate<BlockPos> standable) {
        for (int r = 0; r <= MAX_RADIUS; r++) {
            Optional<BlockPos> best = bestInRing(centre, r, minY, maxY, preferY, standable);
            if (best.isPresent()) return best;
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> bestInRing(BlockPos centre, int r, int minY, int maxY, int preferY,
                                                 Predicate<BlockPos> standable) {
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos cell = new BlockPos(centre.getX() + dx, y, centre.getZ() + dz);
                    int score = Math.abs(y - preferY) * 16 + Math.abs(dx) + Math.abs(dz);
                    if (score < bestScore && standable.test(cell)) {
                        best = cell;
                        bestScore = score;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
