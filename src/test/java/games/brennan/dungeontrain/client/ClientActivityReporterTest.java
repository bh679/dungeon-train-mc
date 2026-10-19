package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rate limiter on menu-activity reports. Trivial arithmetic with one trap: seeding
 * "never sent" as {@link Integer#MIN_VALUE} overflows the subtraction and wedges the limiter
 * shut, which silently stopped every menu-mouse report from reaching the server.
 */
class ClientActivityReporterTest {

    private static final int COOLDOWN = 20;

    @Test
    @DisplayName("a report is due once the cooldown has elapsed, and not before")
    void cooldownBoundary() {
        assertFalse(ClientActivityReporter.due(19, 0, COOLDOWN));
        assertTrue(ClientActivityReporter.due(20, 0, COOLDOWN));
        assertTrue(ClientActivityReporter.due(1_000, 500, COOLDOWN));
    }

    @Test
    @DisplayName("the never-sent seed makes the first report due immediately")
    void freshSeedIsDue() {
        // What the class actually seeds with: -COOLDOWN, at the very first tick.
        assertTrue(ClientActivityReporter.due(1, -COOLDOWN, COOLDOWN));
        assertTrue(ClientActivityReporter.due(0, -COOLDOWN, COOLDOWN));
    }

    @Test
    @DisplayName("MIN_VALUE as the seed overflows and reports nothing — the bug this pins")
    void minValueSeedOverflows() {
        assertFalse(ClientActivityReporter.due(5, Integer.MIN_VALUE, COOLDOWN));
    }

    @Test
    @DisplayName("closing to the world, or to the screen that replaced it")
    void nextScreenAfterClosing() {
        Object closing = new Object();
        Object replacement = new Object();
        assertNull(ClientActivityReporter.nextScreen(null, closing));
        assertSame(replacement, ClientActivityReporter.nextScreen(replacement, closing));
        // Left over from when the closing screen itself opened, over the world.
        assertNull(ClientActivityReporter.nextScreen(closing, closing));
    }

    @Test
    @DisplayName("pause length never goes negative or overflows")
    void pausedForMsClamps() {
        assertEquals(1500, ClientActivityReporter.pausedForMs(11_500L, 10_000L));
        assertEquals(0, ClientActivityReporter.pausedForMs(9_000L, 10_000L));
        assertEquals(Integer.MAX_VALUE, ClientActivityReporter.pausedForMs(Long.MAX_VALUE, 0L));
    }

    @Test
    @DisplayName("a new book page moves presence to now, inside the opening's 60 minutes")
    void newPageRenewsPresence() {
        assertEquals(400_000L, ClientActivityReporter.renewedPresenceSince(10_000L, 10_000L, 400_000L));
        long justInside = 10_000L + ClientActivityReporter.BOOK_PRESENCE_MAX_MS - 1L;
        assertEquals(justInside, ClientActivityReporter.renewedPresenceSince(10_000L, 10_000L, justInside));
    }

    @Test
    @DisplayName("past 60 minutes into one opening, new pages stop renewing presence")
    void renewalCappedPerOpening() {
        long atCap = 10_000L + ClientActivityReporter.BOOK_PRESENCE_MAX_MS;
        assertEquals(2_000_000L, ClientActivityReporter.renewedPresenceSince(2_000_000L, 10_000L, atCap));
    }
}

