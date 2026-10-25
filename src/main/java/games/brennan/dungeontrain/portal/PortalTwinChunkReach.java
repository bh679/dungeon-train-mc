package games.brennan.dungeontrain.portal;

/**
 * How many chunks around a player in twin space have to stay loaded for the room they are in — the
 * pure half of {@link PortalTwinChunkHold}.
 *
 * <p><b>Why a number at all.</b> Vanilla loads a player's view distance whatever their height, so a
 * player standing in the basement under the bedrock used to drive full generation of every overworld
 * column within view of them: land sealed off above their head that they could never see. The room
 * only needs the chunks its copies are stamped into and the ones the tiler is about to stamp into,
 * which is this figure.</p>
 *
 * <p><b>What it covers.</b> The tiler adds copies within {@link PortalRoomTiling#MAX_RADIUS} tiles of
 * the tile the player stands in, shrunk to what the block budget allows for the room's size, and an
 * extra corridor reaches {@link PortalExitSites#tileReach} tiles past the room it opens into. Copies
 * already standing further out are held separately, chunk by chunk, so that radius does not have to
 * reach them. A room that does not tile only needs its own span.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap, the same convention as
 * {@link PortalTwinLanes}.</p>
 */
public final class PortalTwinChunkReach {

    /**
     * The least a twin-space player ever holds: their own chunk and enough around it that the floor
     * under them is ticking. Also what a player who is in twin space but in no room gets (mid-crossing,
     * or fallen into open basement), so their own chunk never unloads under them.
     */
    public static final int MIN_RADIUS = 2;

    /**
     * Rings added past the room's reach. A region ticket of radius {@code r} holds chunks loaded out to
     * {@code r} but entity-ticking only out to {@code r - 2}, so this is what keeps the mobs in the
     * furthest copy moving rather than frozen.
     */
    public static final int ENTITY_TICK_MARGIN = 2;

    private PortalTwinChunkReach() {}

    /**
     * The radius, in tiles, the tiler fills around one occupant for a room allowed {@code budgetTiles}
     * copies: the {@link PortalRoomTiling#MAX_RADIUS} window, or the smallest square window that a
     * smaller budget can spill into (it fills nearest-first, so a part ring still reaches the next one).
     */
    public static int windowRadius(int budgetTiles) {
        if (budgetTiles <= 1) return 0;
        int side = (int) Math.ceil(Math.sqrt(budgetTiles));
        // An odd side is a full window of side/2; an even one is a full window one smaller plus a part
        // ring, which reaches side/2 as well.
        return Math.min(PortalRoomTiling.MAX_RADIUS, side / 2);
    }

    /**
     * How far from the player, in blocks, the room can need its chunks.
     *
     * @param tiles          whether the room repeats ({@link PortalRoomMode#tiles})
     * @param roomLength     the room's X size, one tile's pitch along X
     * @param roomWidth      the room's Z size, one tile's pitch along Z
     * @param budgetTiles    copies one occupant's window may hold
     * @param exitReachTiles how far an extra corridor reaches past its room, in tiles
     * @param spanX          the base structure's length, corridors included
     */
    public static int reachBlocks(boolean tiles, int roomLength, int roomWidth, int budgetTiles,
                                  int exitReachTiles, int spanX) {
        int pitch = Math.max(1, Math.max(roomLength, roomWidth));
        int base = Math.max(pitch, spanX);
        if (!tiles) return base;
        // +1 tile: the player can stand at the far edge of their own tile, away from the window centre.
        int tilesOut = windowRadius(budgetTiles) + 1 + Math.max(0, exitReachTiles);
        return Math.max(base, tilesOut * pitch);
    }

    /**
     * The region-ticket radius, in chunks, for a room reaching {@code reachBlocks}. Never less than
     * {@link #MIN_RADIUS}, and never more than {@code viewDistance}: the hold exists to load less than
     * vanilla did, so it can never ask for more.
     */
    public static int chunkRadius(int reachBlocks, int viewDistance) {
        int chunks = ceilDiv(Math.max(0, reachBlocks), 16) + ENTITY_TICK_MARGIN;
        int cap = Math.max(MIN_RADIUS, viewDistance);
        return Math.max(MIN_RADIUS, Math.min(cap, chunks));
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
