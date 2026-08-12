package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.net.PortalTrainAudioPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine follows the player into the corridor copy at the volume they had aboard, and is gone
 * three blocks into the room.
 */
class ClientPortalTrainAudioTest {

    /**
     * A default-dims structure at the world floor: 9-long corridors at X 0 and X 20 (an 11-block
     * room between them), 7 tall and 7 wide, in a structure box with room to walk around in.
     */
    private static final PortalTrainAudioPacket STRUCTURE = new PortalTrainAudioPacket(
        -10, -61, -20, 40, -45, 20,
        0, -60, 0,
        20, -60, 0,
        9, 7, 7,
        3.0f);

    /** Walkway height inside a corridor whose floor blocks sit at the origin's Y. */
    private static final double FEET = -59.0;

    @BeforeEach
    void clear() {
        ClientPortalTrainAudio.reset();
    }

    @Test
    @DisplayName("a fresh client says nothing — the ordinary distance curve stays in charge")
    void freshClientDefersToTheDistanceCurve() {
        assertEquals(ClientPortalTrainAudio.NOT_APPLICABLE,
            ClientPortalTrainAudio.volumeAt(4, FEET, 3));
    }

    @Test
    @DisplayName("inside the entry corridor the engine is at full volume, as it was in the carriage")
    void entryCorridorIsFullVolume() {
        ClientPortalTrainAudio.update(STRUCTURE);
        assertEquals(1.0f, ClientPortalTrainAudio.volumeAt(4.5, FEET, 3.5));
    }

    @Test
    @DisplayName("the exit corridor sounds exactly like the entry one — the rule is symmetric")
    void exitCorridorIsFullVolumeToo() {
        ClientPortalTrainAudio.update(STRUCTURE);
        assertEquals(1.0f, ClientPortalTrainAudio.volumeAt(24.5, FEET, 3.5));
    }

    @Test
    @DisplayName("stepping out of the mouth fades linearly, and is silent by three blocks in")
    void fadesOverThreeBlocks() {
        ClientPortalTrainAudio.update(STRUCTURE);
        // The corridor ends at X 9, padded by half a block; distances are measured from there.
        assertEquals(0.67f, ClientPortalTrainAudio.volumeAt(10.5, FEET, 3.5), 0.02f);
        assertEquals(0.33f, ClientPortalTrainAudio.volumeAt(11.5, FEET, 3.5), 0.02f);
        assertEquals(0.0f, ClientPortalTrainAudio.volumeAt(12.5, FEET, 3.5));
    }

    @Test
    @DisplayName("deep in the room it stays silent rather than handing back to the distance curve")
    void deepInTheRoomStaysSilent() {
        ClientPortalTrainAudio.update(STRUCTURE);
        // Mid-room, and off to the side in a tiled copy. The real train is directly overhead, well
        // inside the distance curve's range — deferring here is what used to make it audible.
        assertEquals(0.0f, ClientPortalTrainAudio.volumeAt(15, FEET, 3.5));
        assertEquals(0.0f, ClientPortalTrainAudio.volumeAt(15, FEET, 18));
    }

    @Test
    @DisplayName("walking out of the structure hands the engine back with no message from the server")
    void steppingOutDefersAgain() {
        ClientPortalTrainAudio.update(STRUCTURE);
        assertEquals(1.0f, ClientPortalTrainAudio.volumeAt(4.5, FEET, 3.5));

        // Same cached region, player back on the train. This is also what makes a disconnect inside a
        // corridor safe: nothing has to arrive for the rule to stop applying.
        assertEquals(ClientPortalTrainAudio.NOT_APPLICABLE,
            ClientPortalTrainAudio.volumeAt(900, 80, 900));
    }

    @Test
    @DisplayName("a none packet is how the server hands the engine back")
    void nonePacketDefers() {
        ClientPortalTrainAudio.update(STRUCTURE);
        ClientPortalTrainAudio.update(PortalTrainAudioPacket.none());
        assertEquals(ClientPortalTrainAudio.NOT_APPLICABLE,
            ClientPortalTrainAudio.volumeAt(4.5, FEET, 3.5));
    }

    @Test
    @DisplayName("reset drops the region outright — a structure never leaks into the next world")
    void resetDropsTheRegion() {
        ClientPortalTrainAudio.update(STRUCTURE);
        ClientPortalTrainAudio.reset();
        assertEquals(ClientPortalTrainAudio.NOT_APPLICABLE,
            ClientPortalTrainAudio.volumeAt(4.5, FEET, 3.5));
    }

    @Test
    @DisplayName("a null update is treated as none rather than throwing on the next tick")
    void nullUpdateIsNone() {
        ClientPortalTrainAudio.update(STRUCTURE);
        ClientPortalTrainAudio.update(null);
        assertEquals(ClientPortalTrainAudio.NOT_APPLICABLE,
            ClientPortalTrainAudio.volumeAt(4.5, FEET, 3.5));
    }

    @Test
    @DisplayName("standing in the doorway still reads as inside, not a flicker into the fade")
    void doorwayCountsAsInside() {
        ClientPortalTrainAudio.update(STRUCTURE);
        assertEquals(1.0f, ClientPortalTrainAudio.volumeAt(9.4, FEET, 3.5));
    }

    @Test
    @DisplayName("the fade is monotonic — walking away never gets louder")
    void fadeIsMonotonic() {
        ClientPortalTrainAudio.update(STRUCTURE);
        float previous = Float.MAX_VALUE;
        for (double x = 9.5; x <= 13.0; x += 0.25) {
            float v = ClientPortalTrainAudio.volumeAt(x, FEET, 3.5);
            assertTrue(v <= previous, "volume rose at x=" + x + ": " + previous + " → " + v);
            previous = v;
        }
    }
}
