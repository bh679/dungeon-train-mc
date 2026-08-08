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

    /**
     * How close to vanilla's own distance counts as "all the way back out", rather than easing
     * forever toward a value nobody sees.
     */
    private static final float OFF_EPSILON = 0.5f;

    private static volatile PortalRoomFogPacket region = PortalRoomFogPacket.none();

    /**
     * What is actually being drawn, chasing the target. Only ever touched on the render thread.
     *
     * <p>{@code 0} is the sentinel for <i>disengaged</i> — vanilla is in charge and nothing here
     * touches the fog. Every other value is a real fog distance somewhere between the room's radius
     * and vanilla's own far plane.</p>
     */
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
     *
     * <p><b>The ease runs between vanilla's distance and the room's, never up from zero.</b> Easing
     * from zero is what made a portal swap flash: the first frames inside a room hand back 4, 8, 12
     * blocks, and a far plane of four blocks is not "barely any fog", it is the screen filled with
     * fog colour — which underground in a lit overworld is pale enough to read as a white flash. So
     * the moment the camera engages, the applied value starts at whatever vanilla was already drawing
     * and closes in from there, which makes the first frame identical to the one before it. Walking
     * back out opens up to vanilla's distance and disengages at the <i>top</i> of the range rather
     * than the bottom.</p>
     *
     * @param vanillaFar the far plane vanilla had computed for this frame, from the player's own
     *                   render distance and fog setting — the "no room" end of the ease
     */
    public static float fogDistanceAt(double x, double y, double z, float vanillaFar) {
        if (vanillaFar <= 0.0f) return 0.0f;

        boolean inside = contains(x, y, z);
        float radius = inside ? radiusAt(x, z) : 0.0f;
        // A room fogging at further than the player can see anyway has nothing to say, and engaging
        // for it would only add a pointless ease in both directions. Disengage outright rather than
        // returning zero with a live value still cached, which would ease out of a stale distance if
        // the render distance moved again.
        //
        // Asked of the ramped distance rather than the room's nominal one: a Bedrockless room fogs
        // at the clearance, which on a short render distance is already past the far plane, and the
        // whole point of the ramp is what happens as a player walks out of that into eight blocks.
        // Testing the nominal figure would hand the entire walk to vanilla.
        if (inside && radius >= vanillaFar) {
            applied = 0.0f;
            return 0.0f;
        }
        if (!inside && applied <= 0.0f) return 0.0f;

        float target = inside ? radius : vanillaFar;
        // Engaging: start level with vanilla so this frame changes nothing, then close in.
        if (applied <= 0.0f) applied = vanillaFar;
        applied += (target - applied) * EASE_PER_FRAME;
        // All the way back out: hand the fog to vanilla outright rather than sitting a hair under it.
        if (!inside && applied >= vanillaFar - OFF_EPSILON) {
            applied = 0.0f;
            return 0.0f;
        }
        return applied;
    }

    /**
     * How far the room says a player standing here can see — the room's nominal radius at its own
     * walls, closing toward {@link PortalRoomFogPacket#minRadius} the further out into the swept
     * clearance they get.
     *
     * <p>The room's own box is the region shrunk by the falloff, which is exactly how the server grew
     * it. A {@code falloff} of zero — every mode but Bedrockless, and all of them before the ramp
     * existed — is a flat fog and returns before measuring anything.</p>
     *
     * <p><b>Chebyshev, not Euclidean.</b> The distance is the larger of the two axis overshoots
     * rather than the diagonal between them, because the space being crossed is a box: the sweep is
     * the clearance on each axis independently, so a player standing at a corner of it is at the end
     * of the walk on both axes and should see the far end of the ramp, not {@code √2} past it.</p>
     *
     * <p><b>Y is not in it</b>, for the same reason the clearance has no vertical term — the lanes
     * pairs are spread over are {@code TWIN_LANE_HEIGHT} apart, so there is no vertical emptiness to
     * ramp across. A Bedrockless room's void is flat, and the distance worth measuring is the walk.</p>
     */
    private static float radiusAt(double x, double z) {
        PortalRoomFogPacket r = region;
        if (r.falloff() <= 0) return r.radius();

        double outX = Math.max(0.0, Math.max((r.minX() + r.falloff()) - x, x - (r.maxX() + 1 - r.falloff())));
        double outZ = Math.max(0.0, Math.max((r.minZ() + r.falloff()) - z, z - (r.maxZ() + 1 - r.falloff())));
        float t = (float) Math.min(1.0, Math.max(outX, outZ) / r.falloff());
        return r.radius() + (r.minRadius() - r.radius()) * t;
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
