package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure qualification logic for the developer "first new world after first death" ping
 * ({@link DevPingService#decideMarker}). No Minecraft / Discord / file I/O — the state machine
 * is verified deterministically.
 */
class DevPingServiceTest {

    private static final String TOKEN = "{{dt:dev_ping}}";
    private static final long DEATH = 1_000L;

    @Test
    void dormantWhenTokenBlank() {
        // Blank token = feature off, even for an otherwise-qualifying player.
        assertEquals("", DevPingService.decideMarker("", DEATH, DEATH + 1, false));
    }

    @Test
    void nothingWhenPlayerNeverDied() {
        assertEquals("", DevPingService.decideMarker(TOKEN, 0L, DEATH + 1, false));
    }

    @Test
    void nothingForWorldCreatedBeforeDeath() {
        // The world they died in (created earlier) — re-joining it must not ping.
        assertEquals("", DevPingService.decideMarker(TOKEN, DEATH, DEATH - 1, false));
    }

    @Test
    void nothingForWorldCreatedExactlyAtDeath() {
        // Strict "after": equal timestamps do not count as a new world.
        assertEquals("", DevPingService.decideMarker(TOKEN, DEATH, DEATH, false));
    }

    @Test
    void emitsForNewWorldStartedAfterDeath() {
        assertEquals(TOKEN, DevPingService.decideMarker(TOKEN, DEATH, DEATH + 1, false));
    }

    @Test
    void nothingWhenAlreadyPinged() {
        // Once per player, ever.
        assertEquals("", DevPingService.decideMarker(TOKEN, DEATH, DEATH + 1, true));
    }
}
