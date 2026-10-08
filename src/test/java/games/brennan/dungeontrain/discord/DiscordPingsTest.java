package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordPingsTest {

    @Test
    @DisplayName("the hint joins an existing query with & and a bare URL with ?")
    void withParam() {
        assertEquals("https://r/cap/hook?ping=abc", DiscordPings.withParam("https://r/cap/hook", "ping=abc"));
        assertEquals("https://r/cap/hook?x=1&ping=abc", DiscordPings.withParam("https://r/cap/hook?x=1", "ping=abc"));
    }

    @Test
    @DisplayName("the photo cap is the relay base URL's last path segment, and only a safe one")
    void capSegment() {
        assertEquals("abcDEF_12-x", DiscordPings.capSegment("https://brennan.games/dp/abcDEF_12-x"));
        assertEquals("abc", DiscordPings.capSegment("https://brennan.games/dp/abc/"));
        assertEquals("", DiscordPings.capSegment("https://brennan.games/dp/a b"));
        assertEquals("", DiscordPings.capSegment(null));
    }

    @Test
    @DisplayName("uuids go on the wire undashed, the relay's normal form")
    void undashed() {
        assertEquals("069a79f444e94726a5befca90e38aaf5",
                DiscordPings.undashed(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
    }
}
