package games.brennan.dungeontrain.train.engine;

import java.util.List;

/**
 * Where a Drifting Engine Block goes in a box that has none, the same place every time for the same
 * blocks.
 *
 * <p><b>First choice: take the place of a stone-type block</b> (stone, bricks, cobblestone, sandstone,
 * tuff and their kin — see {@link #isStoneLike}) that faces into the room, so the engine is built into
 * the carriage rather than set down in it. Side-wall blocks with open space in front of them come
 * first, at floor level, nearest the middle of the length, the firebox facing into the room; then any
 * other stone-type block with open space beside it (the floor, a pillar). The two end planes
 * ({@code x = 0} and {@code x = l-1}, where carriage doors are) and the roof are never used.</p>
 *
 * <p><b>Otherwise, a free floor cell</b>: empty, empty above, solid below, out of the end planes and
 * the outer walls — against a side wall first, nearest the middle of the length.</p>
 */
public final class EngineSpot {

    /** A test on a box cell. */
    @FunctionalInterface
    public interface Cell {
        boolean at(int x, int y, int z);
    }

    /** Where the engine goes, whether it takes the place of the block there, and which way it faces. */
    public record Spot(int x, int y, int z, boolean replaces, String facing) {
        public int[] pos() {
            return new int[]{x, y, z};
        }
    }

    /** Never stone-type, whatever their name says: not full blocks, or not plain masonry. */
    private static final List<String> NOT_STONE_SUFFIXES = List.of(
            "_stairs", "_slab", "_wall", "_button", "_pressure_plate", "_fence", "_fence_gate", "_ore");
    private static final List<String> NOT_STONE_WORDS = List.of(
            "redstone", "glowstone", "lodestone", "stonecutter", "grindstone", "sign", "infested", "stage_",
            "pointed_");
    private static final List<String> STONE_WORDS = List.of(
            "stone", "brick", "cobble", "tuff", "deepslate", "andesite", "diorite", "granite", "basalt", "calcite");

    private EngineSpot() {}

    /**
     * Whether the block named {@code id} (e.g. {@code minecraft:stone_bricks}) counts as stone-type:
     * plain masonry the engine may take the place of. Callers also rule out anything that is not a
     * full block or that holds a block entity.
     */
    public static boolean isStoneLike(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        for (String s : NOT_STONE_SUFFIXES) if (path.endsWith(s)) return false;
        for (String s : NOT_STONE_WORDS) if (path.contains(s)) return false;
        for (String s : STONE_WORDS) if (path.contains(s)) return true;
        return false;
    }

    /** The spot, or null when the box has neither a stone-type block facing the room nor a free floor cell. */
    public static Spot find(int l, int h, int w, Cell solid, Cell stoneLike) {
        if (l < 3 || h < 3 || w < 3) return null;
        Spot stone = findStone(l, h, w, solid, stoneLike);
        return stone != null ? stone : findFloor(l, h, w, solid);
    }

    private static Spot findStone(int l, int h, int w, Cell solid, Cell stoneLike) {
        Spot best = null;
        long bestScore = Long.MAX_VALUE;
        for (int x = 1; x < l - 1; x++) {
            for (int y = 0; y < h - 1; y++) {
                for (int z = 0; z < w; z++) {
                    if (!stoneLike.at(x, y, z)) continue;
                    boolean wall = (z == 0 && !solid.at(x, y, 1)) || (z == w - 1 && !solid.at(x, y, w - 2));
                    if (!wall && !opensOntoRoom(l, h, w, x, y, z, solid)) continue;
                    long score = (wall ? 0 : 1_000_000_000L) + Math.abs(y - 1) * 10_000L
                            + Math.abs(2 * x - (l - 1)) * 10L + (z <= (w - 1) / 2 ? 0 : 1);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Spot(x, y, z, true, facing(z, w));
                    }
                }
            }
        }
        return best;
    }

    /** A non-wall stone-type block with an empty cell beside it inside the room (not past the box). */
    private static boolean opensOntoRoom(int l, int h, int w, int x, int y, int z, Cell solid) {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (nx < 1 || nx > l - 2 || ny < 1 || ny > h - 2 || nz < 1 || nz > w - 2) continue;
            if (!solid.at(nx, ny, nz)) return true;
        }
        return false;
    }

    private static Spot findFloor(int l, int h, int w, Cell solid) {
        Spot best = null;
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
                        best = new Spot(x, y, z, false, facing(z, w));
                    }
                }
            }
        }
        return best;
    }

    /** Face the firebox into the room: away from the side it is nearer. */
    static String facing(int z, int w) {
        return z <= (w - 1) / 2 ? "south" : "north";
    }
}
