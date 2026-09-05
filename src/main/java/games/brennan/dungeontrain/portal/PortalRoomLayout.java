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
 *   <li><b>Height has a ceiling too.</b> {@link #MAX_HEIGHT} is what an author may ask for; what a
 *       given world can actually stand up is decided by
 *       {@link PortalTwinLanes#maxStructureHeight}, because a twin structure has to fit between the
 *       basement floor and the bedrock. Portal pairs are spread over Y lanes sized to the room
 *       itself — see {@link PortalTwinLanes#laneHeight} — so a taller room costs lanes rather than
 *       being refused.</li>
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
     * How far {@link PortalRoomMode#BEDROCKLESS} sweeps the space around a room, in blocks, on each
     * horizontal axis.
     *
     * <p><b>Horizontal only.</b> There is no vertical counterpart and there must not be one:
     * {@link PortalTwinLanes#laneHeight} is the whole distance to the next pair's structure, and it
     * is only one block more than the structure's own height, so a clearance of this size in Y would
     * delete it. A Bedrockless room's emptiness is a flat void the height of the structure that sits
     * in it, and the fog — drawn at this same distance — is what keeps its ceiling out of view.</p>
     *
     * <p>Chosen against the fog rather than against the world: it is the radius
     * {@code PortalStructure.fogRadius} reports for the mode, so the space that was cleared and the
     * distance a player can see into it are one number. Raising it raises both.</p>
     */
    public static final int VOID_CLEARANCE = 50;

    /**
     * How far a player can see once they are all the way out at the edge of {@link #VOID_CLEARANCE},
     * in blocks — the far end of the ramp a {@link PortalRoomMode#BEDROCKLESS} room fogs on.
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
    public static final int MAX_LENGTH = 64;

    /** Widest room, on the same reasoning as {@link #MAX_LENGTH}. */
    public static final int MAX_WIDTH = 64;

    /**
     * Tallest room a template may hold and a world will stand up.
     *
     * <p>The world's number as much as the format's: a twin structure lives in the basement between
     * the build floor and the bedrock, every DT preset keeps 96 blocks of it, the bottom lane's floor
     * sits {@link PortalTwinLanes#FLOOR_MARGIN} above the build floor and one row stays under the
     * bedrock — so {@link PortalTwinLanes#maxStructureHeight} answers 93, and 90 fits with room to
     * spare. The basement used to be 80 (and this 80), which stood up 77: an author's eighty-block
     * sky room lost its ceiling to bedrock the first time it was tested. The basement was deepened
     * rather than the room shortened, because rooms already built to this height are the reason
     * the number exists.</p>
     *
     * <p>What a template already is, not what an author may grow one to — see {@link #AUTHOR_MAX}.</p>
     */
    public static final int MAX_HEIGHT = 90;

    /**
     * The tallest, longest and widest an author may <em>grow</em> a room to, on any axis.
     *
     * <p>Lower than {@link #MAX_HEIGHT}, {@link #MAX_LENGTH} and {@link #MAX_WIDTH}, which say what a
     * template may hold: a room built past this before the ceiling existed keeps every block it has,
     * and simply cannot be made bigger. {@link #heldForAuthoring} is the rule.</p>
     */
    public static final int AUTHOR_MAX = 64;

    /**
     * {@code wanted} with each axis held to the most an author may grow {@code current} to.
     *
     * <p>Per axis, the ceiling is {@link #AUTHOR_MAX} or the room's present size, whichever is
     * larger: a room already past the ceiling on some axis is never shrunk by asking for more, and
     * never grown on that axis either. Shrinking is always allowed — down to whatever
     * {@link #clampSize} permits, which the caller applies.</p>
     */
    public static Vec3i heldForAuthoring(Vec3i current, Vec3i wanted) {
        return new Vec3i(
            Math.min(wanted.getX(), Math.max(AUTHOR_MAX, current.getX())),
            Math.min(wanted.getY(), Math.max(AUTHOR_MAX, current.getY())),
            Math.min(wanted.getZ(), Math.max(AUTHOR_MAX, current.getZ())));
    }

    private PortalRoomLayout() {}

    /**
     * The built-in room's full box, shell included.
     *
     * <p>Its width and height are its own — {@link #BUILT_IN_INTERIOR_WIDTH} and
     * {@link #BUILT_IN_INTERIOR_HEIGHT} plus a shell row each side, each held up to the matching
     * validation floor for a world whose carriages are bigger than the built-in room ever was.
     * Deliberately not just {@link #minWidth}/{@link #minHeight}: this is also the footprint
     * {@link games.brennan.dungeontrain.track.variant.TrackKind#dims} reports for
     * {@code PORTAL_ROOM}, and {@code TrackSidePlots.slotZ} uses that as the editor's plot-slot base
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
     * The sweep in {@code PortalRoomLayoutTest} checks the containment holds at exactly
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
        return roomOrigin(entryOrigin, dims, layout, width, 0);
    }

    /**
     * As {@link #roomOrigin(BlockPos, CarriageDims, PortalCarriageLayout, int)}, with the doorway
     * line shifted {@code doorOffset} blocks off dead centre within the room's own width.
     *
     * <p>The corridor itself never moves — its cross-section is fixed by {@code dims} and shared by
     * every carriage in the world, so {@code zCentre} below is exactly what the undisplaced overload
     * uses. What moves is how the room's width is <b>split</b> either side of that fixed line: an
     * author who has built wider than {@link #minWidth} has slack to spend
     * ({@code width - minWidth(dims)}), and {@code doorOffset} spends it unevenly instead of the
     * default 50/50 split — which is what makes the door read as off-centre from inside the room
     * the author actually built, even though the corridor beyond it never bends.</p>
     *
     * <p>Clamped rather than rejected, via {@link #clampDoorOffset}, for the same reason every other
     * numeric portal-room setting is: a room saved wide and later trimmed narrower just eases back
     * toward centre instead of becoming invalid.</p>
     */
    public static BlockPos roomOrigin(BlockPos entryOrigin, CarriageDims dims,
                                      PortalCarriageLayout layout, int width, int doorOffset) {
        int interiorWidth = width - 2;
        int zCentre = entryOrigin.getZ() + layout.doorZ();
        int interiorMinZ = zCentre - interiorWidth / 2 - clampDoorOffset(dims, width, doorOffset);
        // One CORRIDOR along, not one carriage — a corridor is the longer of the two
        // (PortalCorridorSize), and the room has to start where the corridor actually ends.
        return new BlockPos(
            entryOrigin.getX() + layout.length(),
            entryOrigin.getY(),
            interiorMinZ - 1);
    }

    /**
     * How far {@link #roomOrigin}'s door-offset may run to one side of centre, for a room of
     * {@code width} in a world of {@code dims} — half the room's slack over {@link #minWidth}.
     *
     * <p>Zero at {@link #minWidth} itself: a room built at the geometric floor has no spare width to
     * give the door either way, so it stays dead centre regardless of what was authored. The wider a
     * room is built, the more room there is to slide the door toward either end.</p>
     */
    public static int maxDoorOffset(CarriageDims dims, int width) {
        int slack = width - minWidth(dims);
        return Math.max(0, slack) / 2;
    }

    /** {@code doorOffset} held inside {@code -}{@link #maxDoorOffset}..{@code +}{@link #maxDoorOffset}. */
    public static int clampDoorOffset(CarriageDims dims, int width, int doorOffset) {
        int max = maxDoorOffset(dims, width);
        return Math.max(-max, Math.min(max, doorOffset));
    }

    /**
     * As {@link #roomOrigin(BlockPos, CarriageDims, PortalCarriageLayout, int, int)}, additionally
     * shifting the corridor's fixed floor line {@code doorHeightOffset} blocks up from the room's own
     * bottom edge.
     *
     * <p>Unlike the Z split, this is not a centring — every room's corridor sits at the room's own
     * floor by default (offset 0), because that is the only Y a room built before this existed ever
     * used, and there is nothing below a floor to give the offset the other way. A room taller than
     * {@link #minHeight} has an attic above the corridor to spend; {@code doorHeightOffset} spends it
     * as a basement below the corridor instead, one block at a time, up to the full slack — see
     * {@link #maxDoorHeightOffset}.</p>
     *
     * <p>The corridor's own Y is exactly as fixed as its Z: {@code entryOrigin.getY()} is where
     * {@link PortalTwinLanes} placed this pair's lane, shared by nothing else, so it never moves —
     * what moves is how far below it the room's own floor sits.</p>
     */
    public static BlockPos roomOrigin(BlockPos entryOrigin, CarriageDims dims,
                                      PortalCarriageLayout layout, int width, int height,
                                      int doorOffset, int doorHeightOffset) {
        BlockPos flat = roomOrigin(entryOrigin, dims, layout, width, doorOffset);
        int clampedHeightOffset = clampDoorHeightOffset(dims, height, doorHeightOffset);
        return flat.atY(flat.getY() - clampedHeightOffset);
    }

    /**
     * How far {@link #roomOrigin}'s door-height-offset may run above the room's own floor, for a room
     * of {@code height} in a world of {@code dims} — the room's whole slack over {@link #minHeight},
     * unhalved because it only ever spends in one direction.
     *
     * <p>Zero at {@link #minHeight} itself, for the same reason {@link #maxDoorOffset} is zero at
     * {@link #minWidth}: no spare height to give the corridor a basement, so it stays at the floor
     * regardless of what was authored.</p>
     */
    public static int maxDoorHeightOffset(CarriageDims dims, int height) {
        int slack = height - minHeight(dims);
        return Math.max(0, slack);
    }

    /** {@code doorHeightOffset} held inside {@code 0}..{@link #maxDoorHeightOffset}. */
    public static int clampDoorHeightOffset(CarriageDims dims, int height, int doorHeightOffset) {
        int max = maxDoorHeightOffset(dims, height);
        return Math.max(0, Math.min(max, doorHeightOffset));
    }

    /**
     * How far the <b>exit</b> corridor stands off the entry corridor's line in {@code Z}, in blocks —
     * the whole of what makes a room's two doorways independent.
     *
     * <p><b>Why a delta and not a second centring.</b> {@link #roomOrigin} spends a room's width slack
     * to place the box, and it can only be placed once: a box has one position, not two. So the
     * <i>entry</i> door is what positions it, exactly as it always did, and the exit door is expressed
     * as a displacement of its own corridor <i>within</i> that box. Same two degrees of freedom,
     * redistributed — and a room whose two doors agree gets a delta of zero, which is why nothing an
     * existing world is standing in moves by so much as a block.</p>
     *
     * <p>Both offsets go through {@link #clampDoorOffset} <b>before</b> subtracting, never after: the
     * clamp is what guarantees each corridor's cross-section stays inside the room, and clamping their
     * difference instead would let a pair of individually-legal doors produce a corridor outside the
     * box that {@code sealCorridorMouth} then cannot seal.</p>
     */
    public static int exitDoorDeltaZ(CarriageDims dims, int width, int doorOffset,
                                     int exitDoorOffset) {
        return clampDoorOffset(dims, width, exitDoorOffset)
            - clampDoorOffset(dims, width, doorOffset);
    }

    /**
     * The vertical twin of {@link #exitDoorDeltaZ} — how far the exit corridor sits above or below the
     * entry corridor's floor lane, in blocks.
     *
     * <p>Signed, unlike {@link PortalRoomDoorHeightOffset} itself: each door is unsigned against the
     * room's own floor, but an exit door lower than the entry door displaces its corridor downward,
     * and that difference is a direction. Clamped per door first, for the reason
     * {@link #exitDoorDeltaZ} gives.</p>
     */
    public static int exitDoorDeltaY(CarriageDims dims, int height, int doorHeightOffset,
                                     int exitDoorHeightOffset) {
        return clampDoorHeightOffset(dims, height, exitDoorHeightOffset)
            - clampDoorHeightOffset(dims, height, doorHeightOffset);
    }
}
