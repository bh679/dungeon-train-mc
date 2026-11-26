package games.brennan.dungeontrain.perks.aura;

/**
 * An aura's ladder: at each rank, how many effects it holds, each one's range in blocks (index 0 is the
 * first drawn), and how many level rounds have passed. A pure function of rank, so only the draw order
 * and the level picks need storing.
 */
@FunctionalInterface
public interface AuraLadder {

    Shape shape(int rank);

    /** {@code ranges[i]} is draw {@code i}'s range; {@code levelUps} counts the level rounds so far. */
    record Shape(int[] ranges, int levelUps) {
        public static final Shape NONE = new Shape(new int[0], 0);

        public int count() {
            return ranges.length;
        }

        public int range(int index) {
            return index >= 0 && index < ranges.length ? ranges[index] : 0;
        }
    }
}
