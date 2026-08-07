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
 * @param origin   world position of the entry twin's minimum corner — the structure's origin
 * @param roomName the {@code portal_room} variant this pair rolled
 * @param roomSize the room's full box, shell included, as stamped (length is the free axis)
 */
public record PortalStructure(BlockPos origin, String roomName, Vec3i roomSize) {

    public PortalStructure {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(roomName, "roomName");
        Objects.requireNonNull(roomSize, "roomSize");
    }

    /** Length of this pair's room along the direction of travel. */
    public int roomLength() {
        return roomSize.getX();
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

    /** Total X span from the entry twin's origin to the far end of the exit twin. */
    public int spanX(CarriageDims dims) {
        return exitTwinOffsetX(dims) + dims.length();
    }

    /** The same structure relocated — same room, new position. */
    public PortalStructure movedTo(BlockPos newOrigin) {
        return new PortalStructure(newOrigin, roomName, roomSize);
    }
}
