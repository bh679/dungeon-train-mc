package games.brennan.dungeontrain.client;

/**
 * Client-side cache of how strongly the portal corridor the player is in wants its lightmap held at
 * a constant — the third of the portal caches {@link PortalRoomFogEvents} clears on logout, and
 * shaped like {@link ClientPortalRoomSky}.
 *
 * <h2>Why this one holds a value and not a place</h2>
 * <p>Its two siblings cache a <b>box</b> and evaluate the camera against it, because a player who
 * disconnects inside a region never hears the "you have left" message and would come back
 * permanently fogged or permanently bright. That trick is not available here: the corridor rides a
 * Sable sub-level, so its blocks live at far shipyard coordinates while the player and the swap
 * arithmetic ({@code PortalFrames}) are in rendered track space. A box the client could test the
 * camera against would have to be re-sent every tick as the train moved, which is the cost the box
 * was chosen to avoid in the first place.</p>
 *
 * <p>So the server sends the ramp itself ({@code PortalCrossingLight}), and the stale-value hazard
 * is closed a different way: the value <b>eases to zero on its own</b> whenever nothing is
 * refreshing it. {@link #update} restarts a countdown; when it runs out the lift fades out over
 * about a second and disengages. A disconnect, a dropped packet or a server that stopped caring all
 * land on the same harmless outcome, with no message needed.</p>
 *
 * <p>Pure logic, no rendering imports — {@code LightTexturePortalCrossingMixin} is what talks to the
 * lightmap, and it calls {@link #advance} once per rebuild.</p>
 */
public final class ClientPortalCrossing {

    /**
     * How fast the lift comes up or goes down, as a fraction of the remaining gap per lightmap
     * rebuild — the same rate {@link ClientPortalRoomSky} eases at, and per rebuild rather than per
     * frame for the same reason: {@code LightTexture} refreshes on a tick flag, so this steps at
     * roughly 20 Hz and crosses most of the way in about a second.
     *
     * <p>The server's own ramp already moves a block at a time as the player walks. This is what
     * turns those steps into a slope, and what covers the tick a packet arrives late.</p>
     */
    private static final float EASE_PER_REBUILD = 0.10f;

    /** Below this the lift is not worth applying, and the mixin hands the lightmap back to vanilla. */
    private static final float OFF_EPSILON = 0.004f;

    /**
     * Rebuilds a target survives without being restated before it decays to zero.
     *
     * <p>Twenty is a second at the rebuild clock — long enough that an ordinary hitch or a tick the
     * server spent elsewhere does not drop the lift, short enough that a client which stops hearing
     * from the server is back to vanilla lighting almost immediately. This is the whole of the
     * stale-state defence, so it is deliberately not generous.</p>
     */
    private static final int TARGET_TTL_REBUILDS = 20;

    /** What the server last said, {@code 0}..{@code 1}. Written on the network thread. */
    private static volatile float target = 0.0f;

    /** Rebuilds left before {@link #target} is treated as {@code 0}. Render thread only. */
    private static int ttl = 0;

    /** How much of the lift is actually applied, {@code 0}..{@code 1}. Render thread only. */
    private static float applied = 0.0f;

    private ClientPortalCrossing() {}

    /** Apply a server update. */
    public static void update(float intensity) {
        target = Math.max(0.0f, Math.min(1.0f, intensity));
    }

    /** Forget everything. Wired to logging out, so a corridor never leaks into the next world. */
    public static void reset() {
        target = 0.0f;
        ttl = 0;
        applied = 0.0f;
    }

    /**
     * Advance the ease and return how strongly the crossing's constant should be applied this
     * rebuild, {@code 0} for "leave the lightmap alone".
     *
     * <p>Meant to be called exactly once per lightmap rebuild, and unconditionally — including while
     * the effect is switched off in the client config, so that turning it back on does not resume
     * from a value a minute out of date.</p>
     */
    public static float advance() {
        float wanted = target;
        if (wanted > 0.0f) {
            // Refreshed. The countdown restarts rather than accumulating, so a burst of packets
            // cannot buy the lift a longer life than one message's worth.
            ttl = TARGET_TTL_REBUILDS;
        } else if (ttl > 0) {
            ttl--;
        }
        // An expired target is a target of zero, whatever the last packet said. Read here rather
        // than zeroing `target` so a late refresh is still accepted.
        if (ttl <= 0) wanted = 0.0f;

        if (wanted <= 0.0f && applied <= 0.0f) return 0.0f;

        applied += (wanted - applied) * EASE_PER_REBUILD;
        if (wanted <= 0.0f && applied <= OFF_EPSILON) {
            applied = 0.0f;
            return 0.0f;
        }
        return applied;
    }
}
