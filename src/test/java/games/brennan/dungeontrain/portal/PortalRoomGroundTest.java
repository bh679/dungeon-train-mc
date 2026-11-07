package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.EditorStatusPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which rows an Endless Open copy repeats as its ground, and how that survives the mode tag. */
class PortalRoomGroundTest {

    private static final PortalRoomGround ENTER =
        new PortalRoomGround(PortalRoomGround.Level.ENTER, false);
    private static final PortalRoomGround DOOR_BELOW =
        new PortalRoomGround(PortalRoomGround.Level.DOOR, true);

    private static PortalRoomSettings open() {
        return PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_OPEN);
    }

    @Test
    @DisplayName("Levels name the room bottom, the door's floor line, and the row above it")
    void levelsNameTheirRows() {
        assertEquals(new PortalRoomGround.Rows(0, 0), PortalRoomGround.DEFAULT.rows(4, 12));
        assertEquals(new PortalRoomGround.Rows(4, 4), ENTER.rows(4, 12));
        assertEquals(new PortalRoomGround.Rows(5, 5),
            new PortalRoomGround(PortalRoomGround.Level.DOOR, false).rows(4, 12));
    }

    @Test
    @DisplayName("Everything Below reaches down to the room's bottom row")
    void belowReachesTheBottom() {
        assertEquals(new PortalRoomGround.Rows(0, 5), DOOR_BELOW.rows(4, 12));
    }

    @Test
    @DisplayName("The ground stops short of the roof, leaving a row open under it")
    void clampsUnderTheRoof() {
        assertEquals(new PortalRoomGround.Rows(9, 9), ENTER.rows(40, 12));
        assertEquals(new PortalRoomGround.Rows(0, 0), ENTER.rows(40, 2));
    }

    @Test
    @DisplayName("The bottom row has nothing below it, so the box settles to off there")
    void bottomHasNoBelow() {
        assertEquals(PortalRoomGround.DEFAULT, PortalRoomGround.DEFAULT.toggleBelow());
        assertEquals(PortalRoomGround.DEFAULT, DOOR_BELOW.withLevel(PortalRoomGround.Level.BOTTOM));
    }

    @Test
    @DisplayName("The level cycles Bottom, Enter, Door and back")
    void levelCycles() {
        PortalRoomGround enter = PortalRoomGround.DEFAULT.nextLevel();
        assertEquals(PortalRoomGround.Level.ENTER, enter.level());
        assertEquals(PortalRoomGround.Level.DOOR, enter.nextLevel().level());
        assertEquals(PortalRoomGround.DEFAULT, enter.nextLevel().nextLevel());
    }

    @Test
    @DisplayName("Parsing is total: anything unreadable is the bottom row")
    void parseIsTotal() {
        assertEquals(PortalRoomGround.DEFAULT, PortalRoomGround.parse(null));
        assertEquals(PortalRoomGround.DEFAULT, PortalRoomGround.parse(""));
        assertEquals(PortalRoomGround.DEFAULT, PortalRoomGround.parse("sideways"));
        assertEquals(DOOR_BELOW, PortalRoomGround.parse(DOOR_BELOW.id()));
        assertEquals(ENTER, PortalRoomGround.parse(ENTER.id()));
    }

    @Test
    @DisplayName("Tags from before the setting existed round-trip unchanged")
    void oldTagsRoundTrip() {
        for (String tag : new String[]{"endless_open", "endless_open/single:minecraft:stone",
                "endless_open/exact/off/off/off/none/sealed/0/1",
                "endless_open/single:minecraft:stone/off/off/off/day/sealed/0/9/0/7"}) {
            PortalRoomSettings parsed = PortalRoomSettings.parse(tag);
            assertEquals(PortalRoomGround.DEFAULT, parsed.ground(), tag);
            assertEquals(tag, parsed.toTag());
        }
    }

    @Test
    @DisplayName("A raised ground round-trips and leaves every other setting as it was")
    void raisedGroundRoundTrips() {
        PortalRoomSettings raised = PortalRoomSettings.parse("endless_open/single:minecraft:stone/off/off/off/day")
            .withGround(DOOR_BELOW);
        PortalRoomSettings back = PortalRoomSettings.parse(raised.toTag());
        assertEquals(DOOR_BELOW, back.ground());
        assertEquals(raised.copies(), back.copies());
        assertEquals(raised.sky(), back.sky());
        assertEquals(raised.toTag(), back.toTag());
        assertTrue(raised.toTag().endsWith("/door:below"), raised.toTag());
    }

    @Test
    @DisplayName("Only Endless Open reads the ground, and no other mode writes it out")
    void onlyEndlessOpenReadsIt() {
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            PortalRoomSettings settings = open().withGround(DOOR_BELOW).withMode(mode);
            boolean applies = mode == PortalRoomMode.ENDLESS_OPEN;
            assertEquals(applies, settings.groundApplies(), mode.id());
            assertEquals(applies ? DOOR_BELOW : PortalRoomGround.DEFAULT, settings.effectiveGround(), mode.id());
            assertEquals(applies, settings.toTag().contains("door"), settings.toTag());
        }
    }

    @Test
    @DisplayName("The ground follows the door, held to where the door can really stand")
    void groundFollowsTheClampedDoor() {
        CarriageDims dims = CarriageDims.DEFAULT;
        int height = PortalRoomLayout.MAX_HEIGHT;
        PortalRoomSettings raised = open().withGround(ENTER)
            .withDoorHeightOffset(new PortalRoomDoorHeightOffset(5));
        assertEquals(new PortalRoomGround.Rows(5, 5), raised.groundRows(dims, height));

        PortalRoomSettings tooHigh = raised.withDoorHeightOffset(new PortalRoomDoorHeightOffset(9999));
        int clamped = PortalRoomLayout.maxDoorHeightOffset(dims, height);
        assertEquals(new PortalRoomGround.Rows(clamped, clamped), tooHigh.groundRows(dims, height));
        assertFalse(tooHigh.groundRows(dims, height).contains(height - 1), "the roof is not ground");
    }

    @Test
    @DisplayName("A room's exit door does not move the ground")
    void exitDoorDoesNotMoveTheGround() {
        PortalRoomSettings raised = open().withGround(ENTER)
            .withDoorHeightOffset(new PortalRoomDoorHeightOffset(5))
            .withExitDoorHeightOffset(new PortalRoomDoorHeightOffset(2));
        assertEquals(new PortalRoomGround.Rows(5, 5),
            raised.groundRows(CarriageDims.DEFAULT, PortalRoomLayout.MAX_HEIGHT));
    }

    @Test
    @DisplayName("The longest ground tag still fits the packet")
    void longestGroundTagFitsThePacket() {
        String blockId = "m".repeat(PortalRoomCopies.BLOCK_ID_MAX);
        PortalRoomSettings widest = PortalRoomSettings.parse("endless_open/single:" + blockId)
            .withContents(PortalRoomContents.values()[PortalRoomContents.values().length - 1])
            .withBooks(PortalRoomBooks.parse("mix:99:99:99:999:999"))
            .withExits(new PortalRoomExits(PortalRoomExits.Kind.RANDOM,
                PortalRoomExits.MAX_EVERY, PortalRoomExits.MOVE_ALWAYS))
            .withSky(PortalRoomSky.values()[PortalRoomSky.values().length - 1])
            .withDoorOffset(new PortalRoomDoorOffset(-64))
            .withDoorHeightOffset(new PortalRoomDoorHeightOffset(96))
            .withExitDoorOffset(new PortalRoomDoorOffset(-64))
            .withExitDoorHeightOffset(new PortalRoomDoorHeightOffset(96))
            .withFog(PortalRoomFog.AUTO)
            .withGround(DOOR_BELOW);
        String tag = widest.toTag();
        assertTrue(tag.length() <= EditorStatusPacket.MODE_TAG_MAX,
            "'" + tag + "' is " + tag.length() + " chars, over the packet cap");
    }
}
