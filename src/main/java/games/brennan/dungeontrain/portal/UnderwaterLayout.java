package games.brennan.dungeontrain.portal;

/**
 * Where the sea sits in an underwater chunk dimension room: how high its floor stands off the box's
 * bottom, and how far its surface sits under the roof.
 *
 * <p>Rolled once per room, from the pair and its re-roll count, so a room keeps its sea for the
 * life of the world and Test the Carriage's reseed gives a fresh one. The floor roll places the
 * middle column's seabed; every other column follows the ocean floor the sample actually has.</p>
 *
 * @param floorRow room-local row a player stands on at the middle of the seabed, 0 to 8
 * @param waterGap blocks of air between the water's top row and the roof: 0 (flooded to the roof)
 *                 half the time, otherwise 2 to 8
 */
public record UnderwaterLayout(int floorRow, int waterGap) {

    public static final int MIN_FLOOR_ROW = 0;
    public static final int MAX_FLOOR_ROW = 8;
    public static final int MIN_GAP = 2;
    public static final int MAX_GAP = 8;

    private static final int FLOOR_SALT = 0x5EA_F100;
    private static final int FLOODED_SALT = 0x5EA_F1D;
    private static final int GAP_SALT = 0x5EA_6A9;

    /** This pair's sea at re-roll {@code roll}. */
    public static UnderwaterLayout roll(long worldSeed, int pairKey, int roll) {
        int floor = MIN_FLOOR_ROW + pick(worldSeed, pairKey, roll, FLOOR_SALT, MAX_FLOOR_ROW - MIN_FLOOR_ROW + 1);
        boolean flooded = pick(worldSeed, pairKey, roll, FLOODED_SALT, 2) == 0;
        int gap = flooded ? 0 : MIN_GAP + pick(worldSeed, pairKey, roll, GAP_SALT, MAX_GAP - MIN_GAP + 1);
        return new UnderwaterLayout(floor, gap);
    }

    /** The highest room-local row that holds water in a box {@code height} tall. */
    public int topWaterRow(int height) {
        return height - 1 - waterGap;
    }

    /** A uniform pick in {@code [0, bound)}, splitmix-hashed from the pair, its roll and a salt. */
    private static int pick(long seed, int key, int roll, int salt, int bound) {
        long h = seed * 0x9E3779B97F4A7C15L + (salt + roll * 977L) * 0xD1B54A32D192ED03L;
        h ^= (long) key * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (int) (((h >>> 11) * 0x1.0p-53) * bound);
    }
}
