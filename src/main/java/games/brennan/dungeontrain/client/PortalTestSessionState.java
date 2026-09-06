package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.PortalTestSessionPacket;
import net.minecraft.client.Minecraft;

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
        boolean was = active;
        active = packet.active();
        roomName = packet.roomName();
        // A test puts Skybox Blocks back however the author's switch is set, and that changes what
        // they cull as well as what they draw. The meshes standing when it starts or ends were
        // built against the other answer, so they are rebuilt — but only when the switch is off,
        // which is the only case where the two answers differ.
        if (was != active && !ClientDisplayConfig.areSkyboxBlocksOn()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
        }
    }

    /** True while this player is standing in a stamped test carriage. */
    public static boolean active() { return active; }

    /** The room it was stamped from, for the row's label. Empty when nothing is active. */
    public static String roomName() { return roomName; }
}
