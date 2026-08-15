package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * Where a portal's pocket room sits and how big it is — the pure geometry behind both the built-in
 * room and the authored {@code dimensional_carriage} template.
 *
 * <p><b>Not {@link PortalCarriageLayout}.</b> That one is the <i>train-side</i> carriage holding
 * the portal doorway, fixed to {@code CarriageDims}. This one is the space behind the doorway: off
 * the train, free-size above a floor, and the thing the player actually walks the length of.</p>
 *
 * <p><b>Free above a floor, on every axis.</b> An authored room may be any size the author likes,
 * subject to two limits per axis:</p>
 *
 * <ul>
 *   <li><b>Width and height have a floor.</b> The room must be at least as wide and tall as the
 *       corridor mouth that opens into it, or {@code sealCorridorMouth} cannot close the ring around
 *       that mouth and the twin structure opens into the surrounding rock. Growing past the floor is
 *       free — {@code structureBox}'s slack and {@code eraseTwin}'s bounds are both derived from the
 *       live room size, not from a constant.</li>
 *   <li><b>Height has a ceiling too.</b> Portal pairs are spread over Y lanes
 *       {@link #TWIN_LANE_HEIGHT} apart; a room taller than that reaches into the next pair's lane,
 *       which is the collision the lanes exist to prevent.</li>
 *   <li><b>Length has no floor beyond legibility.</b> It is the distance a player walks underneath,
 *       and the whole point of the portal is that this differs from the two carriages the same walk
 *       covers on the train.</li>
 * </ul>
 *
 * <p><b>Openings are on the walkway centre line.</b> {@link #roomOrigin} centres the room on
 * {@link PortalCarriageLayout#doorZ()}, which is the same line the corridor's doorways sit on. A
 * room authored with its own internal walls across that line would open onto a wall.</p>
 *
 * <p><b>Nothing here assumes one room per structure.</b> Sizes and origins are values, and
 * {@code PortalCarriageBuilder.stampRoomAt} takes an explicit origin — a second room alongside the
 * first is another call at {@code roomOrigin.offset(0, 0, ±width)}, not a rewrite.</p>
 */
public final class DimensionalCarriageLayout {

    /** Length of the built-in room, used when no template has been authored. */
    public static final int BUILT_IN_LENGTH = 11;

    /** Interior width of the built-in room — the shell adds one wall on each side. */
    private static final int BUILT_IN_INTERIOR_WIDTH = 11;

    /** Interior height of the built-in room — the shell adds the floor row and the ceiling row. */
    private static final int BUILT_IN_INTERIOR_HEIGHT = 5;

    /**
     * Vertical spacing between the Y lanes portal pairs are spread over.
     *
     * <p>Lives here rather than with the tick loop that applies it because it is really a statement
     * about how tall a structure may be, and that is room geometry: {@link #MAX_HEIGHT} is derived
     * from it so the two cannot drift apart.</p>
     *
     * <p>Every structure used to be stamped at the same height, and two pairs were observed landing
     * four blocks apart — near-total overlap, each overwriting the other's corridor so neither
     * matched its carriage any more. Lanes make a collision need both the same lane and overlapping
     * X. Lanes go in Y rather than Z deliberately: the loading guarantee is that a twin sits in its
     * carriage's <b>chunk columns</b>, and Y is the one axis that cannot take it out of them.</p>
     */
    public static final int TWIN_LANE_HEIGHT = 12;

    /**
     * How far {@link DimensionalCarriageMode#BEDROCKLESS} sweeps the space around a room, in blocks, on each
     * horizontal axis.
     *
     * <p><b>Horizontal only.</b> There is no vertical counterpart and there must not be one:
     * {@link #TWIN_LANE_HEIGHT} is the whole distance to the next pair's structure, so a clearance of
     * this size in Y would delete it. A Bedrockless room's emptiness is a flat void the height of the
     * structure that sits in it, and the fog — drawn at this same distance — is what keeps its
     * ceiling out of view.</p>
     *
     * <p>Chosen against the fog rather than against the world: it is the radius
     * {@code PortalStructure.fogRadius} reports for the mode, so the space that was cleared and the
     * distance a player can see into it are one number. Raising it raises both.</p>
     */
    public static final int VOID_CLEARANCE = 50;

    /**
     * How far a player can see once they are all the way out at the edge of {@link #VOID_CLEARANCE},
     * in blocks — the far end of the ramp a {@link DimensionalCarriageMode#BEDROCKLESS} room fogs on.
     *
     * <p>The clearance used to be fogged flat: the same fifty blocks standing in the middle of the
     * room and standing forty-five blocks out in the void, so stepping off a beam into the emptiness
     * looked exactly like staying on it. The fog closes in with the distance instead, and this is
     * where it lands — close enough to be a whiteout, so the room a player walked away from is gone
     * and the void has no visible extent.</p>
     *
     * <p>Only the floor of the ramp, never a distance anything is built at: the sweep is still
     * {@link #VOID_CLEARANCE} on every axis. Lowering this thickens the far end of the walk without
     * moving a single block.</p>
     */
    public static final int VOID_FOG_MIN = 8;

    /**
     * Shortest room worth authoring. Below this the two corridor mouths are close enough that the
     * far one is visible from the near one, which is the one thing the baffles exist to prevent.
     */
    public static final int MIN_LENGTH = 5;

    /**
     * Shortest room that is still a room: floor, two blocks of headroom for a door, ceiling. The
     * same number and the same reasoning as {@link PortalCarriageLayout#MIN_HEIGHT}.
     *
     * <p>This is a constant floor under {@link #minHeight}, not the whole rule — the corridor's own
     * height still raises it whenever the corridor is taller, which at the default dims it is. What
     * this guards is the short end: {@link CarriageDims#MIN_HEIGHT} is 3, and a 3-block room is a
     * floor and a ceiling with nothing between them.</p>
     */
    public static final int MIN_HEIGHT = 4;

    /**
     * Longest room. Not a technical ceiling — the structure is free-standing at the world floor —
     * but a room longer than this walks further than the crossing reads as, and every block of it
     * is re-stamped each time the train drifts {@code TWIN_MAX_DRIFT}.
     */
    public static final int MAX_LENGTH = 48;

    /** Widest room, on the same reasoning as {@link #MAX_LENGTH}. */
    public static final int MAX_WIDTH = 48;

    /**
     * Tallest room: one block clear of the lane above.
     *
     * <p>{@code eraseTwin} sweeps one row past the structure's top, so a room of exactly
     * {@link #TWIN_LANE_HEIGHT} would erase the floor of the next lane's structure.</p>
     */
    public static final int MAX_HEIGHT = TWIN_LANE_HEIGHT - 1;

    private DimensionalCarriageLayout() {}

    /**
     * The built-in room's full box, shell included.
     *
     * <p>Its width and height are its own — {@link #BUILT_IN_INTERIOR_WIDTH} and
     * {@link #BUILT_IN_INTERIOR_HEIGHT} plus a shell row each side, each held up to the matching
     * validation floor for a world whose carriages are bigger than the built-in room ever was.
     * Deliberately not just {@link #minWidth}/{@link #minHeight}: this is also the footprint
     * {@link games.brennan.dungeontrain.track.variant.TrackKind#dims} reports for
     * {@code DIMENSIONAL_CARRIAGE}, and {@code TrackSidePlots.slotZ} uses that as the editor's plot-slot base
     * — so tying it to the validation floor would re-pack every track-side editor row the moment
     * that floor moved.</p>
     *
     * <p>The height {@code max} is load-bearing for exactly that reason. {@link #minHeight} floors
     * at {@link #MIN_HEIGHT} (4), so in a world with short carriages it drops below the built-in
     * room's own 7 — and without this the built-in shell would follow it down and stamp a box too
     * short for the {@link #BUILT_IN_INTERIOR_HEIGHT} interior it is made of.</p>
     */
    public static Vec3i builtInSize(CarriageDims dims) {
        return new Vec3i(BUILT_IN_LENGTH,
            Math.max(BUILT_IN_INTERIOR_HEIGHT + 2, minHeight(dims)),
            Math.max(BUILT_IN_INTERIOR_WIDTH + 2, minWidth(dims)));
    }

    /**
     * The floor an authored room is validated against, as a box — {@link #MIN_LENGTH} by
     * {@link #minHeight} by {@link #minWidth}.
     *
     * <p>Separate from {@link #builtInSize} on purpose. {@code TrackVariantStore.boundsMatch} used to
     * validate an authored template against the built-in room's footprint, which quietly made the
     * built-in room's width the minimum every authored room had to clear — a room could be rejected
     * for being narrower than a shell it has nothing to do with.</p>
     */
    public static Vec3i minSize(CarriageDims dims) {
        return new Vec3i(MIN_LENGTH, minHeight(dims), minWidth(dims));
    }

    /** A room box of {@code length}, at the minimum width and height. */
    public static Vec3i sizeOfLength(CarriageDims dims, int length) {
        return clampSize(dims, new Vec3i(length, minHeight(dims), minWidth(dims)));
    }

    /** {@code wanted}, held inside the floors and ceilings this world's corridor allows. */
    public static Vec3i clampSize(CarriageDims dims, Vec3i wanted) {
        return new Vec3i(
            clampLength(wanted.getX()),
            clampHeight(dims, wanted.getY()),
            clampWidth(dims, wanted.getZ()));
    }

    /**
     * Smallest legal full room height — the taller of {@link #MIN_HEIGHT} and the corridor. At the
     * default {@code CarriageDims(9,7,7)} that is 7, the value the room was hardcoded at.
     *
     * <p>The {@code max} against {@code dims.height()} is what keeps the room's ceiling at or above
     * the corridor's: a corridor poking out through the room's ceiling would leave the mouth
     * unsealed and open the twin structure to the rock above it. So the corridor, not
     * {@link #MIN_HEIGHT}, is what binds at any ordinary carriage height — {@link #MIN_HEIGHT} only
     * takes over below 4, where a room would otherwise have no interior at all.</p>
     *
     * <p>This used to floor at {@code BUILT_IN_INTERIOR_HEIGHT + 2} (7), which was the built-in
     * room's own height leaking into the validation floor rather than anything the seal needed —
     * the same mistake {@link #minWidth} used to make. {@link #builtInSize} applies that 7 itself
     * now, so the built-in room is unchanged.</p>
     */
    public static int minHeight(CarriageDims dims) {
        return Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, dims.height()));
    }

    /**
     * Smallest legal full room width — 11 at the default dims.
     *
     * <p>The constraint is coverage rather than taste. {@code sealCorridorMouth} walls off the door
     * plane by sweeping the <b>room's</b> Z span and skipping the corridor's cross-section, so a
     * corridor column outside that span is never sealed and never covered either — it opens the twin
     * structure onto the surrounding rock. Against {@link #roomOrigin}'s centring,
     * {@code dims.width() + 2} is exactly what holds the containment: the room's <b>interior</b>
     * ({@code width - 2}, centred on the corridor's doorway line) then spans the corridor's full Z
     * extent, at every legal {@link CarriageDims#width()}, odd or even. One block narrower and a
     * corridor column falls outside the room — which is the failure this floor exists to prevent.</p>
     *
     * <p>There is no margin in that number, and deliberately so: it is the geometric bound, not a
     * comfortable distance from it. It was {@code + 4} — one spare block of room either side — which
     * was taste rather than a requirement, and cost authors two blocks of width they could have had.
     * The sweep in {@code DimensionalCarriageLayoutTest} checks the containment holds at exactly
     * {@code minWidth} for every legal carriage width, so a future change to {@link #roomOrigin}'s
     * centring cannot quietly invalidate it.</p>
     *
     * <p>This also used to floor at {@code BUILT_IN_INTERIOR_WIDTH + 2} (13 at the default dims),
     * which was the built-in room's own width leaking into the validation floor rather than anything
     * the seal needed. {@link #builtInSize} still applies it, so the built-in room is unchanged, but
     * an authored room narrower than the built-in shell is legal now — because nothing breaks when it
     * is.</p>
     */
    public static int minWidth(CarriageDims dims) {
        return Math.min(MAX_WIDTH, dims.width() + 2);
    }

    /** Clamp an authored length into {@link #MIN_LENGTH}..{@link #MAX_LENGTH}. */
    public static int clampLength(int length) {
        return Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));
    }

    /** Clamp an authored height into this world's legal band. */
    public static int clampHeight(CarriageDims dims, int height) {
        return Math.max(minHeight(dims), Math.min(MAX_HEIGHT, height));
    }

    /** Clamp an authored width into this world's legal band. */
    public static int clampWidth(CarriageDims dims, int width) {
        return Math.max(minWidth(dims), Math.min(MAX_WIDTH, width));
    }

    /**
     * Minimum corner of the room box for a structure whose entry twin is at {@code entryOrigin} —
     * one corridor along {@code +X}, and centred on the corridor's doorway line in {@code Z}.
     */
    public static BlockPos roomOrigin(BlockPos entryOrigin, CarriageDims dims,
                                      PortalCarriageLayout layout, int width) {
        int interiorWidth = width - 2;
        int zCentre = entryOrigin.getZ() + layout.doorZ();
        int interiorMinZ = zCentre - interiorWidth / 2;
        // One CORRIDOR along, not one carriage — a corridor is the longer of the two
        // (PortalCorridorSize), and the room has to start where the corridor actually ends.
        return new BlockPos(
            entryOrigin.getX() + layout.length(),
            entryOrigin.getY(),
            interiorMinZ - 1);
    }
}
