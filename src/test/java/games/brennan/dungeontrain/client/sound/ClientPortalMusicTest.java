package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.net.PortalTrainAudioPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The soundtrack fades out over the last stretch of a portal corridor, is gone by the doorway, and
 * stays gone for the whole room.
 */
class ClientPortalMusicTest {

    /**
     * The same default-dims structure {@link ClientPortalTrainAudioTest} uses: 9-long corridors at
     * X 0 and X 20 with an 11-block room between them, 7 tall and 7 wide.
     *
     * <p>The entry corridor's room-facing door is therefore at X 9.5 (padded), the exit corridor's
     * at X 19.5, and the fade span is the whole 8 blocks — a 9-long corridor does not clamp it.</p>
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
        ClientPortalMusic.reset();
    }

    @Test
    @DisplayName("a fresh client does not touch the music")
    void freshClientLeavesMusicAlone() {
        assertEquals(1.0f, ClientPortalMusic.targetFactorAt(4.5, FEET, 3.5));
        assertEquals(1.0f, ClientPortalMusic.current());
    }

    @Test
    @DisplayName("outside the structure the music plays as normal — this is the train")
    void outsideTheStructureIsUntouched() {
        ClientPortalTrainAudio.update(STRUCTURE);
        assertEquals(1.0f, ClientPortalMusic.targetFactorAt(900, 80, 900));
    }

    @Test
    @DisplayName("the train-side end of a corridor is still at full volume")
    void trainSideEndIsFullVolume() {
        ClientPortalTrainAudio.update(STRUCTURE);
        // 8 blocks or more from the door at X 9.5.
        assertEquals(1.0f, ClientPortalMusic.targetFactorAt(1.5, FEET, 3.5));
        assertEquals(1.0f, ClientPortalMusic.targetFactorAt(-0.4, FEET, 3.5));
    }

    @Test
    @DisplayName("walking the entry corridor fades linearly to nothing at the door")
    void entryCorridorFadesToTheDoor() {
        ClientPortalTrainAudio.update(STRUCTURE);
        assertEquals(0.75f, ClientPortalMusic.targetFactorAt(3.5, FEET, 3.5), 0.001f);
        assertEquals(0.5f, ClientPortalMusic.targetFactorAt(5.5, FEET, 3.5), 0.001f);
        assertEquals(0.25f, ClientPortalMusic.targetFactorAt(7.5, FEET, 3.5), 0.001f);
        assertEquals(0.0f, ClientPortalMusic.targetFactorAt(9.5, FEET, 3.5));
    }

    @Test
    @DisplayName("the exit corridor fades the other way — its door is the one facing the room")
    void exitCorridorFadesTowardsLowX() {
        ClientPortalTrainAudio.update(STRUCTURE);
        assertEquals(0.0f, ClientPortalMusic.targetFactorAt(19.5, FEET, 3.5));
        assertEquals(0.25f, ClientPortalMusic.targetFactorAt(21.5, FEET, 3.5), 0.001f);
        assertEquals(0.75f, ClientPortalMusic.targetFactorAt(25.5, FEET, 3.5), 0.001f);
        assertEquals(1.0f, ClientPortalMusic.targetFactorAt(27.5, FEET, 3.5));
    }

    @Test
    @DisplayName("the room is silent, base copy and tiled copies alike")
    void theRoomIsSilent() {
        ClientPortalTrainAudio.update(STRUCTURE);
        assertEquals(0.0f, ClientPortalMusic.targetFactorAt(15, FEET, 3.5));
        assertEquals(0.0f, ClientPortalMusic.targetFactorAt(15, FEET, 18));
        assertEquals(0.0f, ClientPortalMusic.targetFactorAt(15, -50, 3.5));
    }

    @Test
    @DisplayName("a corridor shorter than the fade band fades over its own length instead")
    void shortCorridorClampsTheSpan() {
        // A 7-long corridor: door at X 7.5, so 7 blocks back is its far end and must be full volume.
        PortalTrainAudioPacket small = new PortalTrainAudioPacket(
            -10, -61, -20, 40, -45, 20,
            0, -60, 0,
            20, -60, 0,
            7, 5, 5,
            3.0f);
        ClientPortalTrainAudio.update(small);
        assertEquals(1.0f, ClientPortalMusic.targetFactorAt(0.5, FEET, 2.5));
        assertEquals(0.5f, ClientPortalMusic.targetFactorAt(4.0, FEET, 2.5), 0.001f);
    }

    @Test
    @DisplayName("the fade is monotonic — walking towards the door never gets louder")
    void fadeIsMonotonic() {
        ClientPortalTrainAudio.update(STRUCTURE);
        float previous = Float.MAX_VALUE;
        // From the corridor's train-side mouth to mid-room. Further back than that is the plug, a
        // solid block a player only ever leaves by being swapped back into the carriage.
        for (double x = -0.5; x <= 15.0; x += 0.25) {
            float v = ClientPortalMusic.targetFactorAt(x, FEET, 3.5);
            assertTrue(v <= previous, "music rose at x=" + x + ": " + previous + " → " + v);
            previous = v;
        }
    }

    @Test
    @DisplayName("a none packet, a null packet and a reset all hand the music back")
    void clearedRegionsHandTheMusicBack() {
        ClientPortalTrainAudio.update(STRUCTURE);
        ClientPortalTrainAudio.update(PortalTrainAudioPacket.none());
        assertEquals(1.0f, ClientPortalMusic.targetFactorAt(9.5, FEET, 3.5));

        ClientPortalTrainAudio.update(STRUCTURE);
        ClientPortalTrainAudio.update(null);
        assertEquals(1.0f, ClientPortalMusic.targetFactorAt(9.5, FEET, 3.5));

        ClientPortalTrainAudio.update(STRUCTURE);
        ClientPortalTrainAudio.reset();
        assertEquals(1.0f, ClientPortalMusic.targetFactorAt(9.5, FEET, 3.5));
    }

    @Test
    @DisplayName("the ease converges on the target and lands exactly on it")
    void easeConverges() {
        for (int i = 0; i < 200; i++) ClientPortalMusic.tick(0.0f);
        assertEquals(0.0f, ClientPortalMusic.current());

        for (int i = 0; i < 200; i++) ClientPortalMusic.tick(1.0f);
        assertEquals(1.0f, ClientPortalMusic.current());
    }

    @Test
    @DisplayName("the ease never overshoots, so a swap cannot make the music jump loud")
    void easeNeverOvershoots() {
        ClientPortalMusic.tick(0.0f);
        float previous = ClientPortalMusic.current();
        assertTrue(previous < 1.0f && previous > 0.0f, "first eased step was " + previous);
        for (int i = 0; i < 50; i++) {
            float v = ClientPortalMusic.tick(0.0f);
            assertTrue(v <= previous, "ease rose: " + previous + " → " + v);
            assertTrue(v >= 0.0f, "ease undershot: " + v);
            previous = v;
        }
    }

    @Test
    @DisplayName("reset puts the applied factor back to silence-free")
    void resetRestoresFullVolume() {
        for (int i = 0; i < 200; i++) ClientPortalMusic.tick(0.0f);
        ClientPortalMusic.reset();
        assertEquals(1.0f, ClientPortalMusic.current());
    }
}
