package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cut that stops a portal twin from showing the world it is hidden under.
 *
 * <p>The properties worth pinning are the ones a player would notice going wrong: that the seal row
 * itself goes in the basement (it is the ceiling they were looking at) and stays in the attic (it is
 * the floor they are standing on), that a box straddling the plane is never hidden, and that a world
 * whose floor has not been synced yet hides nothing at all rather than everything.</p>
 *
 * <p>No NeoForge bootstrap — {@link PortalSealPlane} holds no Minecraft types, same convention as
 * {@link PortalTwinRegionTest}.</p>
 */
final class PortalSealPlaneTest {

    /** The default DT overworld: terrain floor at 32, band lid at 110. */
    private static final int BEDROCK_Y = 32;
    private static final int ROOF_Y = 110;

    /** Y of a camera in a basement twin, and of one standing on the band's lid. */
    private static final double IN_BASEMENT = -10.0;
    private static final double ON_THE_LID = ROOF_Y + 2.6;

    @Test
    @DisplayName("under the bedrock, the bedrock row and everything over it is hidden")
    void basementHidesTheSealAndAbove() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, IN_BASEMENT);

        assertTrue(cut.sealed());
        // The bedrock row itself: the ceiling the player was looking at, and the whole complaint.
        assertTrue(cut.hides(BEDROCK_Y, BEDROCK_Y + 1));
        // The section it is the floor of, and the train riding a hundred blocks above it.
        assertTrue(cut.hides(BEDROCK_Y, BEDROCK_Y + 16));
        assertTrue(cut.hides(78, 82));
        // The room the player is actually in stays.
        assertFalse(cut.hides(BEDROCK_Y - 16, BEDROCK_Y));
        assertFalse(cut.hides(IN_BASEMENT - 2, IN_BASEMENT + 2));
    }

    @Test
    @DisplayName("over the band's lid, the lid stays and everything under it goes")
    void atticHidesBelowButKeepsTheLid() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, ON_THE_LID);

        assertTrue(cut.sealed());
        // The lid row spans [roofY, roofY + 1) and is the attic's floor — hiding it would hang the
        // twin over a hole.
        assertFalse(cut.hides(ROOF_Y, ROOF_Y + 1));
        // The mirrored world under it goes.
        assertTrue(cut.hides(ROOF_Y - 16, ROOF_Y));
        assertTrue(cut.hides(0, 20));
        // And the twin standing on the lid is untouched.
        assertFalse(cut.hides(ROOF_Y + 1, ROOF_Y + 8));
    }

    @Test
    @DisplayName("a box straddling the plane is left alone")
    void straddlingBoxesSurvive() {
        PortalSealPlane.Cut basement = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, IN_BASEMENT);
        assertFalse(basement.hides(BEDROCK_Y - 4, BEDROCK_Y + 4));

        PortalSealPlane.Cut attic = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, ON_THE_LID);
        assertFalse(attic.hides(ROOF_Y - 4, ROOF_Y + 4));
    }

    @Test
    @DisplayName("no lid stamped here means no attic to be sealed in")
    void atticNeedsALid() {
        // Same camera height, outside the band: an ordinary mountainside at that Y, not a twin.
        assertFalse(PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, ON_THE_LID).sealed());
    }

    @Test
    @DisplayName("standing on the bedrock, or under the lid, seals nothing")
    void theNearSideOfEachRowIsOutside() {
        // Feet on the bedrock row's top face.
        assertFalse(PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, BEDROCK_Y + 1.62).sealed());
        // Head just under the lid row: in the mirrored world, not in the attic.
        assertFalse(PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, ROOF_Y - 0.5).sealed());
        assertFalse(PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, ROOF_Y + 1.0).sealed());
    }

    @Test
    @DisplayName("before the world's floor is known, nothing is hidden")
    void unsyncedWorldHidesNothing() {
        assertFalse(PortalSealPlane
            .cutFor(Integer.MIN_VALUE, Integer.MAX_VALUE, false, IN_BASEMENT).sealed());
        assertFalse(PortalSealPlane
            .cutFor(Integer.MIN_VALUE, Integer.MAX_VALUE, true, ON_THE_LID).sealed());
        assertFalse(PortalSealPlane.Cut.NONE.hides(0, 400));
    }
}
