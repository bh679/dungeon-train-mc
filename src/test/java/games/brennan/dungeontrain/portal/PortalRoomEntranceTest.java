package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A dimensional carriage whose entrance does not lead back to the train. */
class PortalRoomEntranceTest {

    private static final int CARRIAGE = PortalFrames.FRAME_CARRIAGE;
    private static final int TWIN = PortalFrames.FRAME_TWIN;

    @Test
    @DisplayName("Parsing is total: null, blank and nonsense all read as Two-way")
    void parseIsTotal() {
        assertSame(PortalRoomEntrance.TWO_WAY, PortalRoomEntrance.parse(null));
        assertSame(PortalRoomEntrance.TWO_WAY, PortalRoomEntrance.parse("  "));
        assertSame(PortalRoomEntrance.TWO_WAY, PortalRoomEntrance.parse("sideways"));
        assertSame(PortalRoomEntrance.ONE_WAY, PortalRoomEntrance.parse(" One_Way "));
        assertSame(PortalRoomEntrance.TWO_WAY, PortalRoomEntrance.DEFAULT);
    }

    @Test
    @DisplayName("The cycle button steps Two-way, One-way and back")
    void nextWraps() {
        assertSame(PortalRoomEntrance.ONE_WAY, PortalRoomEntrance.TWO_WAY.next());
        assertSame(PortalRoomEntrance.TWO_WAY, PortalRoomEntrance.ONE_WAY.next());
    }

    @Test
    @DisplayName("One-way refuses only the way back out of the original entrance hallway")
    void gateTruthTable() {
        PortalRoomEntrance one = PortalRoomEntrance.ONE_WAY;
        assertTrue(one.blocksMove(CARRIAGE, PortalCarriageRole.ENTRY, false));
        // The way in stays open.
        assertFalse(one.blocksMove(TWIN, PortalCarriageRole.ENTRY, false));
        // The exit hallway works both ways.
        assertFalse(one.blocksMove(CARRIAGE, PortalCarriageRole.EXIT, false));
        assertFalse(one.blocksMove(TWIN, PortalCarriageRole.EXIT, false));
        // Hallway copies scattered through an endless room stay ways out, entry copies included.
        assertFalse(one.blocksMove(CARRIAGE, PortalCarriageRole.ENTRY, true));
        assertFalse(one.blocksMove(CARRIAGE, PortalCarriageRole.EXIT, true));

        for (PortalCarriageRole role : PortalCarriageRole.values()) {
            for (boolean copy : new boolean[]{false, true}) {
                assertFalse(PortalRoomEntrance.TWO_WAY.blocksMove(CARRIAGE, role, copy));
                assertFalse(PortalRoomEntrance.TWO_WAY.blocksMove(TWIN, role, copy));
            }
        }
    }

    @Test
    @DisplayName("Tags from before the setting existed read as Two-way and round-trip unchanged")
    void oldTagsRoundTrip() {
        for (String tag : new String[]{"bedrock", "endless_repetition/dynamic", "chunk_dimension",
                "endless_open/single:minecraft:stone/off/off/off/day/sealed/0/9/0/7",
                "bedrock/exact/off/off/off/none/sealed/0/0/0/0/minecraft:obsidian/off/off"}) {
            PortalRoomSettings parsed = PortalRoomSettings.parse(tag);
            assertSame(PortalRoomEntrance.TWO_WAY, parsed.entrance(), tag);
            assertEquals(tag, parsed.toTag());
        }
    }

    @Test
    @DisplayName("One-way round-trips under every walls mode and leaves every other setting as it was")
    void oneWayRoundTrips() {
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            PortalRoomSettings before = PortalRoomSettings.parse(mode.id())
                .withFog(PortalRoomFog.OFF)
                .withDoorOffset(new PortalRoomDoorOffset(3));
            PortalRoomSettings oneWay = before.withEntrance(PortalRoomEntrance.ONE_WAY);
            PortalRoomSettings back = PortalRoomSettings.parse(oneWay.toTag());
            assertSame(PortalRoomEntrance.ONE_WAY, back.entrance(), oneWay.toTag());
            assertEquals(oneWay.toTag(), back.toTag());
            assertTrue(oneWay.toTag().endsWith("/one_way"), oneWay.toTag());
            assertEquals(before.fog(), back.fog());
            assertEquals(before.doorOffset(), back.doorOffset());
            assertEquals(before.mode(), back.mode());
            // Turning it back off returns the tag it had before.
            assertEquals(before.toTag(), back.withEntrance(PortalRoomEntrance.TWO_WAY).toTag());
        }
    }

    @Test
    @DisplayName("The setting survives the other editor controls, a walls change included")
    void survivesOtherWithers() {
        PortalRoomSettings oneWay = PortalRoomSettings.parse("endless_open").nextEntrance();
        assertSame(PortalRoomEntrance.ONE_WAY, oneWay.entrance());
        assertSame(PortalRoomEntrance.ONE_WAY, oneWay.withMode(PortalRoomMode.BEDROCK).entrance());
        assertSame(PortalRoomEntrance.ONE_WAY, oneWay.withFog(PortalRoomFog.ON).entrance());
        assertSame(PortalRoomEntrance.ONE_WAY, oneWay.nextGroundLevel().entrance());
        assertSame(PortalRoomEntrance.ONE_WAY, oneWay.withDrift(PortalRoomDrift.OFF).entrance());
        assertSame(PortalRoomEntrance.ONE_WAY, oneWay.withLockBlock("minecraft:stone").entrance());
    }

    @Test
    @DisplayName("The longest tag any one-way room can write still fits the editor status packet")
    void longestOneWayTagFits() {
        String longBlock = "minecraft:" + "a".repeat(PortalRoomCopies.BLOCK_ID_MAX - "minecraft:".length());
        String longest = "";
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            for (PortalRoomBooks.Kind books : PortalRoomBooks.Kind.values()) {
                for (PortalRoomSky sky : PortalRoomSky.values()) {
                    PortalRoomSettings s = new PortalRoomSettings(mode,
                        new PortalRoomCopies(PortalRoomCopies.Kind.SINGLE, longBlock),
                        PortalRoomContents.values()[PortalRoomContents.values().length - 1],
                        new PortalRoomExits(PortalRoomExits.Kind.RANDOM, PortalRoomExits.MAX_EVERY,
                            PortalRoomExits.MOVE_ALWAYS),
                        new PortalRoomBooks(books, PortalRoomBooks.MAX_WEIGHT, PortalRoomBooks.MAX_WEIGHT,
                            PortalRoomBooks.MAX_WEIGHT, PortalRoomBooks.MAX_BOOK_BOUND,
                            PortalRoomBooks.MAX_BOOK_BOUND),
                        sky)
                        .withDoorWall(PortalRoomDoorWall.values()[PortalRoomDoorWall.values().length - 1])
                        .withDoorOffset(new PortalRoomDoorOffset(-PortalRoomLayout.MAX_WIDTH))
                        .withDoorHeightOffset(new PortalRoomDoorHeightOffset(PortalRoomLayout.MAX_HEIGHT))
                        .withExitDoorOffset(new PortalRoomDoorOffset(PortalRoomLayout.MAX_WIDTH - 1))
                        .withExitDoorHeightOffset(new PortalRoomDoorHeightOffset(PortalRoomLayout.MAX_HEIGHT - 1))
                        .withLock(new PortalRoomLock("a".repeat(PortalRoomLock.BLOCK_ID_MAX)))
                        .withFog(PortalRoomFog.OFF)
                        .withDrift(PortalRoomDrift.OFF)
                        .withGround(new PortalRoomGround(PortalRoomGround.Level.DOOR, true))
                        .withEntrance(PortalRoomEntrance.ONE_WAY);
                    String tag = s.toTag();
                    if (tag.length() > longest.length()) longest = tag;
                }
            }
        }
        assertTrue(longest.length() <= games.brennan.dungeontrain.net.EditorStatusPacket.MODE_TAG_MAX,
            "longest one-way tag '" + longest + "' is " + longest.length() + " chars, over the packet cap");
    }

    @Test
    @DisplayName("The longest tag with a door distance still fits the editor status packet")
    void longestDoorDistanceTagFits() {
        String longBlock = "minecraft:" + "a".repeat(PortalRoomCopies.BLOCK_ID_MAX - "minecraft:".length());
        String longest = "";
        for (PortalRoomMode mode : PortalRoomMode.values()) {
            for (PortalRoomBooks.Kind books : PortalRoomBooks.Kind.values()) {
                for (PortalRoomSky sky : PortalRoomSky.values()) {
                    PortalRoomSettings s = new PortalRoomSettings(mode,
                        new PortalRoomCopies(PortalRoomCopies.Kind.SINGLE, longBlock),
                        PortalRoomContents.values()[PortalRoomContents.values().length - 1],
                        new PortalRoomExits(PortalRoomExits.Kind.RANDOM, PortalRoomExits.MAX_EVERY,
                            PortalRoomExits.MOVE_ALWAYS),
                        new PortalRoomBooks(books, PortalRoomBooks.MAX_WEIGHT, PortalRoomBooks.MAX_WEIGHT,
                            PortalRoomBooks.MAX_WEIGHT, PortalRoomBooks.MAX_BOOK_BOUND,
                            PortalRoomBooks.MAX_BOOK_BOUND),
                        sky)
                        .withDoorWall(PortalRoomDoorWall.values()[PortalRoomDoorWall.values().length - 1])
                        .withDoorOffset(new PortalRoomDoorOffset(-PortalRoomLayout.MAX_WIDTH))
                        .withDoorHeightOffset(new PortalRoomDoorHeightOffset(PortalRoomLayout.MAX_HEIGHT))
                        .withExitDoorOffset(new PortalRoomDoorOffset(PortalRoomLayout.MAX_WIDTH - 1))
                        .withExitDoorHeightOffset(new PortalRoomDoorHeightOffset(PortalRoomLayout.MAX_HEIGHT - 1))
                        .withLock(new PortalRoomLock("a".repeat(PortalRoomLock.BLOCK_ID_MAX)))
                        .withFog(PortalRoomFog.OFF)
                        .withDrift(PortalRoomDrift.OFF)
                        .withGround(new PortalRoomGround(PortalRoomGround.Level.DOOR, true))
                        .withEntrance(PortalRoomEntrance.ONE_WAY)
                        .withDoorDistance(new PortalRoomDoorDistance(PortalRoomDoorDistance.MAX));
                    String tag = s.toTag();
                    if (tag.length() > longest.length()) longest = tag;
                }
            }
        }
        assertTrue(longest.length() <= games.brennan.dungeontrain.net.EditorStatusPacket.MODE_TAG_MAX,
            "longest door-distance tag '" + longest + "' is " + longest.length() + " chars, over the packet cap");
    }
}
