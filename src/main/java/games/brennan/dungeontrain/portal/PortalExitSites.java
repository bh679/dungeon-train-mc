package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;

import java.util.List;

/**
 * Where an endless room's extra corridors go — the pure half of {@link PortalRoomExits}.
 *
 * <p>Nothing here knows about Minecraft. A site is a tile plus a role, and the caller turns that
 * into world blocks through {@link PortalStructure#shadowAt}, the same split
 * {@link PortalRoomTiling} makes for the same reason: this is the whole of the placement rule and it
 * unit-tests without a NeoForge bootstrap.</p>
 *
 * <h2>Where a site actually stands</h2>
 * <p>A site's tile is its <b>anchor</b>: the copy of the room the corridor opens into, exactly as
 * tile {@code (0, 0)} is the room the original pair opens into. The corridor itself stands beside
 * that tile, not in it — an {@link PortalCarriageRole#ENTRY} corridor on the low-X side, an
 * {@link PortalCarriageRole#EXIT} corridor on the high-X side — because that is what the base pair
 * does, and a site is nothing more than the base pair translated onto another tile.</p>
 *
 * <h2>Deterministic per site, on purpose</h2>
 * <p>Both readings are pure functions of the world seed, the pair, the room's name and the tile — so
 * walking back to a corridor finds the corridor you left rather than a fresh roll, and a copy that
 * retires as the window slides comes back where it was. This is the same rule
 * {@link PortalStructure#variantIndexFor} is written around, and for a sharper reason: a way out that
 * moved when you turned your back would be worse than no extra way out at all.</p>
 *
 * <h2>Why Random is denser than On at the same X</h2>
 * <p>{@link PortalRoomExits.Kind#ON} puts a set on a lattice — one anchor per {@code X²} tiles —
 * while {@link PortalRoomExits.Kind#RANDOM} rolls every tile at one in {@code X}. At {@code X = 8}
 * that is one anchor in sixty-four against one in eight. They are deliberately different readings of
 * the same number: the lattice is a promise about the longest walk between exits, the roll is a
 * frequency. An author who wants them to feel alike sets a different {@code X} for each.</p>
 */
public final class PortalExitSites {

    /**
     * One extra corridor: which copy of the room it opens into, and which end of the pair it is.
     *
     * @param tile the anchor — the room copy this corridor's mouth faces
     * @param role {@link PortalCarriageRole#ENTRY} for a corridor on the low-X side of that tile,
     *             {@link PortalCarriageRole#EXIT} for one on the high-X side
     */
    public record Site(Tile tile, PortalCarriageRole role) {

        /** Chebyshev distance from this site's anchor to {@code centre}, in tiles. */
        public int chebyshevTo(Tile centre) {
            return Math.max(Math.abs(tile.x() - centre.x()), Math.abs(tile.z() - centre.z()));
        }
    }

    /** Odds a {@link PortalRoomExits.Kind#RANDOM} site is measured against, as a percentage. */
    private static final int PERCENT = 100;

    /** Keeps the "is there a site here?" roll from correlating with the "which role?" roll. */
    private static final long SALT_PRESENT = 0x5DEECE66DL;
    private static final long SALT_ROLE = 0x2545F4914F6CDD1DL;

    /** And the moved-exit roll from correlating with either — it is asked of the pair, not a tile. */
    private static final long SALT_MOVE = 0x8EBC6AF09C88C6E3L;

    /** Odd constants, so the tile coordinates cannot collapse onto each other before mixing. */
    private static final long PRIME_X = 0x9E3779B97F4A7C15L;
    private static final long PRIME_Z = 0xC2B2AE3D27D4EB4FL;

    private PortalExitSites() {}

    /**
     * The seed a pair's sites are rolled from.
     *
     * <p>All three inputs earn their place. The <b>world seed</b> separates one world from the next.
     * The <b>pair key</b> is the entry corridor's carriage index, which is what stops every portal on
     * the train that rolled the same room from having its extra doors in identical places — the bug
     * {@link PortalStructure#variantIndexFor} documents finding the hard way. The <b>room name</b>
     * separates two different rooms that happen to sit at the same key.</p>
     */
    public static long seedFor(long worldSeed, int pairKey, String roomName) {
        long name = roomName == null ? 0L : roomName.hashCode();
        return mix(worldSeed ^ mix(pairKey * PRIME_X ^ name * PRIME_Z));
    }

    /**
     * The corridors {@code tile} owes, or an empty list when it owes none.
     *
     * <p>Never the base tile: that is where the pair's own two corridors already stand, and a copy
     * there would be stamped straight over them.</p>
     */
    public static List<Site> owedAt(PortalRoomExits exits, Tile tile, long seed) {
        if (exits == null || tile == null || !exits.lays()) return List.of();
        if (Tile.BASE.equals(tile)) return List.of();

        return switch (exits.kind()) {
            case OFF -> List.of();
            case ON -> onLattice(tile, exits.every())
                ? List.of(new Site(tile, PortalCarriageRole.ENTRY),
                          new Site(tile, PortalCarriageRole.EXIT))
                : List.of();
            case RANDOM -> rolledAt(tile, exits.every(), seed);
        };
    }

    /** True when {@code tile} is a lattice point — every {@code every} tiles on <b>both</b> axes. */
    private static boolean onLattice(Tile tile, int every) {
        return Math.floorMod(tile.x(), every) == 0 && Math.floorMod(tile.z(), every) == 0;
    }

    /**
     * The one-in-{@code every} roll, and the entry/exit roll behind it.
     *
     * <p>Two independent draws rather than one split into ranges: the role has to stay stable when an
     * author changes {@code every}, or nudging the spacing would silently swap every corridor in the
     * room from an exit to an entry.</p>
     */
    private static List<Site> rolledAt(Tile tile, int every, long seed) {
        if (Math.floorMod(roll(seed, tile, SALT_PRESENT), every) != 0) return List.of();
        boolean entry = Math.floorMod(roll(seed, tile, SALT_ROLE), PERCENT)
            < PortalRoomExits.ENTRY_SHARE;
        return List.of(new Site(tile, entry ? PortalCarriageRole.ENTRY : PortalCarriageRole.EXIT));
    }

    /** One draw for a tile, from a seed and a salt. */
    private static long roll(long seed, Tile tile, long salt) {
        return mix(seed ^ (tile.x() * PRIME_X) ^ (tile.z() * PRIME_Z) ^ salt);
    }

    /**
     * Whether this portal stands its <b>real</b> exit corridor somewhere other than directly opposite
     * the room a player arrives in.
     *
     * <p>One draw per <b>pair</b>, not per tile: it is a fact about this portal, decided once and
     * true for as long as it stands. A room that re-rolled it would move its own way out behind a
     * player's back, which reads as a fault rather than a mechanic — and the structure is re-stamped
     * from scratch every time the train drifts far enough, so "decided once" has to mean "a pure
     * function of the pair" rather than "remembered".</p>
     *
     * <p>Only under {@link PortalRoomExits.Kind#RANDOM}, and never at {@code 0}. Hiding the way
     * onward is only fair when there is something unpredictable to find; see
     * {@link PortalRoomExits#movesApply}.</p>
     */
    public static boolean movesExit(PortalRoomExits exits, long seed) {
        if (exits == null) return false;
        int chance = exits.effectiveMoveChance();
        if (chance <= PortalRoomExits.MOVE_NEVER) return false;
        if (chance >= PortalRoomExits.MOVE_ALWAYS) return true;
        return Math.floorMod(mix(seed ^ SALT_MOVE), PortalRoomExits.MOVE_ALWAYS) < chance;
    }

    /**
     * Which tile this pair's real exit corridor stands beside — {@link Tile#BASE} for the ordinary
     * arrangement, directly opposite the room a player arrives in.
     *
     * <p><b>It follows the rules that already exist.</b> The exit does not get a placement rule of
     * its own: it goes to the nearest tile this setting <i>already owes an exit corridor at</i>, by
     * the same lattice or one-in-X roll that scatters the copies. So there is one thing to reason
     * about rather than two, the density knob tunes both together, and a room tuned to scatter
     * corridors thickly finds its exit sooner without anybody wiring that up.</p>
     *
     * <p>Nearest first, ties broken on coordinates, so the answer is deterministic rather than
     * dependent on iteration order — the exit is somewhere a player has to look for, not somewhere
     * that changes.</p>
     *
     * <p><b>Bounded by {@code radius}</b>, and {@link Tile#BASE} when nothing is owed inside it. Past
     * the tiling window the exit's room is not built at all, so its mouth would open onto the empty
     * basement — a way out that drops you out of the world is worse than one that is simply where it
     * has always been.</p>
     */
    public static Tile relocatedExitTile(PortalRoomExits exits, long seed, int radius) {
        if (exits == null || !movesExit(exits, seed)) return Tile.BASE;
        for (int ring = 1; ring <= radius; ring++) {
            Tile found = firstExitOnRing(exits, seed, ring);
            if (found != null) return found;
        }
        return Tile.BASE;
    }

    /**
     * The first tile on the Chebyshev ring at {@code ring} that owes an exit corridor, scanned in a
     * fixed coordinate order so two runs agree.
     *
     * <p>Ring by ring rather than a sorted sweep of the whole square: it stops at the first hit, and
     * for a densely scattered room that is almost always ring one.</p>
     */
    private static Tile firstExitOnRing(PortalRoomExits exits, long seed, int ring) {
        for (int x = -ring; x <= ring; x++) {
            for (int z = -ring; z <= ring; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) != ring) continue;
                Tile tile = new Tile(x, z);
                if (owedAt(exits, tile, seed).contains(new Site(tile, PortalCarriageRole.EXIT))) {
                    return tile;
                }
            }
        }
        return null;
    }

    /**
     * SplitMix64's finaliser.
     *
     * <p>Spelled out rather than taken from {@code java.util.Random}, whose contract is about
     * sequences rather than about a stable hash of a coordinate, and rather than
     * {@code Objects.hash}, whose low bits are too regular to survive the {@code floorMod} below —
     * a weak mix there would put the lattice back into a setting that is meant to scatter.</p>
     */
    private static long mix(long z) {
        z += PRIME_X;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * How many tiles a corridor's blocks reach past its anchor, in tiles.
     *
     * <p>A corridor is longer than a room ({@link PortalCorridorSize}: 13 against 11 at the default
     * dims) and its plug runs further still, so a copy's blocks land in the tiles either side of the
     * one it opens into. Two things need to know how far: {@code PortalExitCopyTiler}, which must not
     * retire a copy while any tile it stands in is still standing — that would leave a
     * corridor-shaped pit with no floor in a room the player can walk back into — and any caller
     * asking which tiles a site could possibly touch.</p>
     *
     * <p>Rounded up and deliberately generous: it is a bound used to hold a copy for longer, and
     * being one tile too careful costs a few blocks of residency, while being one tile short costs a
     * hole in the floor.</p>
     */
    public static int tileReach(int corridorLength, int roomLength, int plugDepth) {
        if (roomLength <= 0) return 1;
        return Math.max(1, ceilDiv(corridorLength + Math.max(0, plugDepth), roomLength));
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
