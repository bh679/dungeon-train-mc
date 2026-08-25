package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local mirror of the relay's upload pause. No Minecraft runtime — this is a clock and a map.
 */
class BookUploadSuspensionsTest {

    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void reset() {
        BookUploadSuspensions.clearAll();
    }

    @Test
    void anUnknownPlayerIsNeverSuspended() {
        assertFalse(BookUploadSuspensions.isSuspended(PLAYER));
        assertEquals(0L, BookUploadSuspensions.remainingSec(PLAYER));
        assertFalse(BookUploadSuspensions.isSuspended(null), "a null player is not a crash");
    }

    @Test
    void anOpenWindowSuspendsOnlyThatPlayer() {
        BookUploadSuspensions.apply(PLAYER, System.currentTimeMillis() + 30_000L, 1);
        assertTrue(BookUploadSuspensions.isSuspended(PLAYER));
        assertFalse(BookUploadSuspensions.isSuspended(OTHER));
    }

    @Test
    void remainingSecRoundsUpSoASubSecondRemainderIsNeverZero() {
        BookUploadSuspensions.apply(PLAYER, System.currentTimeMillis() + 200L, 1);
        assertEquals(1L, BookUploadSuspensions.remainingSec(PLAYER));
    }

    @Test
    void aDeadlineAlreadyPastClearsRatherThanSuspends() {
        BookUploadSuspensions.apply(PLAYER, System.currentTimeMillis() - 1L, 3);
        assertFalse(BookUploadSuspensions.isSuspended(PLAYER));
    }

    @Test
    void aLaterVerdictReplacesTheEarlierWindow() {
        long now = System.currentTimeMillis();
        BookUploadSuspensions.apply(PLAYER, now + 30_000L, 1);
        BookUploadSuspensions.apply(PLAYER, now + 120_000L, 2);
        assertTrue(BookUploadSuspensions.remainingSec(PLAYER) > 60L, "the relay's newest word wins");
    }

    @Test
    void clearForgetsTheWindow() {
        BookUploadSuspensions.apply(PLAYER, System.currentTimeMillis() + 30_000L, 1);
        BookUploadSuspensions.clear(PLAYER);
        assertFalse(BookUploadSuspensions.isSuspended(PLAYER));
    }
}
