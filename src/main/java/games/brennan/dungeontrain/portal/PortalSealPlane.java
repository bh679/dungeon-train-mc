package games.brennan.dungeontrain.portal;

/**
 * Which side of a twin's seal a camera is on, and what that hides — the geometry behind "the two
 * halves of a sealed world never see each other".
 *
 * <p>A portal pair's twin is stamped into sealed world: the basement under the terrain floor
 * everywhere, or the attic over the upside-down band's inverted bedrock lid inside the band (see
 * {@link PortalTwinSpace}). The seal is what makes that space unreachable, and it is also what gave
 * it away — a bedrock ceiling over a room that is supposed to read as a carriage, and, where the band
 * has cleared that row, the train itself drifting past overhead.</p>
 *
 * <h2>It cuts both ways</h2>
 * <p>The first version only hid the world from a camera inside a twin, which left the mirror image of
 * the same problem: from up on the train, looking down through a floor the band has opened, the twin
 * rooms hang there in plain sight. So a cut is a <b>pair</b> of planes rather than one — the world a
 * camera is entitled to see is the band between the seals nearest it on either side, and everything
 * wholly beyond either plane is hidden.</p>
 *
 * <h2>Which side the seal row itself belongs to</h2>
 * <p>The seal rows are ordinary world, so they are kept — with one deliberate exception. The bedrock
 * row is hidden from <i>underneath</i>, because a rock ceiling is exactly what a player in a twin must
 * not see; seen from above it is the world's own floor and stays. The band's lid stays from both
 * sides: it is the attic's floor ({@link PortalTwinRegion#attic} bases the region on that very row),
 * and hiding it would hang the twin over a hole.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap — the same convention as
 * {@link PortalTwinRegion} and {@link PortalGeometry}. The client half, which knows where the camera
 * is, which world it is in and where that world's planes are, is {@code ClientPortalSeal}.</p>
 */
public final class PortalSealPlane {

    private PortalSealPlane() {}

    /**
     * The cut in force for one frame: the two planes bounding what may be drawn.
     *
     * @param sealed  whether anything is hidden at all — {@code false} short-circuits every test
     * @param floorY  hide anything whose top is at or below this. {@link Integer#MIN_VALUE} for
     *                "nothing below is hidden"
     * @param roofY   hide anything whose bottom is at or above this. {@link Integer#MAX_VALUE} for
     *                "nothing above is hidden"
     */
    public record Cut(boolean sealed, int floorY, int roofY) {

        /** Draw everything — outside a sealed world, and whenever the feature is switched off. */
        public static final Cut NONE = new Cut(false, Integer.MIN_VALUE, Integer.MAX_VALUE);

        /** The cut bounded by these two planes, or {@link #NONE} when neither bounds anything. */
        public static Cut between(int floorY, int roofY) {
            if (floorY == Integer.MIN_VALUE && roofY == Integer.MAX_VALUE) return NONE;
            return new Cut(true, floorY, roofY);
        }

        /**
         * Whether a box spanning {@code [minY, maxY]} lies wholly beyond one of the planes.
         *
         * <p>Wholly, not partly: a box straddling a plane holds blocks the camera is entitled to
         * see. That is exact for anything cut by its own bounds — an entity, a Sable sub-level — and
         * 16-block granular for a chunk section, which is why the bedrock plane is the tidier one:
         * every Dungeon Train {@code noise_settings} starts on a multiple of 16, so the bedrock row
         * is a section floor and its section goes whole. The band's lid is at whatever height its
         * config puts it, so up to fifteen rows on the far side of it can survive — culling that
         * section too would take the lid, and the attic's floor, with it.</p>
         */
        public boolean hides(double minY, double maxY) {
            if (!sealed) return false;
            return maxY <= floorY || minY >= roofY;
        }
    }

    /**
     * The cut for a camera at {@code cameraY}, given this world's two seal rows.
     *
     * <p>{@code bedrockY} is {@link Integer#MIN_VALUE} and {@code roofY} {@link Integer#MAX_VALUE}
     * before the world's floor has been synced, which reads as "no seal anywhere" — the same
     * not-yet-known values {@code ClientUpsideDownBand} already holds and treats the same way.</p>
     *
     * <p>The tests are written against the rows themselves rather than against a region. The bedrock
     * row occupies {@code [bedrockY, bedrockY + 1)}, so under it means below {@code bedrockY}; the
     * lid occupies {@code [roofY, roofY + 1)}, so over it means above {@code roofY + 1} — a player
     * standing on the lid, eyes well over that, is in the attic and one crouched beneath it is not.
     * The three cases are exhaustive and ordered from the bottom of the world up.</p>
     *
     * @param lidHere whether a lid is actually stamped at this world-X. Outside the band there is no
     *                attic and no ceiling to hide anything over — every mountainside would otherwise
     *                answer yes.
     */
    public static Cut cutFor(int bedrockY, int roofY, boolean lidHere, double cameraY) {
        boolean hasFloor = bedrockY != Integer.MIN_VALUE;
        boolean hasLid = lidHere && roofY != Integer.MAX_VALUE;

        // In the basement: the bedrock row over your head goes with everything above it, and there is
        // nothing below you to hide.
        if (hasFloor && cameraY < bedrockY) return Cut.between(Integer.MIN_VALUE, bedrockY);

        // In the attic: the lid under your feet stays, and the mirrored world below it goes.
        if (hasLid && cameraY > roofY + 1) return Cut.between(roofY, Integer.MAX_VALUE);

        // In the world between them — on the train, or anywhere else a player ordinarily stands. Both
        // seals are kept, and the sealed space beyond each of them is not drawn: this is the half that
        // stops the twins hanging in view under a floor the band has opened.
        return Cut.between(hasFloor ? bedrockY : Integer.MIN_VALUE,
                           hasLid ? roofY + 1 : Integer.MAX_VALUE);
    }
}
