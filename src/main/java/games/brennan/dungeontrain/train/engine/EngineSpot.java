package games.brennan.dungeontrain.train.engine;

/**
 * Where a Drifting Engine Block goes in a box that has none: a free floor cell, the same one every time for the
 * same blocks.
 *
 * <p>A cell qualifies when it and the cell above it are empty and the cell below is solid — the engine
 * stands on the floor with headroom, so it never blocks a doorway's top or floats. The two end planes
 * ({@code x = 0} and {@code x = l-1}, where carriage doors are) and the outer walls are never used.
 * Cells against a side wall are preferred, nearest the middle of the length first, so the engine sits
 * out of the walkway; anything else free comes after.</p>
 */
public final class EngineSpot {

    /** Whether the box holds a block at {@code (x, y, z)}. */
    @FunctionalInterface
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    private EngineSpot() {}

    /** The spot as {@code {x, y, z}} box offsets, or null when no cell qualifies. */
    public static int[] find(int l, int h, int w, Solid solid) {
        if (l < 3 || h < 3 || w < 3) return null;
        int[] best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < l - 1; x++) {
                for (int z = 1; z < w - 1; z++) {
                    if (solid.at(x, y, z) || solid.at(x, y + 1, z) || !solid.at(x, y - 1, z)) continue;
                    boolean againstWall = z == 1 || z == w - 2;
                    int score = (againstWall ? 0 : 1_000_000) + y * 10_000
                            + Math.abs(2 * x - (l - 1)) * 10 + (z == 1 ? 0 : 1);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    /** Face the engine's firebox into the room: away from the wall it stands against. */
    public static String facing(int[] spot, int w) {
        return spot[2] <= (w - 1) / 2 ? "south" : "north";
    }
}
