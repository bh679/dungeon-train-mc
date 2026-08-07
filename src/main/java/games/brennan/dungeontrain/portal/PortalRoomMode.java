package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * What a portal room does at its own walls — an authored property of a {@code portal_room} variant,
 * stored alongside its weight and gate in the kind's {@code weights.json} (see
 * {@code TemplateMeta.mode()}).
 *
 * <p>A room used to be a fixed shell bounded by whatever rock happened to surround it at the world
 * floor, so every room behaved the same way at its edges. The mode makes the boundary the author's
 * decision.</p>
 *
 * <h2>What each mode does</h2>
 * <ul>
 *   <li>{@link #BEDROCK_LOCK} — a one-block bedrock skin outside the room box. No repetition. The
 *       default, and the closest to how every room behaved before this existed.</li>
 *   <li>{@link #ENDLESS_REPETITION} — the whole room repeats as a grid of copies around the base
 *       tile, seams between neighbours carved open, tiles appended as the player approaches an
 *       edge.</li>
 *   <li>{@link #ENDLESS_OPEN} — the same grid, but only the floor and ceiling planes repeat. No side
 *       walls at all, so it reads as an open plain rather than a hall of rooms.</li>
 * </ul>
 *
 * <h2>Why the tiling grid is X and Z but never Y</h2>
 * <p>Portal pairs are spread over Y lanes {@link PortalRoomLayout#TWIN_LANE_HEIGHT} apart, and
 * {@code PortalCarriageBuilder.eraseTwin} sweeps one row past a structure's top. Vertical repetition
 * would reach into the lane above, which is the collision the lanes exist to prevent. Keeping the
 * grid horizontal makes that safe by construction rather than by a check that could drift.</p>
 *
 * <h2>Why the corridor row does not tile along X</h2>
 * <p>Along X the base room is bookended by the two twin corridors and their plugs — a tile at
 * {@code (±1, 0)} would land on top of a twin, and the twin is exactly what the player walks through
 * to be swapped back onto the train. Pushing the exit twin out to the far edge of the tiled region
 * instead is what {@link PortalStructure}'s javadoc warns against: {@code spanX} is the single source
 * for where the exit twin is stamped, how far {@code eraseTwin} reaches, the occupancy box, <b>and
 * the origin the EXIT role's {@code PortalFrames} maps into</b>. Growing it per appended tile would
 * move the frame a player is standing in, under them, every time a tile appended. So the corridor row
 * holds tile 0 only; step one room sideways in Z and X is free in every direction. See
 * {@link PortalRoomTiling}.</p>
 */
public enum PortalRoomMode {

    /** Sealed in unbreakable rock. The default when a variant says nothing. */
    BEDROCK_LOCK("bedrock_lock", "Bedrock Lock"),

    /** The room repeats around itself, forever, walls between copies carved open. */
    ENDLESS_REPETITION("endless_repetition", "Endless Repetition"),

    /** Only the floor and ceiling repeat — the walls are open. */
    ENDLESS_OPEN("endless_open", "Endless Open");

    /** What a variant with no mode tag — or an unreadable one — behaves as. */
    public static final PortalRoomMode DEFAULT = BEDROCK_LOCK;

    private final String id;
    private final String displayName;

    PortalRoomMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The on-disk / command-line token, e.g. {@code endless_open}. */
    public String id() {
        return id;
    }

    /** Human-readable label for the editor row. */
    public String displayName() {
        return displayName;
    }

    /** True for the two modes that tile the room around itself. */
    public boolean tiles() {
        return this == ENDLESS_REPETITION || this == ENDLESS_OPEN;
    }

    /**
     * True when a tiled face with no neighbour is closed off rather than left open.
     *
     * <p>{@link #ENDLESS_OPEN} is the one that says no: that is what "the walls are open" means, and
     * it is why an Endless Open room has no wall for a player to mine through — the faces always open
     * onto more floor.</p>
     */
    public boolean closesOuterFaces() {
        return this != ENDLESS_OPEN;
    }

    /** True when an appended tile carries the whole room rather than just its floor and ceiling. */
    public boolean tilesWholeRoom() {
        return this == ENDLESS_REPETITION;
    }

    /**
     * The mode named by {@code id}, or {@link #DEFAULT} when it is null, blank or unrecognised.
     *
     * <p>Deliberately total rather than throwing. The tag is free text on disk, and a room whose mode
     * was hand-edited to something misspelt should stamp as a normal sealed room, not fail the whole
     * pair's stamp and leave a player walking into an unbuilt corridor.</p>
     */
    public static PortalRoomMode parse(String id) {
        if (id == null) return DEFAULT;
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT;
        for (PortalRoomMode m : values()) {
            if (m.id.equals(key)) return m;
        }
        return DEFAULT;
    }

    /** The mode after this one, wrapping — what the editor's cycle button steps through. */
    public PortalRoomMode next() {
        PortalRoomMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
