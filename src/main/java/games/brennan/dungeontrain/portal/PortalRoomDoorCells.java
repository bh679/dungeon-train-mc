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
 *   <li><b>Y: two blocks, {@code doorHeightOffset} above the room's own floor.</b>
 *       {@link PortalCarriageLayout#isDoorwayCell} opens {@code dy <= floorY() + 2}, and
 *       {@link PortalCarriageLayout#isShellCell} claims the floor row itself, so what is left for the
 *       door is {@code floorY() + 1} and {@code + 2} relative to wherever the corridor's own floor
 *       actually sits — {@code roomOrigin.y + doorHeightOffset + 1} and {@code + 2}. At the default
 *       offset (0) the corridor's floor is the room's own floor, which is {@code roomOrigin.y + 1}
 *       and {@code + 2} exactly as it always was.</li>
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
        return forRoom(roomOrigin, size, 0, 0);
    }

    /**
     * As {@link #forRoom(BlockPos, Vec3i)}, with the door line shifted {@code doorOffset} blocks off
     * dead centre — the same offset {@link PortalRoomLayout#roomOrigin(BlockPos,
     * games.brennan.dungeontrain.train.CarriageDims, PortalCarriageLayout, int, int)} was given when
     * it placed the box this is called against.
     */
    public static List<BlockPos> forRoom(BlockPos roomOrigin, Vec3i size, int doorOffset) {
        return forRoom(roomOrigin, size, doorOffset, 0);
    }

    /**
     * As {@link #forRoom(BlockPos, Vec3i, int)}, with the door line also raised {@code
     * doorHeightOffset} blocks above the room's own bottom edge — see {@link PortalRoomDoorHeightOffset}.
     */
    public static List<BlockPos> forRoom(
        BlockPos roomOrigin, Vec3i size, int doorOffset, int doorHeightOffset
    ) {
        if (roomOrigin == null || size == null) return List.of();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 2) return List.of();

        int doorZ = doorZ(roomOrigin, size, doorOffset);
        int floorY = roomOrigin.getY() + doorHeightOffset;
        List<BlockPos> cells = new ArrayList<>(CELLS_PER_ROOM);
        addDoor(cells, roomOrigin.getX() - 1, floorY, doorZ);
        addDoor(cells, roomOrigin.getX() + size.getX(), floorY, doorZ);
        return cells;
    }

    /**
     * The two <b>lower</b> door cells — one per end, in the same order {@link #forRoom} uses.
     *
     * <p>What the editor overlay actually ships, because a door is one object rather than two cells:
     * the upper half is always the block above the lower, so naming the base says which half each
     * cell is without a flag per position. {@link #forRoom} stays the full cell list for anything
     * asking "is this cell a doorway", which is a different question.</p>
     */
    public static List<BlockPos> doorBases(BlockPos roomOrigin, Vec3i size) {
        return doorBases(roomOrigin, size, 0, 0);
    }

    /** As {@link #doorBases(BlockPos, Vec3i)}, with the door line shifted by {@code doorOffset}. */
    public static List<BlockPos> doorBases(BlockPos roomOrigin, Vec3i size, int doorOffset) {
        return doorBases(roomOrigin, size, doorOffset, 0);
    }

    /** As {@link #doorBases(BlockPos, Vec3i, int)}, also raised by {@code doorHeightOffset}. */
    public static List<BlockPos> doorBases(
        BlockPos roomOrigin, Vec3i size, int doorOffset, int doorHeightOffset
    ) {
        List<BlockPos> all = forRoom(roomOrigin, size, doorOffset, doorHeightOffset);
        if (all.isEmpty()) return List.of();
        List<BlockPos> bases = new ArrayList<>(2);
        for (int i = 0; i < all.size(); i += CELLS_PER_DOOR) {
            bases.add(all.get(i));
        }
        return bases;
    }

    /**
     * World Z of the walkway centre line the two doorways sit on — the line an authored room must
     * leave clear from end to end.
     */
    public static int doorZ(BlockPos roomOrigin, Vec3i size) {
        return doorZ(roomOrigin, size, 0);
    }

    /**
     * As {@link #doorZ(BlockPos, Vec3i)}, with the line shifted {@code doorOffset} blocks off dead
     * centre of the box — see {@link PortalRoomDoorOffset} for what the offset means and why it is
     * threaded in rather than derived from the box alone.
     */
    public static int doorZ(BlockPos roomOrigin, Vec3i size, int doorOffset) {
        return roomOrigin.getZ() + 1 + (size.getZ() - 2) / 2 + doorOffset;
    }

    /** The two cells of one door: the pair above the floor row at {@code floorY}. */
    private static void addDoor(List<BlockPos> out, int x, int floorY, int z) {
        out.add(new BlockPos(x, floorY + 1, z));
        out.add(new BlockPos(x, floorY + 2, z));
    }
}
