package games.brennan.dungeontrain.portal;

/**
 * Which side of a twin's seal a camera is on, and what that hides — the geometry behind "once you are
 * under the bedrock, nothing above it renders".
 *
 * <p>A portal pair's twin is stamped into sealed world: the basement under the terrain floor
 * everywhere, or the attic over the upside-down band's inverted bedrock lid inside the band (see
 * {@link PortalTwinSpace}). The seal is what makes that space unreachable, and it is also what a
 * player standing in a twin was looking at — a bedrock ceiling overhead is a rock lid over a room
 * that is supposed to read as a carriage, and where the band has cleared that row instead, the train
 * itself drifts past overhead in plain sight.</p>
 *
 * <h2>The two cuts are mirror images, but not symmetrical</h2>
 * <p>In the <b>basement</b> the seal row is the ceiling: it is between the camera and everything it
 * should not see, so it is hidden along with the far side. In the <b>attic</b> the lid is the floor
 * the twin stands on ({@link PortalTwinRegion#attic} bases the region on that very row), so it stays
 * and only what lies strictly below it goes. Hiding the row you are standing on would hang the room
 * over a hole.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap — the same convention as
 * {@link PortalTwinRegion} and {@link PortalGeometry}. The client half, which knows where the camera
 * is and where this world's two planes are, is {@code ClientPortalSeal}.</p>
 */
public final class PortalSealPlane {

    private PortalSealPlane() {}

    /**
     * The cut in force for one frame: nothing, or a plane with the side of it that must not draw.
     *
     * @param sealed     whether anything is hidden at all — {@code false} is the common case, and
     *                   every test short-circuits on it
     * @param hidesAbove {@code true} for the basement cut (hide at and above {@link #planeY}),
     *                   {@code false} for the attic's (hide at and below it)
     * @param planeY     the seal row's Y
     */
    public record Cut(boolean sealed, boolean hidesAbove, int planeY) {

        /** Draw everything — outside twin space, and whenever the feature is switched off. */
        public static final Cut NONE = new Cut(false, false, 0);

        /** The basement cut: the bedrock row at {@code planeY} and everything over it goes. */
        public static Cut above(int planeY) {
            return new Cut(true, true, planeY);
        }

        /** The attic cut: everything under the lid row at {@code planeY} goes; the lid stays. */
        public static Cut below(int planeY) {
            return new Cut(true, false, planeY);
        }

        /**
         * Whether a box spanning {@code [minY, maxY]} lies wholly on the hidden side.
         *
         * <p>Wholly, not partly: a box straddling the plane holds blocks the camera is entitled to
         * see. That is exact for anything cut by its own bounds — an entity, a Sable sub-level — and
         * 16-block granular for a chunk section, which is why the basement cut is the tidier of the
         * two: every Dungeon Train {@code noise_settings} starts on a multiple of 16, so the bedrock
         * row is a section floor and its section goes whole. The band's lid is at whatever height its
         * config puts it, so up to fifteen rows under it can survive the attic cut — culling that
         * section too would take the twin's own floor with it.</p>
         */
        public boolean hides(double minY, double maxY) {
            if (!sealed) return false;
            return hidesAbove ? minY >= planeY : maxY <= planeY;
        }
    }

    /**
     * The cut for a camera at {@code cameraY}, given this world's two planes.
     *
     * <p>{@code bedrockY} is {@link Integer#MIN_VALUE} and {@code roofY} {@link Integer#MAX_VALUE}
     * before the world's floor has been synced, which reads as "no seal anywhere" — the same
     * not-yet-known value {@code ClientUpsideDownBand} already holds and treats the same way.</p>
     *
     * <p>The two tests are written against the rows themselves rather than against a region: under
     * the bedrock means below the row, which occupies {@code [bedrockY, bedrockY + 1)}; over the lid
     * means above the whole of it, so a player standing on the lid — eyes a block and a half over
     * {@code roofY + 1} — is inside the attic and a player crouched under it is not.</p>
     *
     * @param lidHere whether a lid is actually stamped at this world-X. Outside the band there is no
     *                attic to be in, and every mountainside would otherwise answer yes.
     */
    public static Cut cutFor(int bedrockY, int roofY, boolean lidHere, double cameraY) {
        if (bedrockY != Integer.MIN_VALUE && cameraY < bedrockY) return Cut.above(bedrockY);
        if (lidHere && roofY != Integer.MAX_VALUE && cameraY > roofY + 1) return Cut.below(roofY);
        return Cut.NONE;
    }
}
