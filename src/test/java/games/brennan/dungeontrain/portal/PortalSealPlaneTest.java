package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cut that keeps a portal twin and the world it hides under from seeing each other.
 *
 * <p>The properties worth pinning are the ones a player would notice going wrong: that the cut works
 * in <b>both</b> directions (the first version only hid the world from a twin, leaving the twins
 * hanging in view from up on the train), that the seal rows land on the right side of it — the
 * bedrock row hidden from underneath, where a rock ceiling would give the trick away, and kept from
 * above, where it is the world's own floor — that a box straddling a plane is never hidden, and that
 * a world whose floor has not been synced yet hides nothing at all rather than everything.</p>
 *
 * <p>No NeoForge bootstrap — {@link PortalSealPlane} holds no Minecraft types, same convention as
 * {@link PortalTwinRegionTest}.</p>
 */
final class PortalSealPlaneTest {

    /** The default DT overworld: terrain floor at 32, band lid at 110. */
    private static final int BEDROCK_Y = 32;
    private static final int ROOF_Y = 110;

    /** A camera in a basement twin, one on the train between the seals, one up in the attic. */
    private static final double IN_BASEMENT = -10.0;
    private static final double ON_THE_TRAIN = 80.0;
    private static final double IN_THE_ATTIC = ROOF_Y + 2.6;

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
    @DisplayName("from the train, the sealed space under the world is not drawn either")
    void theWorldCannotSeeIntoTheBasement() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, ON_THE_TRAIN);

        assertTrue(cut.sealed());
        // A twin standing in the basement, which the band's open floor would otherwise show.
        assertTrue(cut.hides(BEDROCK_Y - 40, BEDROCK_Y - 20));
        assertTrue(cut.hides(BEDROCK_Y - 16, BEDROCK_Y));
        // Seen from above, the bedrock row is the world's own floor and stays.
        assertFalse(cut.hides(BEDROCK_Y, BEDROCK_Y + 1));
        // As does everything the player is standing in and over.
        assertFalse(cut.hides(ON_THE_TRAIN - 2, ON_THE_TRAIN + 2));
        assertFalse(cut.hides(300, 316));
    }

    @Test
    @DisplayName("in the band, the world between the seals sees neither sealed end")
    void theWorldIsCutAtBothEnds() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, ON_THE_TRAIN);

        assertTrue(cut.sealed());
        assertTrue(cut.hides(BEDROCK_Y - 16, BEDROCK_Y));      // a basement twin below
        assertTrue(cut.hides(ROOF_Y + 1, ROOF_Y + 17));        // an attic twin above
        assertFalse(cut.hides(ROOF_Y, ROOF_Y + 1));            // the lid between them stays
        assertFalse(cut.hides(BEDROCK_Y, BEDROCK_Y + 1));      // and so does the floor
    }

    @Test
    @DisplayName("over the band's lid, the lid stays and everything under it goes")
    void atticHidesBelowButKeepsTheLid() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, IN_THE_ATTIC);

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
    @DisplayName("a box straddling a plane is left alone")
    void straddlingBoxesSurvive() {
        PortalSealPlane.Cut basement = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, IN_BASEMENT);
        assertFalse(basement.hides(BEDROCK_Y - 4, BEDROCK_Y + 4));

        PortalSealPlane.Cut world = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, ON_THE_TRAIN);
        assertFalse(world.hides(BEDROCK_Y - 4, BEDROCK_Y + 4));
        assertFalse(world.hides(ROOF_Y - 4, ROOF_Y + 4));

        PortalSealPlane.Cut attic = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, IN_THE_ATTIC);
        assertFalse(attic.hides(ROOF_Y - 4, ROOF_Y + 4));
    }

    @Test
    @DisplayName("no lid stamped here means no ceiling to hide anything over")
    void theCeilingNeedsALid() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, ON_THE_TRAIN);
        // Outside the band that height is ordinary open sky, not the far side of a seal.
        assertFalse(cut.hides(ROOF_Y + 1, ROOF_Y + 17));
        // And a camera up there is in the world, not in an attic — so the world's floor still cuts.
        PortalSealPlane.Cut high = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, IN_THE_ATTIC);
        assertFalse(high.hides(ROOF_Y - 16, ROOF_Y));
        assertTrue(high.hides(BEDROCK_Y - 16, BEDROCK_Y));
    }

    @Test
    @DisplayName("standing on the bedrock, or under the lid, puts you on the world's side")
    void theRowsBelongToTheWorld() {
        // Feet on the bedrock row's top face: the world's side, so the basement below is what goes.
        PortalSealPlane.Cut onBedrock =
            PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, BEDROCK_Y + 1.62);
        assertTrue(onBedrock.hides(BEDROCK_Y - 16, BEDROCK_Y));
        assertFalse(onBedrock.hides(BEDROCK_Y, BEDROCK_Y + 1));

        // Head just under the lid: in the mirrored world, not in the attic.
        PortalSealPlane.Cut underLid = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, ROOF_Y - 0.5);
        assertTrue(underLid.hides(ROOF_Y + 1, ROOF_Y + 17));
        assertFalse(underLid.hides(ROOF_Y, ROOF_Y + 1));
    }

    @Test
    @DisplayName("before the world's floor is known, nothing is hidden")
    void unsyncedWorldHidesNothing() {
        assertFalse(PortalSealPlane
            .cutFor(Integer.MIN_VALUE, Integer.MAX_VALUE, false, IN_BASEMENT).sealed());
        assertFalse(PortalSealPlane
            .cutFor(Integer.MIN_VALUE, Integer.MAX_VALUE, true, IN_THE_ATTIC).sealed());
        assertFalse(PortalSealPlane
            .cutFor(Integer.MIN_VALUE, Integer.MAX_VALUE, true, ON_THE_TRAIN).sealed());
        assertFalse(PortalSealPlane.Cut.NONE.hides(0, 400));
    }
}
