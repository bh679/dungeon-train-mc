package games.brennan.dungeontrain.portal;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which copies of a portal room are currently standing, for the two endless
 * {@link PortalRoomMode modes} — a grid of tiles around the base room, in tile coordinates where
 * {@code (0, 0)} is the room the corridors open into, {@code x} runs along the train and {@code z}
 * across it.
 *
 * <p>Nothing here knows about Minecraft. A tile is a pair of integers, distances are counted in
 * tiles, and the caller multiplies by the room's own size to get world blocks — so all of this
 * unit-tests without a NeoForge bootstrap, the same reason {@link PortalCarriageLayout} avoids
 * Minecraft types.</p>
 *
 * <h2>The corridor row holds tile 0 only</h2>
 * <p>{@link #isLegal} rejects {@code (i, 0)} for every {@code i != 0}. Along X the base room is
 * bookended by the two twin corridors and their plugs, so a tile there would land on top of a twin —
 * and the twin is what the player walks through to be swapped back onto the train. Moving the exit
 * twin out to the far edge instead is the thing {@link PortalStructure} warns against: its position
 * feeds {@code PortalFrames}, so growing the span would move the frame a player is standing in, under
 * them, every time a tile appended. Step one room sideways in Z and X is free in every direction.</p>
 *
 * <h2>Why a window rather than a growing region</h2>
 * <p>The window is centred on the player's tile and slides with them: walk one room along and a tile
 * appends at the leading edge while one retires at the trailing edge. That is what makes the space
 * genuinely endless rather than merely large, and it is what bounds the cost — the resident set never
 * exceeds {@link #budgetTiles}, however far anyone walks.</p>
 *
 * <h2>The budget is a block count, not a tile count</h2>
 * <p>{@link #MAX_RESIDENT_BLOCKS} caps how much of the world floor one pair may own. A small room
 * therefore gets the full {@link #MAX_RADIUS} window; a room already at the maximum on every axis
 * gets its immediate neighbours and no more. That is the scale-invariant reading of "five rooms out"
 * — a room big enough that five copies fill the view does not need a hundred of them.</p>
 *
 * @param tiles the resident set; always contains {@link Tile#BASE}, never an illegal tile
 */
public record PortalRoomTiling(Set<Tile> tiles) {

    /** A room copy's position in the grid. {@code (0, 0)} is the room the corridors open into. */
    public record Tile(int x, int z) {

        /** The room the corridors open into — always resident, never retired. */
        public static final Tile BASE = new Tile(0, 0);

        /** Squared distance to {@code other}, in tiles. Ordering only, so the square is never taken. */
        public int distanceSqTo(Tile other) {
            int dx = x - other.x;
            int dz = z - other.z;
            return dx * dx + dz * dz;
        }

        public Tile offset(int dx, int dz) {
            return new Tile(x + dx, z + dz);
        }
    }

    /** "Five rooms out", in each direction, as a Chebyshev radius on the tile grid. */
    public static final int MAX_RADIUS = 5;

    /**
     * Ceiling on how much room the resident tiles may occupy, in blocks.
     *
     * <p>{@link #MAX_RADIUS} times {@code MAX_LENGTH × MAX_HEIGHT × MAX_WIDTH} — the largest single
     * room {@code PortalCarriageBuilder} already stamps and erases, once for each ring the radius
     * allows. Expressed as a multiple of those rather than as a number so it cannot drift away from
     * either if the room bounds or the radius move.</p>
     *
     * <p>It works out generously for ordinary rooms and tightly for extreme ones, which is the
     * intent: a default 11×7×13 room clears the whole {@link #MAX_RADIUS} window, while a room
     * already at the maximum on every axis gets five copies. A room big enough that five of them
     * fill the view does not need a hundred.</p>
     */
    public static final int MAX_RESIDENT_BLOCKS =
        MAX_RADIUS * PortalRoomLayout.MAX_LENGTH * PortalRoomLayout.MAX_HEIGHT * PortalRoomLayout.MAX_WIDTH;

    /** Widest window the radius allows, as a tile count — the cap {@link #budgetTiles} clamps to. */
    private static final int MAX_WINDOW_TILES = (2 * MAX_RADIUS + 1) * (2 * MAX_RADIUS + 1);

    /** Nearest first, ties broken on coordinates so a tick's choice is deterministic. */
    private static Comparator<Tile> nearestTo(Tile centre) {
        return Comparator.comparingInt((Tile t) -> t.distanceSqTo(centre))
            .thenComparingInt(Tile::x)
            .thenComparingInt(Tile::z);
    }

    public PortalRoomTiling {
        Set<Tile> copy = new LinkedHashSet<>(tiles);
        copy.add(Tile.BASE);
        copy.removeIf(t -> !isLegal(t));
        tiles = Set.copyOf(copy);
    }

    /** Just the room the corridors open into — what every structure starts and ends as. */
    public static PortalRoomTiling base() {
        return new PortalRoomTiling(Set.of(Tile.BASE));
    }

    /**
     * How many tiles a room of {@code blocksPerTile} may hold at once: the block budget, clamped to at
     * least the base tile and at most the {@link #MAX_RADIUS} window.
     *
     * <p>Sized off the room's <b>whole box</b> even for {@link PortalRoomMode#ENDLESS_OPEN}, which
     * writes only floor and ceiling: a tile still has to be cleared to air before anything is stamped
     * in it, and that clear is the same size whichever mode asked for it.</p>
     */
    public static int budgetTiles(int blocksPerTile) {
        if (blocksPerTile <= 0) return MAX_WINDOW_TILES;
        return Math.max(1, Math.min(MAX_WINDOW_TILES, MAX_RESIDENT_BLOCKS / blocksPerTile));
    }

    /** False for the corridor row's non-zero tiles — see the class javadoc. */
    public static boolean isLegal(Tile tile) {
        return tile.z() != 0 || tile.x() == 0;
    }

    /** True when a copy of the room is standing at {@code tile}. */
    public boolean has(Tile tile) {
        return tiles.contains(tile);
    }

    public int size() {
        return tiles.size();
    }

    /** True when nothing but the base room is standing — the state a structure may be re-stamped in. */
    public boolean isBaseOnly() {
        return tiles.size() == 1;
    }

    /** This tiling plus {@code tile}. Ignored when the tile is illegal. */
    public PortalRoomTiling with(Tile tile) {
        if (!isLegal(tile) || tiles.contains(tile)) return this;
        Set<Tile> next = new LinkedHashSet<>(tiles);
        next.add(tile);
        return new PortalRoomTiling(next);
    }

    /** This tiling minus {@code tile}. The base tile is never removed. */
    public PortalRoomTiling without(Tile tile) {
        if (Tile.BASE.equals(tile) || !tiles.contains(tile)) return this;
        Set<Tile> next = new LinkedHashSet<>(tiles);
        next.remove(tile);
        return new PortalRoomTiling(next);
    }

    /**
     * The tile to stamp next: the nearest legal one to {@code centre} that is within {@code radius}
     * and not already standing, or {@code null} when the window is full or the budget is spent.
     *
     * <p>Nearest first so the player is always surrounded before anything distant is considered —
     * which matters because the fog is clamped to what has actually been built, so a gap next to them
     * is a gap they would see.</p>
     */
    public Tile nextToAdd(Tile centre, int radius, int budget) {
        return nextToAdd(centre, radius, budget, t -> true);
    }

    /**
     * As {@link #nextToAdd(Tile, int, int)}, but skipping candidates {@code buildable} rejects.
     *
     * <p>The filter is how "this copy cannot be built here" stays out of the geometry: the caller
     * knows about unloaded chunks and other pairs' structures, and a rejected tile is passed over for
     * the next-nearest rather than returned and refused, which would stall the fill on the same tile
     * every tick.</p>
     */
    public Tile nextToAdd(Tile centre, int radius, int budget, Predicate<Tile> buildable) {
        if (tiles.size() >= budget) return null;
        Comparator<Tile> order = nearestTo(centre);
        Tile best = null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Tile candidate = centre.offset(dx, dz);
                if (!isLegal(candidate) || tiles.contains(candidate)) continue;
                if (best != null && order.compare(candidate, best) >= 0) continue;
                if (!buildable.test(candidate)) continue;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The tile to retire next: the farthest standing one that has fallen outside {@code radius} of
     * {@code centre}, or {@code null} when every tile is still in range.
     *
     * <p>Farthest first, so a window sliding after a walking player sheds what they have left behind
     * rather than what is beside them.</p>
     */
    public Tile nextToRemove(Tile centre, int radius) {
        return nextToRemove(centre, radius, t -> true);
    }

    /**
     * As {@link #nextToRemove(Tile, int)}, but never offering up a tile {@code removable} rejects.
     *
     * <p>The caller uses this to hold on to a room somebody is standing in. Erasing a copy clears its
     * floor along with everything else, so retiring one out from under a second player who wandered
     * the other way would drop them onto the rock at the world floor.</p>
     */
    public Tile nextToRemove(Tile centre, int radius, Predicate<Tile> removable) {
        Comparator<Tile> order = nearestTo(centre);
        Tile worst = null;
        for (Tile tile : tiles) {
            if (Tile.BASE.equals(tile)) continue;
            if (Math.abs(tile.x() - centre.x()) <= radius && Math.abs(tile.z() - centre.z()) <= radius) {
                continue;
            }
            if (worst != null && order.compare(tile, worst) <= 0) continue;
            if (!removable.test(tile)) continue;
            worst = tile;
        }
        return worst;
    }

    /**
     * The farthest standing tile from {@code centre}, or {@code null} when only the base is left.
     *
     * <p>What draining uses. A structure with nobody in it sheds its tiles from the outside in until
     * {@link #isBaseOnly} — which is the state it has to reach before it may be re-stamped further
     * down the track, so that the erase is never more than the base structure's own box.</p>
     */
    public Tile farthestFrom(Tile centre) {
        return farthestFrom(centre, t -> true);
    }

    /** As {@link #farthestFrom(Tile)}, sparing anything {@code removable} rejects. */
    public Tile farthestFrom(Tile centre, Predicate<Tile> removable) {
        Comparator<Tile> order = nearestTo(centre);
        Tile worst = null;
        for (Tile tile : tiles) {
            if (Tile.BASE.equals(tile)) continue;
            if (worst != null && order.compare(tile, worst) <= 0) continue;
            if (!removable.test(tile)) continue;
            worst = tile;
        }
        return worst;
    }

    /**
     * How many rooms stand in an unbroken line from {@code from} heading {@code (dx, dz)}, counting
     * {@code from} itself — so 1 means "this room and then the edge".
     *
     * <p>This is how far the player can see that way, and therefore where the fog has to sit.</p>
     */
    public int runLength(Tile from, int dx, int dz) {
        if (!tiles.contains(from)) return 0;
        int run = 1;
        Tile cursor = from.offset(dx, dz);
        while (tiles.contains(cursor)) {
            run++;
            cursor = cursor.offset(dx, dz);
        }
        return run;
    }

    /**
     * True when the gap at the end of that run is one this tiling could legally fill.
     *
     * <p>The distinction is what keeps the fog off the corridor doors. Looking along X from the base
     * room, the next tile is illegal — what is actually there is a twin's sealed mouth, which the
     * player is meant to see and walk through. Everywhere else an unfilled legal gap is raw rock the
     * fog exists to hide.</p>
     */
    public boolean runEndsAtFillableGap(Tile from, int dx, int dz) {
        int run = runLength(from, dx, dz);
        if (run == 0) return false;
        return isLegal(from.offset(dx * run, dz * run));
    }

    public int minTileX() {
        return tiles.stream().mapToInt(Tile::x).min().orElse(0);
    }

    public int maxTileX() {
        return tiles.stream().mapToInt(Tile::x).max().orElse(0);
    }

    public int minTileZ() {
        return tiles.stream().mapToInt(Tile::z).min().orElse(0);
    }

    public int maxTileZ() {
        return tiles.stream().mapToInt(Tile::z).max().orElse(0);
    }
}
