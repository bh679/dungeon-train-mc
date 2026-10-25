package games.brennan.dungeontrain.client.portal;

import games.brennan.dungeontrain.client.ClientPortalRoomDepth;
import games.brennan.dungeontrain.client.ClientPortalSeal;
import games.brennan.dungeontrain.net.PortalRoomDepthPacket;
import games.brennan.dungeontrain.portal.PortalSealPlane;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

/**
 * What a minimap may show — what {@code JmBlockMDSealMixin} and {@code JmChunkHeightSealMixin} ask
 * before JourneyMap maps a block.
 *
 * <p>JourneyMap scans chunks itself, so none of the renderer's seal hooks reach it: a player in a
 * dimensional carriage watched the sealed world's lava and ocean fill the minimap around the room.
 * Two rules, decided by where the camera is this frame:</p>
 * <ul>
 *   <li><b>Inside a dimensional carriage</b>, only the carriage exists. Everything outside its
 *   structure box (corridors and room copies, the same box {@link ClientPortalRoomDepth} holds) maps
 *   as air, which JourneyMap draws black.</li>
 *   <li><b>Anywhere else</b>, the rows {@link ClientPortalSeal} hides are hidden here too, so the
 *   twins under the terrain floor and over the lid stay off the map from the train.</li>
 * </ul>
 *
 * <p>Read from JourneyMap's mapping thread, so the frame's answer is held in one volatile written by
 * {@link #beginFrame} on the render thread, next to the seal's own.</p>
 */
public final class MapSealFilter {

    /** The carriage the camera stood in this frame, or {@code null} outside one. */
    private static volatile PortalRoomDepthPacket room;

    private MapSealFilter() {}

    /** Re-read where the camera is. Called with {@link ClientPortalSeal#beginFrame}. */
    public static void beginFrame(double cameraX, double cameraY, double cameraZ) {
        room = inOverworld() ? ClientPortalRoomDepth.structureAt(cameraX, cameraY, cameraZ) : null;
    }

    /** Forget the carriage. Wired to logging out with the seal. */
    public static void reset() {
        room = null;
    }

    /** Whether the minimap should treat the block at {@code (x, y, z)} as air this frame. */
    public static boolean hidesForMap(int x, int y, int z) {
        return hides(room, ClientPortalSeal.cut(), x, y, z);
    }

    /** A column's heightmap top, lowered so the surface scan starts on the first row it may show. */
    public static int clampSurfaceY(int x, int z, int height) {
        return clampSurface(room, ClientPortalSeal.cut(), x, z, height);
    }

    static boolean hides(PortalRoomDepthPacket room, PortalSealPlane.Cut cut, int x, int y, int z) {
        if (room != null) return !room.contains(x + 0.5, y + 0.5, z + 0.5);
        if (!cut.sealed()) return false;
        return cut.hides(y, y + 1) || cut.hidesBlock(y);
    }

    static int clampSurface(PortalRoomDepthPacket room, PortalSealPlane.Cut cut, int x, int z, int height) {
        if (room != null) {
            // Outside the carriage the column is empty: start it at the box's floor, so the scan has
            // nothing to walk down through.
            boolean inColumn = x >= room.minX() && x < room.maxX() && z >= room.minZ() && z < room.maxZ();
            return Math.min(height, inColumn ? room.maxY() : room.minY());
        }
        // The roof's own trim starts at roofY, so roofY is the lowest hidden row either way.
        if (!cut.sealed() || cut.roofY() == Integer.MAX_VALUE) return height;
        return Math.min(height, cut.roofY());
    }

    /** The structure box is an overworld place; another dimension at the same coordinates is not it. */
    private static boolean inOverworld() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension().equals(Level.OVERWORLD);
    }
}
