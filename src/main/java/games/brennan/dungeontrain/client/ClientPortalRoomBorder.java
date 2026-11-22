package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.PortalRoomBorderPacket;

/**
 * The World Border room the server last named — the client half of {@link PortalRoomBorderPacket},
 * read by {@code PortalRoomBorderRenderer} every frame.
 *
 * <p>A place, not a state, like {@link ClientPortalRoomFog}: it says where the border stands, and
 * the renderer decides from the player each frame whether they are inside it (blue) or through it
 * (red). Cleared on logout so a border never follows a player into the next world.</p>
 */
public final class ClientPortalRoomBorder {

    private static volatile PortalRoomBorderPacket region = PortalRoomBorderPacket.none();

    private ClientPortalRoomBorder() {}

    /** Apply a server update. */
    public static void update(PortalRoomBorderPacket packet) {
        region = packet == null ? PortalRoomBorderPacket.none() : packet;
    }

    /** The region to draw, or {@link PortalRoomBorderPacket#none()} for nothing. */
    public static PortalRoomBorderPacket region() {
        return region;
    }

    /** Forget the room. Wired to logging out. */
    public static void reset() {
        region = PortalRoomBorderPacket.none();
    }
}
