package games.brennan.dungeontrain.train;

import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.function.LongPredicate;

/**
 * Finds a mob stuck in a carriage floor (standing on the track bed under the carriage, its head
 * through the deck) and says where to put it instead.
 *
 * <p>Such a mob was never aboard: it got there because a spawner reading only the main level saw
 * the carriage's volume as air (see {@link CarriageSpace}). Until 0.1397.0 kill-ahead discarded it
 * on sight; once that stopped — so mobs that walk off the train survive — it rode along with its eyes
 * and head poking through the floor, an Enderman staring up from under the deck. This is the
 * safety net for any that still get in: they are moved out to the ground beside the carriage.</p>
 *
 * <p>A mob knocked off the train lands there too, through the gap between carriage groups. Within
 * {@link #ROOMS_NEAR_PLAYER} rooms of a player it is not moved or discarded: it stays down on the
 * tracks and is crushed while a carriage passes over it.</p>
 *
 * <p>Pure functions over primitives so they can be unit-tested without a game bootstrap. The
 * carriage box is its Sable world box: {@code minY} is the bottom of the floor block, so the deck
 * surface is {@code minY + 1}.</p>
 */
public final class WedgedUnderFloor {

    /**
     * How far below the deck surface a mob's feet must be before it counts as under the floor rather
     * than standing on it (Sable's carry is not exact to the millimetre).
     */
    static final double BELOW_DECK_BLOCKS = 0.5;

    /** Gap left between the carriage's side and the moved mob's box. */
    static final double SIDE_CLEARANCE = 0.05;

    /**
     * The furthest a moved mob may drop to the ground beside the carriage. Vanilla fall damage
     * starts past 3 blocks; a side spot with nothing that close below it is over a drop (a bridge,
     * a cliff) and is not used.
     */
    static final int MAX_SAFE_DROP_BLOCKS = 3;

    /**
     * How many rooms (carriage lengths) from a player a mob that fell onto the tracks is spared.
     * Players saw mobs knocked through the gap between carriage groups vanish on bridges (0.1569.0
     * log: pillagers, husks, wither skeletons "Discarded ... no ground beside it"); near a player
     * it now stays on the rails and is crushed instead.
     */
    static final int ROOMS_NEAR_PLAYER = 2;

    /** Across the track, how far outside the carriage box a player still counts as near. */
    static final double NEAR_PLAYER_ACROSS_BLOCKS = 4.0;

    /** Above or below the mob, how far a player still counts as near (a rider on the roof). */
    static final double NEAR_PLAYER_VERTICAL_BLOCKS = 12.0;

    /**
     * Younger than this, a mob under the floor was spawned there, not knocked off the train: it
     * keeps the old route (beside the carriage, else discarded) even near a player. The 0.1569.0
     * log's zombified piglins under the floor were all 1 tick old; a creeper summoned on the deck
     * and knocked straight through the gap reached the rails at 5 ticks old.
     */
    static final int MIN_FALLEN_AGE_TICKS = 3;

    /**
     * Ticks spent under carriages between crush hits on a fallen mob: 30 s, so a mob left on the
     * tracks dies slowly rather than in seconds.
     */
    static final int CRUSH_INTERVAL_TICKS = 600;

    /** Each crush hit: half a heart. */
    static final float CRUSH_DAMAGE = 1.0f;

    /** Where a wedged mob goes. */
    public enum Route {
        /** An owned pet with its owner online: sent to the owner, never over the side. */
        TO_OWNER,
        /** Beside the carriage, on ground. */
        BESIDE
    }

    private WedgedUnderFloor() {}

    public static Route route(boolean ownedPet, boolean ownerOnline) {
        return ownedPet && ownerOnline ? Route.TO_OWNER : Route.BESIDE;
    }

    /**
     * True when the mob's centre is inside the carriage footprint, its feet are below the deck,
     * and its eyes are in the floor or above it.
     *
     * <p>Eyes, not the top of the box: a spider on the ground beside the track grazes the bottom of
     * the floor with its back (0.09 of a block) as it walks at a rider, but nothing of it shows on
     * the deck. Counting that moved it out a hair's width, it walked straight back, and it was moved
     * again about once a second for minutes (0.1407.0 log, 279 lines).</p>
     */
    public static boolean isWedged(double x, double feetY, double z, double eyeY,
                                   double cMinX, double cMinY, double cMinZ,
                                   double cMaxX, double cMaxZ) {
        boolean inFootprint = x > cMinX && x < cMaxX && z > cMinZ && z < cMaxZ;
        double deckY = cMinY + 1.0;
        return inFootprint && feetY < deckY - BELOW_DECK_BLOCKS && eyeY > cMinY;
    }

    /**
     * Where to move a wedged mob, best first: just outside each long side of the carriage, nearer
     * side first. "Sides" are across the direction of travel ({@code velX}, {@code velZ}) so the
     * mob never lands in the runway ahead, where kill-ahead would take it. An idle carriage uses
     * whichever axis has the nearer edge.
     *
     * @param halfWidth half the mob's box width
     * @return two {x, z} pairs: {@code {x0, z0, x1, z1}}
     */
    public static double[] sideTargets(double x, double z, double halfWidth,
                                       double cMinX, double cMinZ, double cMaxX, double cMaxZ,
                                       double velX, double velZ) {
        boolean acrossZ;
        if (velX == 0 && velZ == 0) {
            double nearestX = Math.min(x - cMinX, cMaxX - x);
            double nearestZ = Math.min(z - cMinZ, cMaxZ - z);
            acrossZ = nearestZ <= nearestX;
        } else {
            acrossZ = Math.abs(velX) >= Math.abs(velZ);
        }

        double out = halfWidth + SIDE_CLEARANCE;
        if (acrossZ) {
            double low = cMinZ - out;
            double high = cMaxZ + out;
            boolean lowFirst = z - cMinZ <= cMaxZ - z;
            return lowFirst ? new double[] {x, low, x, high} : new double[] {x, high, x, low};
        }
        double low = cMinX - out;
        double high = cMaxX + out;
        boolean lowFirst = x - cMinX <= cMaxX - x;
        return lowFirst ? new double[] {low, z, high, z} : new double[] {high, z, low, z};
    }

    /**
     * True when a mob under the floor fell off the train rather than spawned there; see
     * {@link #MIN_FALLEN_AGE_TICKS}.
     */
    public static boolean fellOffTrain(int ageTicks) {
        return ageTicks >= MIN_FALLEN_AGE_TICKS;
    }

    /**
     * True when the player at ({@code px}, {@code py}, {@code pz}) is within
     * {@link #ROOMS_NEAR_PLAYER} rooms of the mob at ({@code mx}, {@code my}, {@code mz}) along the
     * track, and beside or on the carriage across it.
     *
     * <p>"Along" is the direction of travel ({@code velX}, {@code velZ}); an idle carriage uses its
     * box's long axis (a carriage box is a whole group, so it is always the longer one). The mob's
     * own room plus two either side: up to {@code ROOMS_NEAR_PLAYER + 1} room lengths away.</p>
     */
    public static boolean withinRooms(double px, double py, double pz,
                                      double mx, double my, double mz,
                                      double cMinX, double cMinZ, double cMaxX, double cMaxZ,
                                      double velX, double velZ, int roomLength) {
        boolean alongX = velX == 0 && velZ == 0
            ? cMaxX - cMinX >= cMaxZ - cMinZ
            : Math.abs(velX) >= Math.abs(velZ);
        double along = alongX ? Math.abs(px - mx) : Math.abs(pz - mz);
        double across = alongX ? pz : px;
        double acrossMin = (alongX ? cMinZ : cMinX) - NEAR_PLAYER_ACROSS_BLOCKS;
        double acrossMax = (alongX ? cMaxZ : cMaxX) + NEAR_PLAYER_ACROSS_BLOCKS;
        return along <= (ROOMS_NEAR_PLAYER + 1) * (double) roomLength
            && across >= acrossMin && across <= acrossMax
            && Math.abs(py - my) <= NEAR_PLAYER_VERTICAL_BLOCKS;
    }

    /** True on the tick a mob that has spent this long under carriages takes a crush hit. */
    public static boolean crushDue(long ticksUnder) {
        return ticksUnder > 0 && ticksUnder % CRUSH_INTERVAL_TICKS == 0;
    }

    /**
     * True when every chunk a {@code noCollision} check of this box would read is loaded, so the
     * check can't sync-load one on the server thread (a 5 s freeze in a 0.1547.0 /bug log). Covers
     * the block range {@code BlockCollisions} walks: one block past each edge.
     *
     * @param loaded asked once per chunk, by {@link ChunkPos#asLong} key
     */
    public static boolean collisionChunksLoaded(double minX, double minZ, double maxX, double maxZ,
                                                LongPredicate loaded) {
        int cMinX = (Mth.floor(minX - 1.0E-7) - 1) >> 4;
        int cMaxX = (Mth.floor(maxX + 1.0E-7) + 1) >> 4;
        int cMinZ = (Mth.floor(minZ - 1.0E-7) - 1) >> 4;
        int cMaxZ = (Mth.floor(maxZ + 1.0E-7) + 1) >> 4;
        for (int cx = cMinX; cx <= cMaxX; cx++) {
            for (int cz = cMinZ; cz <= cMaxZ; cz++) {
                if (!loaded.test(ChunkPos.asLong(cx, cz))) return false;
            }
        }
        return true;
    }
}
