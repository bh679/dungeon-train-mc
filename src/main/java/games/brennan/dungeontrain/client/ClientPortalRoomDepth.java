package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.PortalRoomDepthPacket;

/**
 * Client-side cache of the portal structure the player is standing in and the Y shift that makes its
 * debug-screen coordinates read as though it stood on the surface — the sibling of
 * {@link ClientPortalRoomFog} and {@link ClientPortalRoomSky}, and shaped exactly like them.
 *
 * <p><b>It stores a place, not a state.</b> A player who disconnects inside a dimensional carriage
 * never receives the "your coordinates are honest again" message, and would come back reading every
 * Y in the next world a hundred blocks off. Holding the structure's bounds instead means walking out
 * ends the disguise with no message at all, and a disconnect drops the cache along with everything
 * else.</p>
 *
 * <p>Pure logic, no rendering imports — {@code DebugScreenOverlayDepthMixin} is what talks to the
 * debug screen, and {@link DebugScreenDepthLines} is what rewrites its text.</p>
 */
public final class ClientPortalRoomDepth {

    private static volatile PortalRoomDepthPacket region = PortalRoomDepthPacket.none();

    private ClientPortalRoomDepth() {}

    /** Apply a server update. */
    public static void update(PortalRoomDepthPacket packet) {
        region = packet == null ? PortalRoomDepthPacket.none() : packet;
    }

    /** Forget everything. Wired to logging out, so a structure never leaks into the next world. */
    public static void reset() {
        region = PortalRoomDepthPacket.none();
    }

    /**
     * Blocks to add to a Y displayed for a camera at {@code (x, y, z)}, or {@code 0} for "show the
     * truth" — outside any structure, or with the disguise switched off in the client config.
     *
     * <p>The config is read here rather than at the mixin so that every caller gets the same answer,
     * and so that switching it off takes effect on the next frame without anything having to be
     * re-sent.</p>
     */
    public static int shiftAt(double x, double y, double z) {
        if (!ClientDisplayConfig.isPortalRoomSurfaceCoordinates()) return 0;
        PortalRoomDepthPacket r = region;
        return r.contains(x, y, z) ? r.yShift() : 0;
    }
}
