package games.brennan.dungeontrain.ship.sable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReplayRecordingPlayersTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @BeforeEach
    @AfterEach
    void reset() {
        ReplayRecordingPlayers.clear();
    }

    @Test
    void nobodyRecordsByDefault() {
        assertFalse(ReplayRecordingPlayers.isRecording(ALICE));
        assertEquals(0, ReplayRecordingPlayers.count());
    }

    @Test
    void setTrueFlagsOnlyThatPlayer() {
        ReplayRecordingPlayers.set(ALICE, true);
        assertTrue(ReplayRecordingPlayers.isRecording(ALICE));
        assertFalse(ReplayRecordingPlayers.isRecording(BOB));
        assertEquals(1, ReplayRecordingPlayers.count());
    }

    @Test
    void setFalseClearsAndIsIdempotent() {
        ReplayRecordingPlayers.set(ALICE, true);
        ReplayRecordingPlayers.set(ALICE, true);
        assertEquals(1, ReplayRecordingPlayers.count());
        ReplayRecordingPlayers.set(ALICE, false);
        ReplayRecordingPlayers.set(ALICE, false);
        assertFalse(ReplayRecordingPlayers.isRecording(ALICE));
        assertEquals(0, ReplayRecordingPlayers.count());
    }

    @Test
    void clearForgetsEveryone() {
        ReplayRecordingPlayers.set(ALICE, true);
        ReplayRecordingPlayers.set(BOB, true);
        ReplayRecordingPlayers.clear();
        assertFalse(ReplayRecordingPlayers.isRecording(ALICE));
        assertFalse(ReplayRecordingPlayers.isRecording(BOB));
    }
}
