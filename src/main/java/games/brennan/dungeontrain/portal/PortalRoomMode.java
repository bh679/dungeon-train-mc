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
 *   <li>{@link #BEDROCK_LOCK} — a one-block bedrock skin outside the whole structure: the room box,
 *       the two corridors standing off its ends, and the plugs beyond their outer doors. No
 *       repetition. The default, and the closest to how every room behaved before this existed.</li>
 *   <li>{@link #ENDLESS_REPETITION} — the whole room repeats as a grid of copies around the base
 *       tile, seams between neighbours carved open, tiles appended as the player approaches an
 *       edge.</li>
 *   <li>{@link #ENDLESS_OPEN} — the same grid, but only the floor and ceiling planes repeat. No side
 *       walls at all, so it reads as an open plain rather than a hall of rooms. Those two planes are
 *       still rolled per tile or once for the whole grid as {@link PortalRoomCopies} says — see
 *       {@link #copiesApply}.</li>
 *   <li>{@link #BEDROCKLESS} — {@link #BEDROCK_LOCK} with the bedrock taken away: no repetition, and
 *       nothing at all around the room for {@link PortalRoomLayout#VOID_CLEARANCE} blocks.</li>
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
 * <p>The clearance is horizontal only. Pairs are spread over Y lanes only
 * {@link PortalTwinLanes#laneHeight} apart — one block more than the structure itself — so clearing
 * the same distance vertically would empty the lane above and below — the collision the lanes exist to prevent. What a player sees is a
 * flat void, and with the fog at the same distance its ceiling is never in view.</p>
 *
 * <h2>Why the tiling grid is X and Z but never Y</h2>
 * <p>Portal pairs are spread over Y lanes {@link PortalTwinLanes#laneHeight} apart — one block more
 * than the structure itself — and {@code PortalCarriageBuilder.eraseTwin} sweeps one row past a
 * structure's top. Vertical repetition
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
public enum PortalRoomMode {

    /** Sealed in unbreakable rock. The default when a variant says nothing. */
    BEDROCK_LOCK("bedrock_lock", "Bedrock Lock"),

    /** The room repeats around itself, forever, walls between copies carved open. */
    ENDLESS_REPETITION("endless_repetition", "Endless Repetition"),

    /** Only the floor and ceiling repeat — the walls are open. */
    ENDLESS_OPEN("endless_open", "Endless Open"),

    /** Sealed in nothing at all: no skin, no repetition, and empty space out to the clearance. */
    BEDROCKLESS("bedrockless", "Bedrockless"),

    /**
     * A single chunk of ordinary world generation, sealed in bedrock, with its surface on the
     * corridor doors — see {@link PortalChunkTerrain}.
     *
     * <p>The one mode whose room is not something a person built. The variant's template is still
     * stamped, and is what a player sees until the sample lands and if it never does, but what fills
     * the box is a 16-block cube of Overworld, Nether or End terrain, rolled per pair.</p>
     */
    CHUNK_DIMENSION("chunk_dimension", "Chunk Dimension");

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
     * True when the two corridors standing off the room's ±X ends are sealed along with it — a
     * bedrock skin around each, and bedrock rather than ordinary rock in the plug behind its dead
     * outer door.
     *
     * <p>The room's own skin stops at those ends, because they are the corridor door planes. So a
     * room "sealed in unbreakable rock" used to have two corridors of ordinary stone brick hanging
     * off it, each backed by three blocks of deepslate — a player could mine sideways through a
     * corridor wall, or straight through the plug, and walk out of the lock. The whole structure is
     * wrapped now, not the room alone.</p>
     *
     * <p>A predicate of its own rather than the complement of {@link #clearsSurroundings}: the two
     * endless modes seal nothing at all — their boundary is the next copy of the room — and their
     * corridors stay as they are.</p>
     */
    public boolean sealsCorridors() {
        return sealsRoomBox();
    }

    /**
     * True when the room's own box is wrapped in a block of bedrock.
     *
     * <p>{@link #BEDROCK_LOCK} and {@link #CHUNK_DIMENSION}. The second wraps for a reason of its
     * own rather than by inheriting the first's: a chunk dimension is a slice of terrain with no
     * walls of its own worth speaking of — a sampled hillside runs straight into the box's faces —
     * so what stops a player digging out of it and into the basement rock is the skin, and nothing
     * else. Asked as a question of its own so a later mode that seals its corridors without sealing
     * its box, or the other way round, has somewhere to say so.</p>
     */
    public boolean sealsRoomBox() {
        return this == BEDROCK_LOCK || this == CHUNK_DIMENSION;
    }

    /**
     * True when the room's interior is generated rather than authored — {@link #CHUNK_DIMENSION}
     * alone. The flag {@code stampPairStructure} branches on to pour a sampled chunk into the box
     * once the template is down.
     */
    public boolean generatesTerrain() {
        return this == CHUNK_DIMENSION;
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
     * True when the Copies control applies at all — either mode that appends tiles.
     *
     * <p>{@link #tiles} and deliberately not {@link #tilesWholeRoom}, which is what this used to be
     * gated on. The two answer different questions: {@code tilesWholeRoom} is what an appended tile
     * <i>contains</i>, while Copies is how whatever it contains is <i>rolled</i>. An
     * {@link #ENDLESS_OPEN} tile contains only the floor and the ceiling — and those cells go through
     * the variant sidecar exactly as any other cell does, so whether they reroll per tile is a live
     * question for it too. Gated on the narrower predicate, an open plain could only ever repeat one
     * floor out to the fog, with no way for an author to say otherwise.</p>
     */
    public boolean copiesApply() {
        return tiles();
    }

    /**
     * True when {@link PortalRoomCopies.Kind#SINGLE} is one of the answers this mode can give.
     *
     * <p>{@link #ENDLESS_OPEN} alone. Single replaces an appended tile's floor and ceiling with one
     * block, and floor-and-ceiling is the whole of what an Endless Open tile is. An
     * {@link #ENDLESS_REPETITION} tile is a whole room — walls, furnishing, chests — so the same
     * setting there would append solid cubes of stone to the end of a hall, which is not a room and
     * not somewhere a player can walk.</p>
     *
     * <p>Narrower than {@link #copiesApply} on purpose, and asked separately rather than folded into
     * it: a Repetition room is still asked whether its copies are Exact or Dynamic. What differs is
     * only which answers are on the list.</p>
     */
    public boolean singleCopiesApply() {
        return this == ENDLESS_OPEN;
    }

    /**
     * True when the Exits control applies at all — only a room that repeats has anywhere to put an
     * extra way back to the train.
     *
     * <p>Both tiling modes, the same set {@link #copiesApply} covers. Getting lost is not a property
     * of the walls, so a question about the way out is asked of any endless room.</p>
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
     * <p>Answered here rather than in {@link PortalRoomExits} because it is a statement about what a
     * <i>mode</i> wants, the same way {@link #closesOuterFaces} and {@link #fogs} are.</p>
     */
    public PortalRoomExits defaultExits() {
        return this == ENDLESS_REPETITION ? PortalRoomExits.ON : PortalRoomExits.OFF;
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
