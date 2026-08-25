package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ends of the world a portal twin can be hidden in, and the promise that swapping one for the
 * other costs the rooms nothing.
 *
 * <p>Inside the upside-down band the basement is not a hiding place at all — the mirror deletes the
 * bedrock row, so it hangs open under a world with no floor — and the sealed space is the attic over
 * the inverted lid. The properties worth pinning are that the attic really is roomier than the
 * basement it replaces at stock settings (so nothing an author built stops fitting), that it degrades
 * rather than lies when a world's config leaves it shallow, and that its ceiling already carries the
 * margin the fit gate would otherwise refuse a lane for.</p>
 *
 * <p>No NeoForge bootstrap — same convention as {@link PortalTwinLanesTest}; the lid height comes from
 * {@link UpsideDownBand}'s pure halves, which is what the level-aware {@code UpsideDownBand.roofY}
 * composes.</p>
 */
final class PortalTwinRegionTest {

    /** The default DT overworld: dimension floor at -48, terrain floor at 32, build ceiling 320. */
    private static final int DT_MIN_Y = -48;
    private static final int DT_BEDROCK_Y = 32;
    private static final int DT_MAX_Y = 320;

    /** Stock band settings: train at 78, mirror plane on it, no ceiling gap, ceiling capped at 32. */
    private static final int TRAIN_Y = 78;
    private static final int CEILING_GAP = 0;
    private static final int MAX_CEILING_HEIGHT = 32;

    private static final int CEILING_MARGIN = 4;

    /** The height lanes are spaced on in a world that has authored nothing taller. */
    private static final int ROOM_H = 7;

    /** The lid the band stamps at these settings, by the same two calls the server composes. */
    private static int stockRoofY() {
        int roofY = UpsideDownBand.bedrockRoofY(TRAIN_Y, CEILING_GAP, DT_BEDROCK_Y, DT_MAX_Y);
        return UpsideDownBand.cappedRoofY(roofY, TRAIN_Y, CEILING_GAP, MAX_CEILING_HEIGHT);
    }

    private static PortalTwinRegion stockAttic() {
        return PortalTwinRegion.attic(stockRoofY(), DT_MAX_Y, CEILING_MARGIN);
    }

    @Test
    @DisplayName("The attic stands on the lid, well clear of the train underneath it")
    void atticStandsOnTheLid() {
        assertEquals(111, stockRoofY(), "stock lid height");
        PortalTwinRegion attic = stockAttic();
        assertEquals(111, attic.base());
        assertTrue(attic.base() > TRAIN_Y,
            "a twin below the train would be inside the world the train runs through");
    }

    @Test
    @DisplayName("Its ceiling already holds the margin the fit gate keeps under the build ceiling")
    void atticCeilingCarriesTheMargin() {
        assertEquals(DT_MAX_Y - CEILING_MARGIN, stockAttic().ceiling());
    }

    @Test
    @DisplayName("At stock settings it is the roomier of the two — no room shortens on entering the band")
    void atticIsRoomierThanTheBasement() {
        PortalTwinRegion basement = PortalTwinRegion.basement(DT_MIN_Y, DT_BEDROCK_Y);
        PortalTwinRegion attic = stockAttic();
        assertTrue(attic.ceiling() - attic.base() > basement.ceiling() - basement.base());
        assertTrue(
            PortalTwinLanes.maxStructureHeight(attic.base(), attic.ceiling())
                >= PortalTwinLanes.maxStructureHeight(basement.base(), basement.ceiling()),
            "an in-band room must not be held shorter than the same room outside the band");
    }

    @Test
    @DisplayName("And holds every lane, so pairs spread there exactly as they do in the basement")
    void atticHoldsAllLanes() {
        PortalTwinRegion attic = stockAttic();
        assertEquals(PortalTwinLanes.MAX_LANES,
            PortalTwinLanes.usableLanes(attic.base(), attic.ceiling(), ROOM_H));
        // Every lane's structure clears the build ceiling, which is the fit gate's other half.
        for (int lane = 0; lane < PortalTwinLanes.MAX_LANES; lane++) {
            int twinY = PortalTwinLanes.twinFloorY(
                attic.base(), attic.ceiling(), lane * 4, 4, ROOM_H);
            assertTrue(
                PortalTwinLanes.fitsUnderWorld(attic.base(), attic.ceiling(), twinY, ROOM_H),
                "lane " + lane + " at " + twinY);
            assertTrue(twinY + ROOM_H < DT_MAX_Y - CEILING_MARGIN, "lane " + lane);
        }
    }

    @Test
    @DisplayName("canHold says no before the lanes start lying about it")
    void canHoldRefusesAnAtticTooShallowToStandOne() {
        PortalTwinRegion roomy = stockAttic();
        assertTrue(roomy.canHold(ROOM_H));
        assertTrue(roomy.canHold(PortalRoomLayout.MAX_HEIGHT));

        // A lid pushed to just under the build ceiling — an exotic but legal config.
        PortalTwinRegion cramped = PortalTwinRegion.attic(310, DT_MAX_Y, CEILING_MARGIN);
        assertFalse(cramped.canHold(ROOM_H),
            "a region that cannot stand one structure must not be chosen, or every pair loses its twin");
        // And the refusal is exactly the gate the caller would have failed anyway.
        assertFalse(PortalTwinLanes.fitsUnderWorld(cramped.base(), cramped.ceiling(),
            PortalTwinLanes.floorY(cramped.base()), ROOM_H));
    }

    @Test
    @DisplayName("A room in either region reads as inside it, and the open world in between does not")
    void containsCoversTheRoomAndNothingElse() {
        PortalTwinRegion basement = PortalTwinRegion.basement(DT_MIN_Y, DT_BEDROCK_Y);
        PortalTwinRegion attic = stockAttic();

        assertTrue(basement.contains(PortalTwinLanes.floorY(DT_MIN_Y)));
        assertFalse(basement.contains(DT_BEDROCK_Y), "the bedrock row is the world, not the basement");
        assertTrue(attic.contains(PortalTwinLanes.floorY(attic.base())));
        assertFalse(attic.contains(TRAIN_Y), "the train runs in the world, not in twin space");
        assertFalse(attic.contains(DT_BEDROCK_Y + 1), "nor does the terrain above the bedrock");
    }
}
