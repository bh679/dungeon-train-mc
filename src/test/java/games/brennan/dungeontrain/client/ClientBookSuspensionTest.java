package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client-side pause mirror the signing screen reads. Pure clock arithmetic — no Minecraft.
 */
class ClientBookSuspensionTest {

    @BeforeEach
    void reset() {
        ClientBookSuspension.clear();
    }

    @Test
    void aFreshClientIsNotSuspended() {
        assertFalse(ClientBookSuspension.isSuspended());
        assertEquals(0L, ClientBookSuspension.remainingSec());
        assertEquals(0, ClientBookSuspension.strikes());
    }

    @Test
    void aSyncedWindowSuspendsAndKeepsTheStrikeCount() {
        ClientBookSuspension.set(30L, 2);
        assertTrue(ClientBookSuspension.isSuspended());
        assertEquals(30L, ClientBookSuspension.remainingSec());
        assertEquals(2, ClientBookSuspension.strikes());
    }

    @Test
    void zeroOrNegativeRemainingClearsTheWindow() {
        ClientBookSuspension.set(30L, 1);
        ClientBookSuspension.set(0L, 0);
        assertFalse(ClientBookSuspension.isSuspended(), "the relay lifting a pause must free the button");
        ClientBookSuspension.set(-5L, 0);
        assertFalse(ClientBookSuspension.isSuspended());
    }

    @Test
    void remainingSecRoundsUpSoTheButtonNeverReadsZeroWhileStillDead() {
        ClientBookSuspension.set(1L, 1);
        assertEquals(1L, ClientBookSuspension.remainingSec());
        assertTrue(ClientBookSuspension.isSuspended());
    }

    @Test
    void aLaterSyncReplacesTheEarlierWindow() {
        ClientBookSuspension.set(600L, 1);
        ClientBookSuspension.set(5L, 2);
        assertTrue(ClientBookSuspension.remainingSec() <= 5L, "the newest word from the server wins");
    }
}
