package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.PortalRoomFogPacket;

/**
 * Client-side cache of the endless portal room the player is in, mirroring {@link ClientNetherBand}.
 *
 * <p><b>It stores a place, not a state.</b> "You are fogged" and "you are no longer fogged" is the
 * obvious pair of messages and has one failure mode that matters: a player who disconnects while
 * inside a room never receives the second one and comes back permanently fogged. Holding the room's
 * bounds instead means walking out clears the fog with no message at all, and a disconnect drops the
 * cache along with everything else.</p>
 *
 * <p>Pure logic, no rendering imports — {@link PortalRoomFogEvents} is what talks to the renderer.</p>
 */
public final class ClientPortalRoomFog {

    /**
     * How fast the applied fog closes in or opens out, as a fraction of the remaining gap per frame.
     *
     * <p>Stepping straight to the target would snap the view the instant a foot crossed a threshold,
     * and the thresholds here move: copies of the room appear and retire underneath the player. The
     * ease also means a packet arriving a tick late costs nothing.</p>
     */
    private static final float EASE_PER_FRAME = 0.08f;

    /** Below this the fog is treated as off, rather than easing forever toward a value nobody sees. */
    private static final float OFF_EPSILON = 0.5f;

    private static volatile PortalRoomFogPacket region = PortalRoomFogPacket.none();

    /** What is actually being drawn, chasing the target. Only ever touched on the render thread. */
    private static float applied = 0.0f;

    private ClientPortalRoomFog() {}

    /** Apply a server update. */
    public static void update(PortalRoomFogPacket packet) {
        region = packet == null ? PortalRoomFogPacket.none() : packet;
    }

    /** Forget everything. Wired to logging out, so a room never leaks into the next world. */
    public static void reset() {
        region = PortalRoomFogPacket.none();
        applied = 0.0f;
    }

    /**
     * The fog distance to draw at this camera position, or {@code 0} for "leave the fog alone".
     *
     * <p>Eases toward the target every call, so this is meant to be asked once a frame.</p>
     */
    public static float fogDistanceAt(double x, double y, double z) {
        float target = contains(x, y, z) ? region.radius() : 0.0f;
        applied += (target - applied) * EASE_PER_FRAME;
        if (applied < OFF_EPSILON && target <= 0.0f) applied = 0.0f;
        return applied;
    }

    /**
     * True when the camera is inside the copies of the room that have actually been stamped.
     *
     * <p>The bounds are of what was built rather than of what the mode asked for. Copies can fail to
     * appear — an unloaded chunk, a spent budget, another pair's structure in the way — and a fog
     * that reached past them would be claiming a room the player could walk out of.</p>
     */
    private static boolean contains(double x, double y, double z) {
        PortalRoomFogPacket r = region;
        if (r.radius() <= 0.0f) return false;
        return x >= r.minX() && x <= r.maxX() + 1
            && y >= r.minY() && y <= r.maxY() + 1
            && z >= r.minZ() && z <= r.maxZ() + 1;
    }
}
