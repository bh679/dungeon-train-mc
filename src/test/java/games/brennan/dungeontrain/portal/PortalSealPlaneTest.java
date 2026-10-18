package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("over the band's lid, the lid and everything under it goes")
    void atticHidesTheLidAndBelow() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, IN_THE_ATTIC);

        assertTrue(cut.sealed());
        // The lid row spans [roofY, roofY + 1): a bedrock floor under every window, and the complaint.
        assertTrue(cut.hides(ROOF_Y, ROOF_Y + 1));
        // The mirrored world under it goes.
        assertTrue(cut.hides(ROOF_Y - 16, ROOF_Y));
        assertTrue(cut.hides(0, 20));
        // The twin's own bedrock underside sits on the row above the lid, and stays with the room.
        assertFalse(cut.hides(ROOF_Y + 1, ROOF_Y + 2));
        assertFalse(cut.hides(ROOF_Y + 1, ROOF_Y + 8));
    }

    @Test
    @DisplayName("from the attic, the rows the lid's section keeps are trimmed block by block")
    void atticTrimsTheLidsSection() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, IN_THE_ATTIC);
        int sectionFloor = 96; // the section [96, 112) holds the lid at 110 and the room's underside at 111

        // That section straddles the plane, so it is kept whole...
        assertFalse(cut.hides(sectionFloor, sectionFloor + 16));
        // ...and its rows from the section floor up to and including the lid are skipped one by one.
        assertTrue(cut.hidesBlock(sectionFloor));
        assertTrue(cut.hidesBlock(ROOF_Y - 1));
        assertTrue(cut.hidesBlock(ROOF_Y));
        // The room's underside and everything over it are drawn.
        assertFalse(cut.hidesBlock(ROOF_Y + 1));
        assertFalse(cut.hidesBlock(ROOF_Y + 5));
        // Rows in sections the cut already drops need no trim.
        assertFalse(cut.hidesBlock(sectionFloor - 1));
    }

    @Test
    @DisplayName("a lid on a section's top row is hidden with its whole section")
    void atticLidOnASectionTopNeedsNoRoomRows() {
        int roof = 111; // section [96, 112) ends on the lid, so the room starts the next section
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, roof, true, roof + 2.6);
        assertTrue(cut.hides(96, 112));
        assertFalse(cut.hides(112, 128));
        assertFalse(cut.hidesBlock(roof + 1));
    }

    @Test
    @DisplayName("the basement's plane is a section floor, so it trims nothing")
    void basementNeedsNoTrim() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, false, IN_BASEMENT);
        for (int y = BEDROCK_Y - 20; y < BEDROCK_Y + 20; y++) assertFalse(cut.hidesBlock(y));
    }

    @Test
    @DisplayName("the world view never trims — its cut moves with the train")
    void worldViewNeverTrims() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutFor(BEDROCK_Y, ROOF_Y, true, ON_THE_TRAIN);
        for (int y = -64; y < 320; y++) assertFalse(cut.hidesBlock(y));
        assertFalse(PortalSealPlane.Cut.NONE.hidesBlock(0));
    }

    @Test
    @DisplayName("the sunk band trims its attic over the lid and its basement under the sunk floor")
    void amplifiedTrims() {
        int floor = -20, lid = 200;
        PortalSealPlane.Cut attic = PortalSealPlane.cutForAmplified(floor, lid, true, lid + 2.6);
        assertTrue(attic.hidesBlock(lid));
        assertTrue(attic.hidesBlock(192));
        assertFalse(attic.hidesBlock(lid + 1));

        // Below a sunk floor that is not a section floor: rows [floor, -16) go one by one.
        PortalSealPlane.Cut basement = PortalSealPlane.cutForAmplified(floor, lid, true, floor - 10.0);
        assertTrue(basement.hidesBlock(floor));
        assertTrue(basement.hidesBlock(-17));
        assertFalse(basement.hidesBlock(-16));
        assertFalse(basement.hidesBlock(floor - 1));

        // From the world over the sunk band, nothing is trimmed.
        PortalSealPlane.Cut world = PortalSealPlane.cutForAmplified(floor, lid, true, 60.0);
        assertFalse(world.hidesBlock(floor - 1));
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

    /** Sunk Amplified at stock: the band's bedrock at -48 (the old floor was 32), barrier lid at 224. */
    private static final int AMP_FLOOR_Y = -48;
    private static final int AMP_LID_Y = 224;

    @Test
    @DisplayName("sunk Amplified: from the train, the valleys under the old floor are drawn")
    void amplifiedValleysUnderTheOldFloorStay() {
        PortalSealPlane.Cut onTrain = PortalSealPlane.cutForAmplified(AMP_FLOOR_Y, AMP_LID_Y, true, ON_THE_TRAIN);
        assertFalse(onTrain.hides(0, 16), "a valley section under y32 must not be culled");
        assertFalse(onTrain.hides(AMP_FLOOR_Y, AMP_FLOOR_Y + 16));
        assertTrue(onTrain.hides(AMP_FLOOR_Y - 16, AMP_FLOOR_Y));
    }

    @Test
    @DisplayName("sunk Amplified: no plane over the lid from the world, so fade peaks keep their tops")
    void amplifiedNoCutAboveTheLidFromTheWorld() {
        PortalSealPlane.Cut onTrain = PortalSealPlane.cutForAmplified(AMP_FLOOR_Y, AMP_LID_Y, true, ON_THE_TRAIN);
        assertFalse(onTrain.hides(AMP_LID_Y + 16, AMP_LID_Y + 32));
    }

    @Test
    @DisplayName("sunk Amplified: a camera in an attic twin sees nothing of the world below the lid")
    void amplifiedAtticHidesTheWorld() {
        PortalSealPlane.Cut inAttic = PortalSealPlane.cutForAmplified(AMP_FLOOR_Y, AMP_LID_Y, true, AMP_LID_Y + 40);
        assertTrue(inAttic.hides(AMP_LID_Y - 32, AMP_LID_Y - 16));
        assertFalse(inAttic.hides(AMP_LID_Y, AMP_LID_Y + 16), "the lid is the attic's floor");
        // Outside the slot the lid isn't there, so the same height is just sky.
        PortalSealPlane.Cut offSlot = PortalSealPlane.cutForAmplified(AMP_FLOOR_Y, AMP_LID_Y, false, AMP_LID_Y + 40);
        assertFalse(offSlot.hides(AMP_LID_Y - 32, AMP_LID_Y - 16));
    }

    // ---- a camera inside a carriage's structure box, whatever band its column is in ----

    private static final int NO_LID = Integer.MAX_VALUE;

    @Test
    @DisplayName("a basement carriage hides the bedrock row and everything over it, in any band")
    void basementCarriageIsSealedAnywhere() {
        PortalSealPlane.Cut cut = PortalSealPlane.cutForTwin(-60, BEDROCK_Y - 4, BEDROCK_Y, ROOF_Y, AMP_LID_Y);

        assertTrue(cut.hides(BEDROCK_Y, BEDROCK_Y + 1));
        assertTrue(cut.hides(78, 82));
        assertFalse(cut.hides(BEDROCK_Y - 16, BEDROCK_Y));
    }

    @Test
    @DisplayName("a carriage over the sunk lid, carried out of the zone, still hides the world below it")
    void sunkAtticCarriageOutOfItsZone() {
        // The upside-down lid is off in this world; only the sunk band's barrier lid exists.
        PortalSealPlane.Cut cut = PortalSealPlane.cutForTwin(AMP_LID_Y + 2, AMP_LID_Y + 40, BEDROCK_Y,
            NO_LID, AMP_LID_Y);

        assertTrue(cut.sealed());
        // The world under the room, and the lid row it would otherwise stand on.
        assertTrue(cut.hides(64, 128));
        assertTrue(cut.hidesBlock(AMP_LID_Y));
        // The room itself stays.
        assertFalse(cut.hides(AMP_LID_Y + 2, AMP_LID_Y + 10));
    }

    @Test
    @DisplayName("with both lids known, a carriage is sealed over the one under its own floor")
    void picksTheLidUnderTheCarriage() {
        // Over the lower (upside-down) lid: the higher sunk lid is above the room, not under it.
        PortalSealPlane.Cut lower = PortalSealPlane.cutForTwin(ROOF_Y + 2, ROOF_Y + 60, BEDROCK_Y,
            ROOF_Y, AMP_LID_Y);
        assertTrue(lower.hidesBlock(ROOF_Y));
        assertFalse(lower.hides(ROOF_Y + 2, ROOF_Y + 10));

        // Over the higher (sunk) lid: everything down past the upside-down lid goes too.
        PortalSealPlane.Cut higher = PortalSealPlane.cutForTwin(AMP_LID_Y + 2, AMP_LID_Y + 20, BEDROCK_Y,
            ROOF_Y, AMP_LID_Y);
        assertTrue(higher.hides(ROOF_Y + 2, ROOF_Y + 10));
        assertTrue(higher.hidesBlock(AMP_LID_Y));
    }

    @Test
    @DisplayName("a box over no known lid leaves the column tests to answer")
    void noLidUnderTheBox() {
        assertNull(PortalSealPlane.cutForTwin(60, 90, BEDROCK_Y, NO_LID, NO_LID));
        assertNull(PortalSealPlane.cutForTwin(60, 90, BEDROCK_Y, ROOF_Y, AMP_LID_Y));
    }
}
