package games.brennan.dungeontrain.portal;

import java.util.Locale;

/**
 * What a dimensional carriage does at its own walls — an authored property of a {@code dimensional_carriage} variant,
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
 *   <li>{@link #BEDROCKLESS} — {@link #BEDROCK_LOCK} with the bedrock taken away: no repetition, and
 *       nothing at all around the room for {@link DimensionalCarriageLayout#VOID_CLEARANCE} blocks.</li>
 * </ul>
 *
 * <h2>Bedrockless is not "the same room, unsealed"</h2>
 * <p>Twins are stamped in the empty basement under the world's bedrock ({@link PortalTwinLanes}),
 * which generation never reaches, so the space around a room is <i>already</i> empty in an ordinary
 * Dungeon Train world — the lock's skin was the only thing standing in it. What Bedrockless adds on
 * top of not writing that skin is the guarantee: the clearance is swept whatever the room landed in,
 * which is what makes it hold in a Compatible Terrain world, where the basement does not exist and a
 * twin sits inside solid rock.</p>
 *
 * <p>The clearance is horizontal only. Pairs are spread over Y lanes
 * {@link DimensionalCarriageLayout#TWIN_LANE_HEIGHT} apart, so clearing the same distance vertically would
 * empty the lane above and below — the collision the lanes exist to prevent. What a player sees is a
 * flat void, and with the fog at the same distance its ceiling is never in view.</p>
 *
 * <h2>Why the tiling grid is X and Z but never Y</h2>
 * <p>Portal pairs are spread over Y lanes {@link DimensionalCarriageLayout#TWIN_LANE_HEIGHT} apart, and
 * {@code PortalCarriageBuilder.eraseTwin} sweeps one row past a structure's top. Vertical repetition
 * would reach into the lane above, which is the collision the lanes exist to prevent. Keeping the
 * grid horizontal makes that safe by construction rather than by a check that could drift.</p>
 *
 * <h2>The corridors sit inside the endless space, not at its edge</h2>
 * <p>Copies of the room tile straight through the row the two twin corridors stand in, so the way
 * back to the train is an object standing in the endless room rather than a wall bounding it. Each
 * twin is placed once, when the structure is built, and every copy stamped afterwards is masked off
 * the volume the corridors own — see {@code PortalCorridorMask}.</p>
 *
 * <p>What must not happen is the tiling moving a twin. {@link PortalStructure}'s javadoc is the
 * warning: {@code spanX} is the single source for where the exit twin is stamped, how far
 * {@code eraseTwin} reaches, the occupancy box, <b>and the origin the EXIT role's
 * {@code PortalFrames} maps into</b>. It stays the corridor span and is blind to the tiling, so no
 * number of copies can shift the frame a player is standing in, under them.</p>
 */
public enum DimensionalCarriageMode {

    /** Sealed in unbreakable rock. The default when a variant says nothing. */
    BEDROCK_LOCK("bedrock_lock", "Bedrock Lock"),

    /** The room repeats around itself, forever, walls between copies carved open. */
    ENDLESS_REPETITION("endless_repetition", "Endless Repetition"),

    /** Only the floor and ceiling repeat — the walls are open. */
    ENDLESS_OPEN("endless_open", "Endless Open"),

    /** Sealed in nothing at all: no skin, no repetition, and empty space out to the clearance. */
    BEDROCKLESS("bedrockless", "Bedrockless");

    /** What a variant with no mode tag — or an unreadable one — behaves as. */
    public static final DimensionalCarriageMode DEFAULT = BEDROCK_LOCK;

    private final String id;
    private final String displayName;

    DimensionalCarriageMode(String id, String displayName) {
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
     * True when the room hides its own boundary behind fog.
     *
     * <p>Named for what it decides rather than derived from {@link #tiles}, which is what the fog used
     * to be gated on. That read correctly only while tiling and fogging happened to coincide:
     * {@link #BEDROCKLESS} repeats nothing and still has an edge worth hiding, and a mode added later
     * would inherit whichever of the two it did not mean. Both questions get asked by name now.</p>
     */
    public boolean fogs() {
        return tiles() || this == BEDROCKLESS;
    }

    /**
     * True when the room is stamped into a swept clearance rather than into whatever it landed in —
     * {@link #BEDROCKLESS} alone.
     *
     * <p>Deliberately not the complement of {@link #BEDROCK_LOCK}'s skin: the tiling modes write
     * neither, because their boundary is the next copy of the room.</p>
     */
    public boolean clearsSurroundings() {
        return this == BEDROCKLESS;
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
     * True when the Exits control applies at all — only a room that repeats has anywhere to put an
     * extra way back to the train.
     *
     * <p>Both tiling modes, unlike {@code DimensionalCarriageSettings.copiesApply}, which is
     * {@link #tilesWholeRoom} — what Copies describes is what an appended tile <i>contains</i>, which
     * {@link #ENDLESS_OPEN} decides for itself. Getting lost is not a property of the walls, so a
     * question about the way out is asked of any endless room.</p>
     */
    public boolean exitsApply() {
        return tiles();
    }

    /**
     * What a room of this mode does about extra corridors when its variant says nothing.
     *
     * <p>{@link #ENDLESS_REPETITION} is a hall of identical rooms, which is the shape a player gets
     * lost in, so it lays them by default. {@link #ENDLESS_OPEN} has sightlines across it — the way
     * back stays visible from a long way off — so a corridor standing in the middle of the plain is
     * furniture nobody asked for, and it defaults to none.</p>
     *
     * <p>Answered here rather than in {@link DimensionalCarriageExits} because it is a statement about what a
     * <i>mode</i> wants, the same way {@link #closesOuterFaces} and {@link #fogs} are.</p>
     */
    public DimensionalCarriageExits defaultExits() {
        return this == ENDLESS_REPETITION ? DimensionalCarriageExits.ON : DimensionalCarriageExits.OFF;
    }

    /**
     * The mode named by {@code id}, or {@link #DEFAULT} when it is null, blank or unrecognised.
     *
     * <p>Deliberately total rather than throwing. The tag is free text on disk, and a room whose mode
     * was hand-edited to something misspelt should stamp as a normal sealed room, not fail the whole
     * pair's stamp and leave a player walking into an unbuilt corridor.</p>
     */
    public static DimensionalCarriageMode parse(String id) {
        if (id == null) return DEFAULT;
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return DEFAULT;
        for (DimensionalCarriageMode m : values()) {
            if (m.id.equals(key)) return m;
        }
        return DEFAULT;
    }

    /** The mode after this one, wrapping — what the editor's cycle button steps through. */
    public DimensionalCarriageMode next() {
        DimensionalCarriageMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
