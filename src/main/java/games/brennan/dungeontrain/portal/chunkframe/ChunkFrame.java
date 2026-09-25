package games.brennan.dungeontrain.portal.chunkframe;

import games.brennan.dungeontrain.portal.PortalChunkTerrain;
import net.minecraft.core.Vec3i;

/**
 * The geometry of a chunk dimension's <b>frame</b>: one template that dresses a dimensional carriage
 * room — its walls, floor, roof and ends together.
 *
 * <p>A frame is the room box grown by one block on every side: {@link #SIZE} is {@code 18 × 34 × 18}
 * for the {@code 16 × 32 × 16} box, and template cell {@code (0,0,0)} lands one block below and
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

    /** The room box a frame fits — a dimensional carriage's own. */
    public static final Vec3i ROOM_SIZE = new Vec3i(PortalChunkTerrain.SIZE, PortalChunkTerrain.HEIGHT,
        PortalChunkTerrain.SIZE);

    /** The template's size: the room plus one block on every side. */
    public static final Vec3i SIZE = new Vec3i(ROOM_SIZE.getX() + 2, ROOM_SIZE.getY() + 2, ROOM_SIZE.getZ() + 2);

    /** Where template cell (0,0,0) sits relative to the room origin. */
    public static final Vec3i OFFSET = new Vec3i(-1, -1, -1);

    private ChunkFrame() {}

    /** True when template-local {@code (x, y, z)} is in the shell, outside the room box. */
    public static boolean isShell(int x, int y, int z) {
        return x == 0 || y == 0 || z == 0
            || x == SIZE.getX() - 1 || y == SIZE.getY() - 1 || z == SIZE.getZ() - 1;
    }
}
