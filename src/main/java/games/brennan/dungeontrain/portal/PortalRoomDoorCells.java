package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a pocket room's two corridor doors stand, given nothing but the room's own box.
 *
 * <p><b>Why this is a class of its own.</b> A room is authored in an editor plot with neither
 * corridor present — {@link PortalCarriageBuilder#stampRoomAt} lays the room and nothing else, and
 * the twins only exist in a live structure underground. So the two openings an author has to build
 * around are invisible in the plot, and the only thing that ever said where they were was a sentence
 * in the chat message on {@code PortalRoomEditor.enter}. This is that sentence as geometry, so the
 * editor can paint it.</p>
 *
 * <p><b>Derived from the writers, not chosen.</b> Each axis below is read off the code that actually
 * puts the door there, and {@code PortalRoomDoorCellsTest} sweeps the Z line against
 * {@link PortalRoomLayout#roomOrigin} for every legal width so the two cannot drift apart:</p>
 *
 * <ul>
 *   <li><b>X: one column outside each ±X end.</b> An entry corridor's mouth is its far door, at
 *       {@code roomOrigin.x - 1}; an exit corridor's is its near door, at
 *       {@code roomOrigin.x + size.x}. Those are {@code stampCorridorHalf}'s two {@code sealX}
 *       values, and the same pair of columns {@code bedrockSkin} reaches out to.</li>
 *   <li><b>Z: the walkway centre line.</b> {@link PortalRoomLayout#roomOrigin} centres the room's
 *       <i>interior</i> ({@code size.z - 2}) on {@link PortalCarriageLayout#doorZ()}, which puts the
 *       line at {@code roomOrigin.z + 1 + (size.z - 2) / 2} — for odd and even widths alike.</li>
 *   <li><b>Y: two blocks, sitting on the floor.</b> {@link PortalCarriageLayout#isDoorwayCell} opens
 *       {@code dy <= floorY() + 2}, and {@link PortalCarriageLayout#isShellCell} claims the floor row
 *       itself, so what is left for the door is {@code floorY() + 1} and {@code floorY() + 2}. A
 *       corridor's {@code floorY()} is 0 and its origin shares the room's Y, so those are
 *       {@code roomOrigin.y + 1} and {@code + 2}.</li>
 * </ul>
 *
 * <p>Pure: no level, no dims, no registry. Everything it needs is in the room's own box, which is
 * what lets the editor ask the question for a plot that has no structure behind it at all.</p>
 */
public final class PortalRoomDoorCells {

    /** How many cells a single door occupies — the two-block column of {@link PortalCarriageLayout#isDoorwayCell}. */
    public static final int CELLS_PER_DOOR = 2;

    /** Every door cell of a room: {@link #CELLS_PER_DOOR} at each of the two ends. */
    public static final int CELLS_PER_ROOM = CELLS_PER_DOOR * 2;

    private PortalRoomDoorCells() {}

    /**
     * The four cells the two corridor doors occupy for a room whose minimum corner is
     * {@code roomOrigin} and whose full box, shell included, is {@code size}.
     *
     * <p>Returned in a stable order — entry end first, lower cell first — so a caller keying a dedup
     * string off the list gets the same string for the same room.</p>
     *
     * <p>Empty for a degenerate box rather than throwing. The callers are an overlay and a render
     * pass: a plot whose size has not been primed yet should draw nothing for a frame, not fail the
     * tick that would have primed it.</p>
     */
    public static List<BlockPos> forRoom(BlockPos roomOrigin, Vec3i size) {
        if (roomOrigin == null || size == null) return List.of();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 2) return List.of();

        int doorZ = doorZ(roomOrigin, size);
        List<BlockPos> cells = new ArrayList<>(CELLS_PER_ROOM);
        addDoor(cells, roomOrigin.getX() - 1, roomOrigin.getY(), doorZ);
        addDoor(cells, roomOrigin.getX() + size.getX(), roomOrigin.getY(), doorZ);
        return cells;
    }

    /**
     * World Z of the walkway centre line the two doorways sit on — the line an authored room must
     * leave clear from end to end.
     */
    public static int doorZ(BlockPos roomOrigin, Vec3i size) {
        return roomOrigin.getZ() + 1 + (size.getZ() - 2) / 2;
    }

    /** The two cells of one door: the pair above the floor row at {@code floorY}. */
    private static void addDoor(List<BlockPos> out, int x, int floorY, int z) {
        out.add(new BlockPos(x, floorY + 1, z));
        out.add(new BlockPos(x, floorY + 2, z));
    }
}
