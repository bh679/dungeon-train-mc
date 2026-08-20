package games.brennan.dungeontrain.portal;

/**
 * Which height a portal pair's twin structure is stamped at — the basement under the world, split
 * into Y lanes so two pairs cannot land on each other.
 *
 * <p><b>Under the bedrock, not merely at the bottom.</b> A Dungeon Train overworld's dimension type
 * runs {@code BASEMENT} blocks deeper than its terrain does, so there is empty world beneath the
 * bedrock layer that generation never touches and a player can never dig into. That is where twins
 * live. Before this they sat one block above the bedrock, carved out of the deepslate a player is
 * free to mine through — the structures were hidden by depth alone, and the bedrock skin
 * {@code PortalCarriageBuilder} wraps a room in was the only thing keeping a determined digger out
 * of the illusion.</p>
 *
 * <p><b>Why lanes.</b> Every structure was once stamped at the same height, at whatever X its entry
 * carriage happened to be at, with nothing checking whether another pair already occupied that
 * space. A structure is about 35 blocks long, and two pairs were observed stamping four blocks
 * apart — near-total overlap, each overwriting the other's corridor so neither matched its carriage
 * any more. Spreading pairs over {@link #MAX_LANES} heights makes a collision need both the same
 * lane and overlapping X.</p>
 *
 * <p><b>How many lanes is a question about the room and the dimension, not a constant.</b> Spacing
 * used to be a fixed twelve blocks, which capped an authored room at eleven — the ceiling was really
 * "whatever fits under the next lane". {@link #laneHeight} sizes a lane to the structure instead and
 * {@link #usableLanes} divides the basement the current dimension actually has by it, so the same
 * eighty blocks give a default room the six lanes it always had and a very tall room one. Height and
 * lanes are the two ends of one budget, and this is where they trade.</p>
 *
 * <p>Lanes go in Y rather than Z deliberately: the whole loading guarantee is that a twin sits in its
 * carriage's <b>chunk columns</b>, and Y is the one axis that cannot take it out of them.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap — same convention as
 * {@link PortalAnchors} and {@link PortalGeometry}.</p>
 */
public final class PortalTwinLanes {

    private PortalTwinLanes() {}

    /**
     * How far above the build floor the lowest lane's floor sits.
     *
     * <p><b>Two, so the lowest lane gets an underside like every other lane.</b> A structure's skin
     * writes one row <i>under</i> its floor — the Bedrock Lock underside — and
     * {@code PortalCarriageBuilder.lowestWritableY} clamps that row to {@code worldMinY + 1}, since
     * nothing can be placed lower. At a margin of one those two are the same row, the clamp swallows
     * the underside, and lane 0 alone ends up with a floor you can break through into open basement
     * and then out of the world. At two, the underside lands.</p>
     *
     * <p>It costs one block of a basement 80 deep, and {@link #usableLanes} accounts for it, so the
     * lane count is unchanged.</p>
     */
    public static final int FLOOR_MARGIN = 2;

    /** Distinct heights pairs are spread over, when the basement is deep enough to hold them all. */
    public static final int MAX_LANES = 6;

    /**
     * Vertical spacing between lanes for a structure of {@code structureHeight} — one block more than
     * the structure itself, so no part of one reaches into the lane above.
     *
     * <p>The spare row is not slack: {@code eraseTwin} sweeps one row past a structure's top, so a
     * spacing of exactly {@code structureHeight} would have each lane's erase take the floor out of
     * the lane above it.</p>
     */
    public static int laneHeight(int structureHeight) {
        return structureHeight + 1;
    }

    /**
     * The tallest structure this world's basement can stand up, in blocks.
     *
     * <p>The bottom lane's floor is {@link #floorY} and the structure has to stay strictly under the
     * bedrock ({@link #fitsUnderWorld}), so the basement's depth less the floor margin and that one
     * row is the whole of it — 77 in every stock DT preset, which keeps 80 blocks of basement.
     * Capped at {@link PortalRoomLayout#MAX_HEIGHT} so a deeper dimension does not exceed what an
     * author can express.</p>
     *
     * <p>A world with no basement — Compatible Terrain runs vanilla's dimension type, whose floor and
     * bedrock are the same row — has nothing to measure, so it gets the authoring ceiling and the
     * caller's other fit checks (clear of the train, clear of the build ceiling) decide alone, which
     * is exactly what those worlds did before the basement existed.</p>
     */
    public static int maxStructureHeight(int worldMinY, int bedrockY) {
        int floor = floorY(worldMinY);
        if (bedrockY <= floor) return PortalRoomLayout.MAX_HEIGHT;
        return Math.max(1, Math.min(PortalRoomLayout.MAX_HEIGHT, bedrockY - floor - 1));
    }

    /**
     * How many lanes a structure of {@code structureHeight} fits between the build floor and the
     * bedrock, capped at {@link #MAX_LANES}.
     *
     * <p>Measured against the <b>bedrock</b> rather than the train, so no lane can put a structure
     * up through the world's floor and into terrain a player is standing on. A world with no
     * basement — Compatible Terrain mode runs vanilla's dimension type, whose floor and bedrock are
     * the same row — yields one lane at the old height, which is exactly the behaviour those worlds
     * had before the basement existed.</p>
     *
     * <p>{@code structureHeight} is the tallest structure the <b>world</b> may stamp rather than the
     * one pair's own, because a lane is only a lane if every pair agrees where it is; see
     * {@code PortalRoomSizes.tallestKnown}.</p>
     */
    public static int usableLanes(int worldMinY, int bedrockY, int structureHeight) {
        int floor = floorY(worldMinY);
        // A lane is usable only if a full-height structure in it still clears the bedrock.
        int headroom = bedrockY - floor - structureHeight;
        if (headroom < 0) return 1;
        return Math.max(1, Math.min(MAX_LANES, headroom / laneHeight(structureHeight) + 1));
    }

    /** Floor of the lowest lane. */
    public static int floorY(int worldMinY) {
        return worldMinY + FLOOR_MARGIN;
    }

    /**
     * The floor height for a pair's structure.
     *
     * <p>{@code structureHeight} sizes the lanes — see {@link #usableLanes} for why it is the
     * world's tallest room rather than this pair's.</p>
     *
     * <p>The lane comes from the <b>group ordinal</b>, not the raw pair key. A pair is keyed on its
     * group's anchor, which is always a multiple of the group size — so keying the lane on it
     * directly would give every pair in the world the same remainder, land them all in lane 0, and
     * reinstate the overlapping-structures bug the lanes exist to prevent. Dividing first makes
     * consecutive portal groups take consecutive lanes, which is what the spread wants.</p>
     */
    public static int twinFloorY(int worldMinY, int bedrockY, int pairKey, int groupSize,
                                 int structureHeight) {
        long lane = Math.floorDiv((long) pairKey, Math.max(1, groupSize));
        int lanes = usableLanes(worldMinY, bedrockY, structureHeight);
        return floorY(worldMinY)
            + (int) Math.floorMod(lane, (long) lanes) * laneHeight(structureHeight);
    }

    /**
     * Whether a structure of {@code height} standing on {@code twinY} stays under the world.
     *
     * <p>In a world with no basement there is nothing to stay under — the twin is inside the terrain
     * either way, as it always was — so the check passes and the caller's remaining fit checks
     * (clear of the train, clear of the build ceiling) decide on their own.</p>
     */
    public static boolean fitsUnderWorld(int worldMinY, int bedrockY, int twinY, int height) {
        if (bedrockY <= floorY(worldMinY)) return true;
        return twinY + height < bedrockY;
    }
}
