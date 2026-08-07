package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.Objects;

/**
 * One portal pair's twin structure, as actually built:
 *
 * <pre>
 *   [plug] [entry twin] [room] [exit twin] [plug]
 * </pre>
 *
 * <p><b>Why this is a record rather than a {@code BlockPos}.</b> The exit twin's position is one
 * corridor plus one room along from the entry twin — and the room's length comes from whichever
 * {@code portal_room} template this pair rolled, so it is not a constant. Four separate places used
 * to re-derive that offset from {@code dims.length() + POCKET_LENGTH}: where the exit twin is
 * stamped, how far {@code eraseTwin} reaches, the occupancy box, and the origin the EXIT role's
 * {@code PortalFrames} maps into. If any of them disagreed with the others, a player walking back
 * out of the room would be mapped onto a corridor that is not there. So the structure carries what
 * it was built from, and everything reads it from here.</p>
 *
 * <p><b>The room identity is stable, the origin is not.</b> A pair's room name is a pure function of
 * its key and the world seed, so it never changes across the life of a pair. The origin moves
 * whenever the train drifts far enough to need a re-stamp — and because the old record is kept until
 * the new one replaces it, {@code eraseTwin} always clears exactly the box that was written, even if
 * the author saved a longer room in between.</p>
 *
 * <p><b>{@link #mode} is fixed for the life of the structure.</b> It is read once when the pair is
 * planned rather than looked up per tick, so an author saving a different mode while somebody is
 * standing in the room cannot change the walls around them mid-visit. The next pair to be planned
 * picks it up.</p>
 *
 * <p><b>{@link #tiling} grows and shrinks; {@link #spanX} never does.</b> The endless modes append
 * copies of the room around the base one, but only sideways in Z and — off the corridor row — along
 * X, never in line with the twins. So {@link #exitOrigin} and {@link #spanX} stay exactly what the
 * room's own length makes them, which is what keeps the EXIT frame under a player's feet still. The
 * tiled copies widen {@link #tiledMinX}..{@link #tiledMaxZ} instead, and the occupancy box and the
 * erase sweep read those.</p>
 *
 * @param origin   world position of the entry twin's minimum corner — the structure's origin
 * @param roomName the {@code portal_room} variant this pair rolled
 * @param roomSize the room's full box, shell included, as stamped (length is the free axis)
 * @param mode     what this room does at its walls, resolved when the pair was planned
 * @param tiling   which copies of the room are currently standing
 */
public record PortalStructure(BlockPos origin, String roomName, Vec3i roomSize,
                              PortalRoomMode mode, PortalRoomTiling tiling) {

    public PortalStructure {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(roomName, "roomName");
        Objects.requireNonNull(roomSize, "roomSize");
        if (mode == null) mode = PortalRoomMode.DEFAULT;
        if (tiling == null) tiling = PortalRoomTiling.base();
    }

    /** Back-compat 3-arg form — a default-mode structure with only its base room standing. */
    public PortalStructure(BlockPos origin, String roomName, Vec3i roomSize) {
        this(origin, roomName, roomSize, PortalRoomMode.DEFAULT, PortalRoomTiling.base());
    }

    /** Length of this pair's room along the direction of travel. */
    public int roomLength() {
        return roomSize.getX();
    }

    /** Width of this pair's room across the direction of travel — the tile stride in Z. */
    public int roomWidth() {
        return roomSize.getZ();
    }

    /** X offset from the entry twin's origin to the exit twin's — one corridor plus one room. */
    public int exitTwinOffsetX(CarriageDims dims) {
        return dims.length() + roomLength();
    }

    /** Minimum corner of the exit twin corridor. */
    public BlockPos exitOrigin(CarriageDims dims) {
        return origin.offset(exitTwinOffsetX(dims), 0, 0);
    }

    /** Minimum corner of the room box, centred on the corridor's doorway line. */
    public BlockPos roomOrigin(CarriageDims dims, PortalCarriageLayout layout) {
        // Centred on this pair's OWN room width, not the world minimum — a wider authored room
        // still has to line its interior centre up with the corridor's doorway.
        return PortalRoomLayout.roomOrigin(origin, dims, layout, roomSize.getZ());
    }

    /**
     * Total X span from the entry twin's origin to the far end of the exit twin.
     *
     * <p>Deliberately blind to {@link #tiling}: this is the corridor span, and the whole point of
     * tiling off the corridor row is that appending a copy of the room does not move a twin.</p>
     */
    public int spanX(CarriageDims dims) {
        return exitTwinOffsetX(dims) + dims.length();
    }

    /** Minimum corner of the room copy standing at {@code tile}. */
    public BlockPos tileOrigin(CarriageDims dims, PortalCarriageLayout layout,
                               PortalRoomTiling.Tile tile) {
        return roomOrigin(dims, layout)
            .offset(tile.x() * roomLength(), 0, tile.z() * roomWidth());
    }

    /** How many blocks one copy of this room occupies — what the tiling budget is measured in. */
    public int blocksPerTile() {
        return roomSize.getX() * roomSize.getY() * roomSize.getZ();
    }

    /** How many copies of this room may stand at once. */
    public int tileBudget() {
        return PortalRoomTiling.budgetTiles(blocksPerTile());
    }

    /** Lowest world X any standing copy of the room reaches. */
    public int tiledMinX(CarriageDims dims, PortalCarriageLayout layout) {
        return roomOrigin(dims, layout).getX() + tiling.minTileX() * roomLength();
    }

    /** Highest world X (inclusive) any standing copy of the room reaches. */
    public int tiledMaxX(CarriageDims dims, PortalCarriageLayout layout) {
        return roomOrigin(dims, layout).getX() + (tiling.maxTileX() + 1) * roomLength() - 1;
    }

    /** Lowest world Z any standing copy of the room reaches. */
    public int tiledMinZ(CarriageDims dims, PortalCarriageLayout layout) {
        return roomOrigin(dims, layout).getZ() + tiling.minTileZ() * roomWidth();
    }

    /** Highest world Z (inclusive) any standing copy of the room reaches. */
    public int tiledMaxZ(CarriageDims dims, PortalCarriageLayout layout) {
        return roomOrigin(dims, layout).getZ() + (tiling.maxTileZ() + 1) * roomWidth() - 1;
    }

    /** Which tile a world position falls in — the grid cell, whether or not a copy stands there. */
    public PortalRoomTiling.Tile tileAt(CarriageDims dims, PortalCarriageLayout layout,
                                        double worldX, double worldZ) {
        BlockPos room = roomOrigin(dims, layout);
        return new PortalRoomTiling.Tile(
            Math.floorDiv((int) Math.floor(worldX) - room.getX(), roomLength()),
            Math.floorDiv((int) Math.floor(worldZ) - room.getZ(), roomWidth()));
    }

    /** The same structure relocated — same room, same mode, back to just its base tile. */
    public PortalStructure movedTo(BlockPos newOrigin) {
        return new PortalStructure(newOrigin, roomName, roomSize, mode, PortalRoomTiling.base());
    }

    /** The same structure with a different set of room copies standing. */
    public PortalStructure withTiling(PortalRoomTiling newTiling) {
        return new PortalStructure(origin, roomName, roomSize, mode, newTiling);
    }
}
