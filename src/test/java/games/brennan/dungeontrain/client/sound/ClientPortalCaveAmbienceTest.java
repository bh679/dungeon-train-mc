package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.net.PortalTrainAudioPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cave noises play in the portal room — never in the corridors, never on the tick you walk in, and
 * on a fresh roll each visit.
 */
class ClientPortalCaveAmbienceTest {

    /**
     * The same default-dims structure {@link ClientPortalMusicTest} uses: 9-long corridors at X 0
     * and X 20 with an 11-block room between them, 7 tall and 7 wide.
     */
    private static final PortalTrainAudioPacket STRUCTURE = new PortalTrainAudioPacket(
        -10, -61, -20, 40, -45, 20,
        0, -60, 0,
        20, -60, 0,
        9, 7, 7,
        3.0f);

    /** Walkway height inside a corridor whose floor blocks sit at the origin's Y. */
    private static final double FEET = -59.0;

    /** Somewhere in the middle of the room, between the two corridors. */
    private static final double ROOM_X = 15.0;

    @BeforeEach
    void clear() {
        ClientPortalTrainAudio.reset();
        ClientPortalCaveAmbience.reset();
    }

    @Test
    @DisplayName("a fresh client is in no room at all")
    void freshClientIsNowhere() {
        assertFalse(ClientPortalTrainAudio.inRoom(ROOM_X, FEET, 3.5));
    }

    @Test
    @DisplayName("the room is the structure minus its two corridors")
    void roomIsTheStructureMinusTheCorridors() {
        ClientPortalTrainAudio.update(STRUCTURE);

        assertTrue(ClientPortalTrainAudio.inRoom(ROOM_X, FEET, 3.5), "between the corridors");
        assertTrue(ClientPortalTrainAudio.inRoom(35.0, FEET, 3.5), "a tiled copy past the exit");

        assertFalse(ClientPortalTrainAudio.inRoom(4.5, FEET, 3.5), "inside the entry corridor");
        assertFalse(ClientPortalTrainAudio.inRoom(24.5, FEET, 3.5), "inside the exit corridor");
        assertFalse(ClientPortalTrainAudio.inRoom(900, 80, 900), "out on the train");
    }

    @Test
    @DisplayName("walking in arms the timer rather than firing it")
    void enteringDoesNotFire() {
        assertFalse(ClientPortalCaveAmbience.tick(true, 0.0));
    }

    @Test
    @DisplayName("the shortest roll fires after the minimum gap, and not a tick before")
    void firesWhenTheGapRunsOut() {
        assertFalse(ClientPortalCaveAmbience.tick(true, 0.0));
        for (int i = 1; i < ClientPortalCaveAmbience.MIN_GAP_TICKS; i++) {
            assertFalse(ClientPortalCaveAmbience.tick(true, 0.0), "fired early on tick " + i);
        }
        assertTrue(ClientPortalCaveAmbience.tick(true, 0.0), "should fire once the gap elapses");
    }

    @Test
    @DisplayName("firing re-arms, so a long stay keeps making noises")
    void firingReArms() {
        ClientPortalCaveAmbience.tick(true, 0.0);
        for (int i = 1; i < ClientPortalCaveAmbience.MIN_GAP_TICKS; i++) {
            ClientPortalCaveAmbience.tick(true, 0.0);
        }
        assertTrue(ClientPortalCaveAmbience.tick(true, 0.0), "first noise");

        for (int i = 1; i < ClientPortalCaveAmbience.MIN_GAP_TICKS; i++) {
            assertFalse(ClientPortalCaveAmbience.tick(true, 0.0), "fired early on tick " + i);
        }
        assertTrue(ClientPortalCaveAmbience.tick(true, 0.0), "second noise, a whole gap later");
    }

    @Test
    @DisplayName("leaving disarms — a part-elapsed gap does not carry over to the next visit")
    void leavingDisarms() {
        ClientPortalCaveAmbience.tick(true, 0.0);
        for (int i = 1; i < ClientPortalCaveAmbience.MIN_GAP_TICKS; i++) {
            ClientPortalCaveAmbience.tick(true, 0.0);
        }
        // One tick short of a noise. Step out onto the train, then back in.
        assertFalse(ClientPortalCaveAmbience.tick(false, 0.0));
        assertFalse(ClientPortalCaveAmbience.tick(true, 0.0), "re-entry rolls a fresh gap");

        for (int i = 1; i < ClientPortalCaveAmbience.MIN_GAP_TICKS; i++) {
            assertFalse(ClientPortalCaveAmbience.tick(true, 0.0), "fired early on tick " + i);
        }
        assertTrue(ClientPortalCaveAmbience.tick(true, 0.0));
    }

    @Test
    @DisplayName("outside a room nothing ever fires, however long the player is out there")
    void outsideNeverFires() {
        for (int i = 0; i < ClientPortalCaveAmbience.MAX_GAP_TICKS * 2; i++) {
            assertFalse(ClientPortalCaveAmbience.tick(false, 0.0));
        }
    }

    @Test
    @DisplayName("the rolled gap spans 30 to 180 seconds, ends included")
    void gapSpansTheWholeRange() {
        assertEquals(ClientPortalCaveAmbience.MIN_GAP_TICKS, ClientPortalCaveAmbience.gapFrom(0.0));
        assertEquals(ClientPortalCaveAmbience.MAX_GAP_TICKS,
            ClientPortalCaveAmbience.gapFrom(Math.nextDown(1.0)));
        // Out-of-range values clamp rather than escaping the band.
        assertEquals(ClientPortalCaveAmbience.MIN_GAP_TICKS, ClientPortalCaveAmbience.gapFrom(-1.0));
        assertEquals(ClientPortalCaveAmbience.MAX_GAP_TICKS, ClientPortalCaveAmbience.gapFrom(1.0));

        int mid = ClientPortalCaveAmbience.gapFrom(0.5);
        assertTrue(mid > ClientPortalCaveAmbience.MIN_GAP_TICKS
            && mid < ClientPortalCaveAmbience.MAX_GAP_TICKS, "half a roll lands mid-band: " + mid);
    }
}
