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
 * <h2>The corridor row tiles like any other</h2>
 * <p>Copies on row {@code z == 0} land exactly where the two twin corridors go. The corridors are
 * stamped <b>after</b> the base room and only ever once; every copy placed later is masked off the
 * volume they own, so it is built around them rather than over them. See
 * {@code PortalCorridorMask}.</p>
 *
 * <p>What the tiling must never do is move a twin. The exit twin's position feeds
 * {@code PortalFrames}, so growing {@link PortalStructure#spanX} per copy would shift the frame a
 * player is standing in, under them. It does not: {@code spanX} is the corridor span and is blind to
 * the tiling, so the corridors stay put and the endless space is built through them.</p>
 *
 * <h2>Why a window rather than a growing region</h2>
 * <p>The window is centred on the player's tile and slides with them: walk one room along and a tile
 * appends at the leading edge while one retires at the trailing edge. That is what makes the space
 * genuinely endless rather than merely large, and it is what bounds the cost — the resident set never
 * exceeds {@link #budgetTiles}, however far anyone walks.</p>
 *
 * <h2>One window per occupant, not one per room</h2>
 * <p>Every method that takes a centre takes a <b>set</b> of them — the tiles the players inside are
 * standing in. The window is their union: a tile is retired only once it has fallen outside the
 * radius of <i>every</i> one of them, and the next tile to build is the one nearest to <i>any</i> of
 * them.</p>
 *
 * <p>It used to follow the first occupant alone, with the others protected only by the caller sparing
 * the single tile each was standing in. On a server that erased the floor all round a second player
 * the moment the first walked the other way, leaving them on an island one room across with the world
 * floor below the next step. Following one centre is the same thing as following the host, and the
 * cost of the room is paid per occupant anyway — see {@link #budgetTiles(int, int)}.</p>
 *
 * <h2>The budget is a block count, not a tile count</h2>
 * <p>{@link #MAX_RESIDENT_BLOCKS} caps how much of the world floor one pair may own. A small room
 * therefore gets the full {@link #MAX_RADIUS} window; a room already at the maximum on every axis
 * gets its immediate neighbours and no more. That is the scale-invariant reading of "five rooms out"
 * — a room big enough that five copies fill the view does not need a hundred of them.</p>
 *
 * @param tiles the resident set; always contains {@link Tile#BASE}
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
     * How far the window reaches for somebody who is near the portal carriage but not yet in the
     * room.
     *
     * <p>Just the ring around the base room — eight copies, against a hundred and twenty at
     * {@link #MAX_RADIUS}. The point of building anything before
     * a player walks in is only that the room should already be there when they arrive rather than
     * filling in behind their back, and one ring does that. Going straight to the full window would
     * mean somebody who rides past a portal carriage and never goes through it has paid for a hundred
     * copies of a room nobody looked at.</p>
     */
    public static final int APPROACH_RADIUS = 1;

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

    /**
     * How many occupants of one room the block budget is multiplied by before it stops growing.
     *
     * <p>Somewhere the per-occupant budget has to stop, or a full server standing in one endless room
     * would own the world floor. Four is chosen as the size of a group that plays together — past
     * that, players are close enough to share a window in practice, and the retire rule protects
     * everybody regardless of what the budget says.</p>
     */
    public static final int MAX_CENTRES = 4;

    /**
     * How far {@code tile} is from the closest of {@code centres}, squared. Ordering only, so the
     * square is never taken.
     *
     * <p>{@link Integer#MAX_VALUE} for an empty set, which no caller passes — the tick loop drains
     * instead of tiling when nobody is inside.</p>
     */
    private static int distanceSqToNearest(Tile tile, Set<Tile> centres) {
        int best = Integer.MAX_VALUE;
        for (Tile centre : centres) {
            best = Math.min(best, tile.distanceSqTo(centre));
        }
        return best;
    }

    /**
     * Nearest to whichever occupant is closest, ties broken on coordinates so a tick's choice is
     * deterministic.
     *
     * <p>Measuring against the nearest centre rather than against a chosen one is what balances the
     * fill between two players walking apart: a gap one tile from the second of them outranks a tile
     * four out in the first's window, so both are surrounded before either is extended.</p>
     */
    private static Comparator<Tile> nearestToAny(Set<Tile> centres) {
        return Comparator.comparingInt((Tile t) -> distanceSqToNearest(t, centres))
            .thenComparingInt(Tile::x)
            .thenComparingInt(Tile::z);
    }

    /** True when {@code tile} is inside the Chebyshev {@code radius} of at least one centre. */
    private static boolean withinAny(Tile tile, Set<Tile> centres, int radius) {
        for (Tile centre : centres) {
            if (Math.abs(tile.x() - centre.x()) <= radius && Math.abs(tile.z() - centre.z()) <= radius) {
                return true;
            }
        }
        return false;
    }

    public PortalRoomTiling {
        Set<Tile> copy = new LinkedHashSet<>(tiles);
        copy.add(Tile.BASE);
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
        return budgetTiles(blocksPerTile, 1);
    }

    /**
     * As {@link #budgetTiles(int)}, for a room with {@code occupants} players standing in distinct
     * tiles — the budget one of them would get, that many times over, up to {@link #MAX_CENTRES}.
     *
     * <p>A shared budget is not merely mean, it is a wall. Two players walking apart both fill their
     * side of a single budget; it is then spent, and nothing is retirable because every resident tile
     * is close to one of them — so neither window can ever grow again, in a room whose whole promise
     * is that it does not end. Scaling with the occupants keeps each of them a window of the size
     * they would have had alone.</p>
     *
     * <p>The cost is real and it is bounded on both sides: it is only paid while those players are
     * actually in the room, and it buys no more stamping per tick — {@code PortalRoomTiler} still
     * lays one tile a tick however many people are watching.</p>
     */
    public static int budgetTiles(int blocksPerTile, int occupants) {
        int centres = Math.max(1, Math.min(MAX_CENTRES, occupants));
        if (blocksPerTile <= 0) return MAX_WINDOW_TILES * centres;
        return Math.max(1, Math.min(MAX_WINDOW_TILES * centres,
            (MAX_RESIDENT_BLOCKS / blocksPerTile) * centres));
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

    /** This tiling plus {@code tile}. */
    public PortalRoomTiling with(Tile tile) {
        if (tiles.contains(tile)) return this;
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
        return nextToAdd(Set.of(centre), radius, budget, t -> true);
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
        return nextToAdd(Set.of(centre), radius, budget, buildable);
    }

    /**
     * As {@link #nextToAdd(Tile, int, int, Predicate)} for a room with several people in it: the
     * window is the union of the squares around {@code centres}, and the tile chosen is the nearest
     * unbuilt one to whichever of them is closest to it.
     *
     * <p>Which is what keeps a second player supplied. Following one centre only, the room simply
     * never extended past the first player's radius — everybody else walked to a wall, or in
     * {@link PortalRoomMode#ENDLESS_OPEN}, which closes no faces, off the edge of the floor.</p>
     *
     * <p>The candidate scan is per-centre and deduplicated by the resident check rather than by a
     * set, so overlapping windows cost a few repeated comparisons and no allocation. With the centres
     * capped at {@link #MAX_CENTRES} for the budget, and in practice one or two, that is cheaper than
     * building the union.</p>
     */
    public Tile nextToAdd(Set<Tile> centres, int radius, int budget, Predicate<Tile> buildable) {
        if (tiles.size() >= budget || centres.isEmpty()) return null;
        Comparator<Tile> order = nearestToAny(centres);
        Tile best = null;
        for (Tile centre : centres) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Tile candidate = centre.offset(dx, dz);
                    if (tiles.contains(candidate)) continue;
                    if (best != null && order.compare(candidate, best) >= 0) continue;
                    if (!buildable.test(candidate)) continue;
                    best = candidate;
                }
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
        return nextToRemove(Set.of(centre), radius, t -> true);
    }

    /**
     * As {@link #nextToRemove(Tile, int)}, but never offering up a tile {@code removable} rejects.
     *
     * <p>The caller uses this to hold on to a room somebody is standing in. Erasing a copy clears its
     * floor along with everything else, so retiring one out from under a second player who wandered
     * the other way would drop them onto the rock at the world floor.</p>
     */
    public Tile nextToRemove(Tile centre, int radius, Predicate<Tile> removable) {
        return nextToRemove(Set.of(centre), radius, removable);
    }

    /**
     * As {@link #nextToRemove(Tile, int, Predicate)} for a room with several people in it: a tile is
     * offered up only once it has fallen outside {@code radius} of <b>every</b> centre.
     *
     * <p>This is the safety rule of the whole class, and it deliberately does not consult the budget.
     * Erasing a copy takes its floor with it, so a tile retired next to somebody is a step they can
     * take into the world floor — and it does not have to be the tile they occupy, which was the only
     * one the caller could spare while the window followed a single player. Everything within a
     * radius of everybody stays, whatever it costs; growth is what the budget limits.</p>
     */
    public Tile nextToRemove(Set<Tile> centres, int radius, Predicate<Tile> removable) {
        if (centres.isEmpty()) return null;
        Comparator<Tile> order = nearestToAny(centres);
        Tile worst = null;
        for (Tile tile : tiles) {
            if (Tile.BASE.equals(tile)) continue;
            if (withinAny(tile, centres, radius)) continue;
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
        Comparator<Tile> order = nearestToAny(Set.of(centre));
        Tile worst = null;
        for (Tile tile : tiles) {
            if (Tile.BASE.equals(tile)) continue;
            if (worst != null && order.compare(tile, worst) <= 0) continue;
            if (!removable.test(tile)) continue;
            worst = tile;
        }
        return worst;
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
