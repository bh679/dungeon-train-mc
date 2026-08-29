package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.PortalRoomSkyPacket;
import games.brennan.dungeontrain.portal.PortalRoomSky;

/**
 * Client-side cache of the daylit portal room the player is in, the sibling of
 * {@link ClientPortalRoomFog} and shaped exactly like it.
 *
 * <p><b>It stores a place, not a state.</b> A player who disconnects inside a room never receives
 * the "you are not daylit any more" message, and would come back permanently bright. Holding the
 * room's bounds instead means walking out ends the lift with no message at all, and a disconnect
 * drops the cache along with everything else.</p>
 *
 * <p>Pure logic, no rendering imports — {@code LightTexturePortalRoomMixin} is what talks to the
 * lightmap, and it reads {@link #intensity()} and {@link #sky()} once per rebuild.</p>
 */
public final class ClientPortalRoomSky {

    /**
     * How fast the lift comes up or goes down, as a fraction of the remaining gap per lightmap
     * rebuild.
     *
     * <p><b>Per rebuild, not per frame</b> — {@code LightTexture} refreshes on a tick flag rather
     * than every frame, so this steps at roughly 20 Hz and reaches most of the way across in about a
     * second. Stepping straight to the target would have the world change brightness between two
     * frames as a foot crossed the threshold, which reads as a flash; it is also what makes a portal
     * swap survivable, since the arrival puts the camera inside a room with no walk-in at all.</p>
     */
    private static final float EASE_PER_REBUILD = 0.10f;

    /** Below this the lift is not worth applying, and the mixin hands the lightmap back to vanilla. */
    private static final float OFF_EPSILON = 0.004f;

    private static volatile PortalRoomSkyPacket region = PortalRoomSkyPacket.none();

    /**
     * The sky whose tint is currently being eased. Held separately from {@link #region} so that
     * walking from a Nether-skied room into a Daylight one eases <i>out</i> of the first before
     * easing into the second, rather than swapping tints at full strength mid-fade.
     */
    private static PortalRoomSky easing = PortalRoomSky.NONE;

    /** How much of the lift is actually applied, {@code 0}..{@code 1}. Render thread only. */
    private static float applied = 0.0f;

    private ClientPortalRoomSky() {}

    /** Apply a server update. */
    public static void update(PortalRoomSkyPacket packet) {
        region = packet == null ? PortalRoomSkyPacket.none() : packet;
    }

    /** Forget everything. Wired to logging out, so a room never leaks into the next world. */
    public static void reset() {
        region = PortalRoomSkyPacket.none();
        easing = PortalRoomSky.NONE;
        applied = 0.0f;
    }

    /**
     * Advance the ease and return how strongly the room's sky should be applied this rebuild,
     * {@code 0} for "leave the lightmap alone".
     *
     * <p>Meant to be called exactly once per lightmap rebuild, before {@link #sky()}. The camera
     * position is read here rather than passed in because the two hooks that need this run at
     * different points in vanilla's method and only one of them is in a position to supply one.</p>
     *
     * <p><b>A corridor counts as partway in.</b> The room's own box stops at its walls, and a player
     * walking a portal corridor toward a daylit room used to get the whole lift in the step that
     * carried them through the doorway. {@code crossing} — the corridor ramp {@code ClientPortalRoomFog}
     * and the crossing lift both ride — is the target while they are outside the box, so the room's
     * light comes up over the walk and is already there when they arrive. It ramps down again on the
     * way out, since the ramp is measured from the train end whichever corridor it is.</p>
     *
     * @param crossing how far through a portal corridor the camera is, {@code 0}..{@code 1}, or
     *                 {@code 0} when it is in none — see {@link ClientPortalCrossing#current}
     */
    public static float advance(double x, double y, double z, float crossing) {
        PortalRoomSkyPacket r = region;
        PortalRoomSky wanted = r.skyKind();
        // An editor plot's lift is the author's to switch off; a room in the world is not. Read as
        // "not inside" rather than dropped on arrival, so turning it off mid-plot fades out through
        // the ease below exactly as walking out of the room does.
        boolean allowed = !r.editor() || ClientDisplayConfig.isEditorPlotLighting();
        boolean lit = allowed && wanted.lights();
        // Full inside the room, the corridor ramp on the way to it, nothing anywhere else.
        float want = 0.0f;
        if (lit) {
            want = contains(r, x, y, z) ? 1.0f : Math.max(0.0f, Math.min(1.0f, crossing));
        }

        // Changing sky mid-lift: fade the old one out first and only then adopt the new one, so the
        // tint never jumps from red to white at full strength. A room entered from nothing adopts
        // immediately, which is the ordinary case.
        if (want > 0.0f && wanted != easing) {
            if (applied <= OFF_EPSILON) {
                easing = wanted;
            } else {
                want = 0.0f;
            }
        }
        if (want <= 0.0f && applied <= 0.0f) return 0.0f;

        applied += (want - applied) * EASE_PER_REBUILD;
        if (want <= 0.0f && applied <= OFF_EPSILON) {
            applied = 0.0f;
            easing = PortalRoomSky.NONE;
            return 0.0f;
        }
        return applied;
    }

    /** The sky the current lift is toward — only meaningful while {@link #advance} returns above zero. */
    public static PortalRoomSky sky() {
        return easing;
    }

    /**
     * True when the camera is inside the copies of the room that have actually been stamped.
     *
     * <p>Unlike the fog's test this one has a real vertical term and no horizontal padding: the
     * lift belongs to the room's own box and stops at its walls. What reaches past them is not this
     * box but the corridor ramp {@link #advance} takes, which is a walk toward the room rather than
     * a region around it.</p>
     */
    private static boolean contains(PortalRoomSkyPacket r, double x, double y, double z) {
        return x >= r.minX() && x <= r.maxX() + 1
            && y >= r.minY() && y <= r.maxY() + 1
            && z >= r.minZ() && z <= r.maxZ() + 1;
    }
}
