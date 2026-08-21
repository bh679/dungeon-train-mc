package games.brennan.dungeontrain.portal;

/**
 * The stretch of sealed, unreachable world a portal pair's twin structure is stamped into — a floor
 * to stand the lanes on and a ceiling they must stay under.
 *
 * <p><b>Why this is not simply "the basement".</b> An ordinary Dungeon Train overworld runs its
 * {@code dimension_type} deeper than its terrain, leaving empty world under the bedrock that
 * generation never touches and a player can never dig into; that is where twins live, and
 * {@link #basement} describes it. Inside the <b>upside-down band</b> the world is turned over: the
 * mirror deletes the bedrock row so the underside opens to void and stamps an inverted bedrock lid
 * overhead instead. The basement there is a shelf hanging in the open, and the sealed space — the one
 * that is "under the world" in every sense that matters — is the <b>attic</b> above that lid. Same
 * property, opposite end of the world. {@link #attic} describes that one.</p>
 *
 * <p><b>Two numbers, because that is all the lane allocator ever wanted.</b> Every method on
 * {@link PortalTwinLanes} was already written against a floor and a ceiling; it only ever called them
 * {@code worldMinY} and {@code bedrockY} because the basement was the only region there was. Passing
 * a region instead changes no lane arithmetic at all — {@link #base} plays the first role and
 * {@link #ceiling} the second.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap — same convention as
 * {@link PortalTwinLanes} and {@link PortalGeometry}. Resolving which region a given carriage is over
 * needs a level, and lives in {@link PortalTwinSpace}.</p>
 *
 * @param base    the row <i>below</i> the lowest lane's floor — {@link PortalTwinLanes#FLOOR_MARGIN}
 *                sits between them
 * @param ceiling the row a structure must stay strictly under
 */
public record PortalTwinRegion(int base, int ceiling) {

    /**
     * The empty world under an ordinary overworld's bedrock: floor at the build floor, ceiling at the
     * terrain bedrock layer. In a world with no basement at all — Compatible Terrain runs vanilla's
     * dimension type, whose floor and bedrock are the same row — the two coincide, which every
     * {@link PortalTwinLanes} method already treats as "measure nothing, let the caller's other fit
     * checks decide".
     */
    public static PortalTwinRegion basement(int worldMinY, int bedrockY) {
        return new PortalTwinRegion(worldMinY, bedrockY);
    }

    /**
     * The sealed space above the upside-down band's inverted bedrock lid: floor on the lid itself,
     * ceiling held {@code ceilingMargin} below the build ceiling.
     *
     * <p>The margin is the same one {@code PortalCarriageEvents.CEILING_MARGIN} has always applied to
     * a twin's top — the structure's skin writes one row past its ceiling, and the build ceiling is
     * where writes stop mattering. Folding it into the region means the lane allocator never hands
     * out a lane the skin could not finish.</p>
     *
     * <p>Unlike the basement's fixed eighty blocks, an attic's depth is whatever the lid's config
     * leaves it — see {@code UpsideDownBand.roofY} — so it can be too shallow to stand anything up.
     * {@link #canHold} is the question to ask before choosing it.</p>
     */
    public static PortalTwinRegion attic(int roofY, int worldMaxY, int ceilingMargin) {
        return new PortalTwinRegion(roofY, worldMaxY - ceilingMargin);
    }

    /**
     * Whether this region can stand up one full lane of a structure {@code structureHeight} tall.
     *
     * <p>Exactly the condition {@link PortalTwinLanes#fitsUnderWorld} applies to the lowest lane, so a
     * region that answers {@code true} is guaranteed to yield at least one usable lane and a caller
     * that picks it will not find every pair rejected at the fit gate.</p>
     */
    public boolean canHold(int structureHeight) {
        return PortalTwinLanes.fitsUnderWorld(
            base, ceiling, PortalTwinLanes.floorY(base), structureHeight);
    }

    /**
     * Whether {@code y} is inside this region — the test that replaces "is the player below the
     * bedrock", which several systems use as their proxy for "is in a portal room" and which is
     * simply false for a room in the attic.
     */
    public boolean contains(double y) {
        return y >= base && y < ceiling;
    }
}
