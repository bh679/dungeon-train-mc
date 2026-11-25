package games.brennan.dungeontrain.portal.tribute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomStandJobTest {

    @Test
    void nothingIsOverdueUntilNextWasPressed() {
        assertFalse(TributeRoomStandJob.beginOverdue(false, 0L, 10_000_000L));
    }

    @Test
    void aVisitAskedForGivesUpAtTheDeadline() {
        long at = 1_000L;
        assertFalse(TributeRoomStandJob.beginOverdue(true, at, at + TributeRoomStandJob.BEGIN_DEADLINE_MS - 1));
        assertTrue(TributeRoomStandJob.beginOverdue(true, at, at + TributeRoomStandJob.BEGIN_DEADLINE_MS));
    }

    @Test
    void serverGivesUpBeforeTheClientDoes() {
        // The client's own wait (TributeTvVisit.ROOM_TIMEOUT_MS); the server must answer none before it falls back.
        long clientTimeoutMs = 20_000L;
        assertTrue(TributeRoomStandJob.BEGIN_DEADLINE_MS < clientTimeoutMs - 2_000L);
    }
}
