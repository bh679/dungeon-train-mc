package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * Where a portal's pocket room sits and how big it is — the pure geometry behind both the built-in
 * room and the authored {@code portal_room} template.
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
public final class PortalRoomLayout {

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
     * Shortest room worth authoring. Below this the two corridor mouths are close enough that the
     * far one is visible from the near one, which is the one thing the baffles exist to prevent.
     */
    public static final int MIN_LENGTH = 5;

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

    private PortalRoomLayout() {}

    /**
     * The built-in room's full box, shell included.
     *
     * <p>Its width is its own — {@link #BUILT_IN_INTERIOR_WIDTH} plus a wall each side, held up to
     * {@link #minWidth} for a world whose carriages are wider than the built-in room ever was.
     * Deliberately not just {@link #minWidth}: this is also the footprint
     * {@link games.brennan.dungeontrain.track.variant.TrackKind#dims} reports for
     * {@code PORTAL_ROOM}, and {@code TrackSidePlots.slotZ} uses that as the editor's plot-slot base
     * — so tying it to the validation floor would re-pack every track-side editor row the moment
     * that floor moved.</p>
     */
    public static Vec3i builtInSize(CarriageDims dims) {
        return new Vec3i(BUILT_IN_LENGTH, minHeight(dims),
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
     * Smallest legal full room height. At the default {@code CarriageDims(9,7,7)} this is 7, the
     * value the room was hardcoded at. The {@code max} matters for a world whose carriages are
     * taller than that: a corridor poking out through the room's ceiling would leave the mouth
     * unsealed and open the twin structure to the rock above it.
     */
    public static int minHeight(CarriageDims dims) {
        return Math.min(MAX_HEIGHT, Math.max(BUILT_IN_INTERIOR_HEIGHT + 2, dims.height()));
    }

    /**
     * Smallest legal full room width — 11 at the default dims.
     *
     * <p>The constraint is coverage rather than taste. {@code sealCorridorMouth} walls off the door
     * plane by sweeping the <b>room's</b> Z span and skipping the corridor's cross-section, so a
     * corridor column outside that span is never sealed and never covered either — it opens the twin
     * structure onto the surrounding rock. Against {@link #roomOrigin}'s centring,
     * {@code dims.width() + 4} is what holds the containment: it leaves exactly two blocks of room
     * either side of the corridor — one wall, one interior — at every legal
     * {@link CarriageDims#width()}, odd or even.</p>
     *
     * <p>This also used to floor at {@code BUILT_IN_INTERIOR_WIDTH + 2} (13 at the default dims),
     * which was the built-in room's own width leaking into the validation floor rather than anything
     * the seal needed. {@link #builtInSize} still applies it, so the built-in room is unchanged, but
     * an authored room narrower than the built-in shell is legal now — because nothing breaks when it
     * is.</p>
     */
    public static int minWidth(CarriageDims dims) {
        return Math.min(MAX_WIDTH, dims.width() + 4);
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
