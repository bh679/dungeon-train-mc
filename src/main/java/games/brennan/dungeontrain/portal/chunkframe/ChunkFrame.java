package games.brennan.dungeontrain.portal.chunkframe;

import games.brennan.dungeontrain.portal.PortalChunkTerrain;
import net.minecraft.core.Vec3i;

/**
 * The geometry of a chunk dimension's <b>frame</b>: one template that dresses a dimensional carriage
 * room — its walls, floor, roof and ends together.
 *
 * <p>A frame is the room box grown by one block on every side: {@link #SIZE} is {@code 18 × 34 × 18}
 * for the {@code 16 × 32 × 16} box and {@code 50 × 34 × 50} for a 3x3 room ({@link #sizeFor}), and template cell {@code (0,0,0)} lands one block below and
 * outside the room's own origin ({@link #OFFSET}). So the template holds two regions, written by
 * different rules in {@link ChunkFramePlacer}:</p>
 * <ul>
 *   <li><b>the shell</b> — the one-block ring outside the room box, where the lock skin (the skybox)
 *       stands. A frame's blocks there replace the skin; its air leaves the skin.</li>
 *   <li><b>the inside</b> — the whole room box, where the sampled terrain runs. A frame's blocks
 *       there overwrite the terrain, as deep into the room as the author builds; its air leaves the
 *       terrain.</li>
 * </ul>
 */
public final class ChunkFrame {

    /** The one-chunk room box — what most frames fit. */
    public static final Vec3i ROOM_SIZE = new Vec3i(PortalChunkTerrain.SIZE, PortalChunkTerrain.HEIGHT,
        PortalChunkTerrain.SIZE);

    /** A one-chunk room's frame: the room plus one block on every side. */
    public static final Vec3i SIZE = sizeFor(ROOM_SIZE);

    /** Where template cell (0,0,0) sits relative to the room origin — the same for every size. */
    public static final Vec3i OFFSET = new Vec3i(-1, -1, -1);

    private ChunkFrame() {}

    // ---- sizes ---------------------------------------------------------------

    /**
     * The frame that fits a room of {@code roomSize}: one block bigger on every side. A frame dresses
     * exactly the rooms it fits — a one-chunk frame the one-chunk rooms, a 3x3 frame the 3x3 rooms —
     * whatever its {@link ChunkFrameMeta} room list says.
     */
    public static Vec3i sizeFor(Vec3i roomSize) {
        return new Vec3i(roomSize.getX() + 2, roomSize.getY() + 2, roomSize.getZ() + 2);
    }

    /** The room a frame of {@code frameSize} fits. */
    public static Vec3i roomFor(Vec3i frameSize) {
        return new Vec3i(frameSize.getX() - 2, frameSize.getY() - 2, frameSize.getZ() - 2);
    }

    /**
     * Whether a frame may be {@code frameSize}: it fits a generated room — the one-chunk box, or a
     * grid of chunks {@link games.brennan.dungeontrain.portal.PortalChunkArea} samples — at the
     * generated rooms' height.
     */
    public static boolean validSize(Vec3i frameSize) {
        if (frameSize == null) return false;
        Vec3i room = roomFor(frameSize);
        if (room.getY() != PortalChunkTerrain.HEIGHT) return false;
        return room.equals(ROOM_SIZE) || games.brennan.dungeontrain.portal.PortalChunkArea.span(room) > 1;
    }

    /** The frame size a generated room called {@code room} takes, from its template's footprint. */
    public static Vec3i sizeForRoom(String room) {
        int width = games.brennan.dungeontrain.portal.PortalChunkArea.spanOf(room) * PortalChunkTerrain.SIZE;
        return sizeFor(new Vec3i(width, PortalChunkTerrain.HEIGHT, width));
    }

    // ---- rooms ---------------------------------------------------------------

    /**
     * Every generated room a frame of any size can dress — the room variants whose walls are
     * {@code chunk_dimension} — in the room registry's order.
     */
    public static java.util.List<String> chunkRooms() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String room : games.brennan.dungeontrain.track.variant.TrackVariantRegistry.namesFor(
                games.brennan.dungeontrain.track.variant.TrackKind.PORTAL_ROOM)) {
            if (games.brennan.dungeontrain.portal.PortalRoomSettings.of(room).mode().generatesTerrain()) out.add(room);
        }
        return out;
    }

    /** The generated rooms frame {@code frame} fits — what its room toggles list. */
    public static java.util.List<String> chunkRooms(String frame) {
        Vec3i frameSize = ChunkFrameStore.sizeOf(frame);
        return chunkRooms().stream().filter(room -> sizeForRoom(room).equals(frameSize)).toList();
    }

    // ---- cells ---------------------------------------------------------------

    /** True when template-local {@code (x, y, z)} is in a one-chunk frame's shell, outside the room box. */
    public static boolean isShell(int x, int y, int z) {
        return isShell(x, y, z, SIZE);
    }

    /** True when template-local {@code (x, y, z)} is in the shell of a frame of {@code size}. */
    public static boolean isShell(int x, int y, int z, Vec3i size) {
        return x == 0 || y == 0 || z == 0
            || x == size.getX() - 1 || y == size.getY() - 1 || z == size.getZ() - 1;
    }
}
