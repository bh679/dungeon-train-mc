package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seal block's own segment grammar — bedrock unless the author said otherwise, and total on the
 * way in, because the tag it rides is hand-editable on disk.
 */
class PortalRoomLockTest {

    @Test
    @DisplayName("Nothing said means bedrock, which is what every sealed room already was")
    void defaultsToBedrock() {
        assertEquals(PortalRoomLock.DEFAULT_BLOCK, PortalRoomLock.parse(null).blockId());
        assertEquals(PortalRoomLock.DEFAULT_BLOCK, PortalRoomLock.parse("").blockId());
        assertEquals(PortalRoomLock.DEFAULT_BLOCK, PortalRoomLock.parse("   ").blockId());
        assertEquals(PortalRoomLock.DEFAULT_BLOCK, new PortalRoomLock(null).blockId());
    }

    @Test
    @DisplayName("A block id round-trips through the segment, normalised")
    void blockRoundTrips() {
        assertEquals("minecraft:obsidian", PortalRoomLock.parse(" Minecraft:Obsidian ").id());
        assertEquals("minecraft:obsidian",
            PortalRoomLock.DEFAULT.withBlock("minecraft:obsidian").blockId());
    }

    @Test
    @DisplayName("An id longer than the tag can carry reads back as bedrock rather than as itself")
    void overlongIdFallsBackToTheDefault() {
        String tooLong = "a".repeat(PortalRoomLock.BLOCK_ID_MAX + 1);
        assertEquals(PortalRoomLock.DEFAULT_BLOCK, PortalRoomLock.parse(tooLong).blockId());
    }

    @Test
    @DisplayName("Air is a value of its own — the author asking for no shell at all")
    void airIsAValue() {
        assertTrue(PortalRoomLock.parse(PortalRoomLock.AIR_BLOCK).isAir());
        assertFalse(PortalRoomLock.DEFAULT.isAir());
    }
}
