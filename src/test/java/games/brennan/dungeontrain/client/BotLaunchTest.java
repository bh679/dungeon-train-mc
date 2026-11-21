package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The launch value parser for {@link BotLaunch}. */
final class BotLaunchTest {

    @Test
    void unsetOrUnknownIsOff() {
        assertEquals(BotLaunch.Mode.OFF, BotLaunch.parse(null));
        assertEquals(BotLaunch.Mode.OFF, BotLaunch.parse(""));
        assertEquals(BotLaunch.Mode.OFF, BotLaunch.parse("false"));
        assertEquals(BotLaunch.Mode.OFF, BotLaunch.parse("granted"));
    }

    @Test
    void denyAliases() {
        for (String v : new String[] {"deny", "DENY", " 1 ", "true", "yes"}) {
            assertEquals(BotLaunch.Mode.DENY, BotLaunch.parse(v), v);
        }
    }

    @Test
    void grant() {
        assertEquals(BotLaunch.Mode.GRANT, BotLaunch.parse("grant"));
        assertEquals(BotLaunch.Mode.GRANT, BotLaunch.parse("Grant"));
    }
}
