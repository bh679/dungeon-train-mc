package games.brennan.dungeontrain.train.engine;

import java.util.List;

/**
 * Where a Drifting Engine Block goes in a box that has none, the same place every time for the same
 * blocks.
 *
 * <p><b>First choice: take the place of a block facing into the room</b>, so the engine is built into
 * the carriage rather than set down in it. Blocks come in two grades ({@link #grade}): masonry and
 * metal first — stone, bricks, cobblestone, sandstone, tuff, deepslate and their kin, solid copper,
 * quartz and obsidian — and only when a carriage has none of those facing the room, wood and earth:
 * planks, logs, stems, dirt, mud, moss, clay, terracotta. Within a grade, side-wall blocks with open
 * space in front of them come first, at floor level, nearest the middle of the length, the firebox
 * facing into the room; then any other such block with open space beside it (the floor, a pillar).
 * The two end planes ({@code x = 0} and {@code x = l-1}, where carriage doors are) and the roof are
 * never used.</p>
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

    /** The {@link #grade} of a box cell's block: {@link #NONE}, {@link #PRIMARY} or {@link #SECONDARY}. */
    @FunctionalInterface
    public interface Grade {
        int at(int x, int y, int z);
    }

    /** Not a block the engine may take the place of. */
    public static final int NONE = 0;
    /** Masonry and metal: the engine's first choice. */
    public static final int PRIMARY = 1;
    /** Wood and earth: only when the carriage has no {@link #PRIMARY} block facing the room. */
    public static final int SECONDARY = 2;

    /** Where the engine goes, whether it takes the place of the block there, and which way it faces. */
    public record Spot(int x, int y, int z, boolean replaces, String facing) {
        public int[] pos() {
            return new int[]{x, y, z};
        }
    }

    /** Never replaceable, whatever their name says: not full blocks, or not plain building material. */
    private static final List<String> NEVER_SUFFIXES = List.of(
            "_stairs", "_slab", "_wall", "_button", "_pressure_plate", "_fence", "_fence_gate", "_ore", "_path");
    private static final List<String> NEVER_WORDS = List.of(
            "redstone", "glowstone", "lodestone", "stonecutter", "grindstone", "sign", "infested", "stage_",
            "pointed_", "door", "bulb", "glass");
    private static final List<String> PRIMARY_WORDS = List.of(
            "stone", "brick", "cobble", "tuff", "deepslate", "andesite", "diorite", "granite", "basalt", "calcite",
            "copper", "quartz", "obsidian");
    private static final List<String> SECONDARY_WORDS = List.of(
            "planks", "bamboo_block", "dirt", "mud", "moss_block", "clay", "terracotta");
    private static final List<String> SECONDARY_SUFFIXES = List.of("_log", "_wood", "_stem", "_hyphae");

    private EngineSpot() {}

    /**
     * How readily the engine takes the place of the block named {@code id} (e.g.
     * {@code minecraft:stone_bricks}): {@link #PRIMARY}, {@link #SECONDARY} or {@link #NONE}. Callers also
     * rule out anything that is not a full block or that holds a block entity.
     */
    public static int grade(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        for (String s : NEVER_SUFFIXES) if (path.endsWith(s)) return NONE;
        for (String s : NEVER_WORDS) if (path.contains(s)) return NONE;
        for (String s : PRIMARY_WORDS) if (path.contains(s)) return PRIMARY;
        for (String s : SECONDARY_WORDS) if (path.contains(s)) return SECONDARY;
        for (String s : SECONDARY_SUFFIXES) if (path.endsWith(s)) return SECONDARY;
        return NONE;
    }

    /** The spot, or null when the box has neither a replaceable block facing the room nor a free floor cell. */
    public static Spot find(int l, int h, int w, Cell solid, Grade grade) {
        if (l < 3 || h < 3 || w < 3) return null;
        for (int tier : new int[]{PRIMARY, SECONDARY}) {
            Spot spot = findReplace(l, h, w, solid, grade, tier);
            if (spot != null) return spot;
        }
        return findFloor(l, h, w, solid);
    }

    private static Spot findReplace(int l, int h, int w, Cell solid, Grade grade, int tier) {
        Spot best = null;
        long bestScore = Long.MAX_VALUE;
        for (int x = 1; x < l - 1; x++) {
            for (int y = 0; y < h - 1; y++) {
                for (int z = 0; z < w; z++) {
                    if (grade.at(x, y, z) != tier) continue;
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

    /** A non-wall block with an empty cell beside it inside the room (not past the box). */
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
