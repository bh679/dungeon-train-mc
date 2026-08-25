package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * Whether a portal room is lit as though it stood outdoors, and which sky it stands under.
 *
 * <p>Portal rooms are stamped into the basement band below the world's bedrock row, so however the
 * dimension is configured there is no daylight down there and never can be. A room set to anything
 * but {@link #NONE} has the <i>lightmap</i> lifted toward its sky while the camera is inside it —
 * the same purely client-side retint {@code LightTextureUpsideDownBandMixin} and its Nether and End
 * siblings already do for the bands, and nothing else. No block is changed, no light is stored, and
 * the server does not know or care.</p>
 *
 * <h2>Why not real skylight</h2>
 * <p>Because vanilla cannot express it. {@code ChunkSkyLightSources} stores <b>one</b> sky-source Y
 * per column and treats everything at or above it as level 15, and {@code SkyLightEngine} fills
 * downward from the top of the world to that Y without consulting opacity. Giving a room its own
 * sky therefore means either flooding the whole rock column above it — which bleeds daylight into
 * every adjacent cave — or capping that fill, which takes the sky away from the real surface the
 * train is running on. A room needs a second sky underneath an existing one, and one Y per column
 * has nowhere to put it.</p>
 *
 * <p><b>What that costs, honestly:</b> the lift is flat. Vanilla's own light values still decide
 * what is bright and what is shadowed, so a room lit only by the builder's ceiling lamps reads as a
 * brightly lit room rather than as an open field — the geometry casts the shadows its lamps give it,
 * not the ones a sun would. Rooms authored to be daylit are the ones this flatters: build them lit,
 * and this makes the light read as sky.</p>
 *
 * <p>Stored as the sixth segment of the room's {@code mode} tag — see {@link PortalRoomSettings},
 * which owns the encoding.</p>
 */
public enum PortalRoomSky {

    /** No lift. The default, and what every room did before this existed. */
    NONE("none", "Off"),

    /** Full daylight, held at noon however late it is on the surface. */
    DAY("day", "Daylight"),

    /**
     * Daylight that follows the world clock, so the room darkens at dusk as outdoors would.
     *
     * <p>The one kind that does not pin: it lifts the lightmap's floor without touching sky-darken,
     * so the room tracks the surface it is pretending to stand on.</p>
     */
    CYCLE("cycle", "Day/Night"),

    /** Pinned, and tinted toward the Nether's red — a room that opens onto somewhere hot. */
    NETHER("nether", "Nether"),

    /** Pinned, and tinted toward the End's violet. */
    END("end", "End");

    private final String id;
    private final String displayName;

    PortalRoomSky(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The on-disk / command-line token. */
    public String id() {
        return id;
    }

    /** Human-readable label for the editor row. */
    public String displayName() {
        return displayName;
    }

    /**
     * The sky named by {@code segment}, or {@link #NONE} when it is null, blank or unrecognised.
     *
     * <p>Total, for the same reason {@link PortalRoomMode#parse} and
     * {@link PortalRoomBooks.Kind#parse} are: the tag is free text on disk, and a room whose setting
     * was hand-edited to something misspelt should stamp an ordinary unlit room rather than fail the
     * pair's stamp.</p>
     */
    public static PortalRoomSky parse(String segment) {
        if (segment == null) return NONE;
        String key = segment.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return NONE;
        for (PortalRoomSky sky : values()) {
            if (sky.id.equals(key)) return sky;
        }
        return NONE;
    }

    /**
     * The sky at {@code ordinal}, or {@link #NONE} when it names nothing.
     *
     * <p>What the network form reads back. Total for the same reason {@link #parse} is, and for one
     * more: a client on a different build than the server will eventually see an ordinal its own
     * enum does not have, and the right answer to that is an unlit room rather than an exception on
     * the packet thread.</p>
     */
    public static PortalRoomSky byOrdinal(int ordinal) {
        PortalRoomSky[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : NONE;
    }

    /** True when this room's lightmap is lifted at all — everything but Off. */
    public boolean lights() {
        return this != NONE;
    }

    /**
     * True when the client should hold the lightmap at full daylight inside this room rather than
     * letting it follow the world clock. {@link #CYCLE} is the one kind that does not: its whole
     * point is that the room darkens with the surface.
     */
    public boolean pinsDaylight() {
        return lights() && this != CYCLE;
    }

    /** The sky after this one, wrapping — what an editor Sky button would step through. */
    public PortalRoomSky next() {
        PortalRoomSky[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
