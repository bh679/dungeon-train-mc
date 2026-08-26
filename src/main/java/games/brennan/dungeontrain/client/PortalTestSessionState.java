package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.PortalTestSessionPacket;

/**
 * Client mirror of {@link games.brennan.dungeontrain.portal.PortalTestSession} — whether this player
 * is inside a test dimensional carriage right now, and which room it was stamped from.
 *
 * <p>Read by {@code EditorMenuScreen} to decide whether to offer the Back row. Same shape as
 * {@link DebugFlagsState} and the other server-state mirrors: a static the packet writes and the
 * menu reads, with a safe default for a client that has been told nothing.</p>
 */
public final class PortalTestSessionState {

    private static boolean active = false;
    private static String roomName = "";

    private PortalTestSessionState() {}

    public static void update(PortalTestSessionPacket packet) {
        active = packet.active();
        roomName = packet.roomName();
    }

    /** True while this player is standing in a stamped test carriage. */
    public static boolean active() { return active; }

    /** The room it was stamped from, for the row's label. Empty when nothing is active. */
    public static String roomName() { return roomName; }
}
