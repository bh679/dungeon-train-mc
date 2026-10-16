package games.brennan.dungeontrain.client.twitch;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What counts as a Twitch handle in a chat line. */
class TwitchHandleTest {

    @Test
    void urlsInAnyForm() {
        assertEquals(Optional.of("steve_streams"), TwitchHandle.find("my channel is https://www.twitch.tv/Steve_Streams come say hi", false));
        assertEquals(Optional.of("steve_streams"), TwitchHandle.find("twitch.tv/steve_streams", false));
        assertEquals(Optional.of("steve_streams"), TwitchHandle.find("https://m.twitch.tv/steve_streams/", false));
        assertEquals(Optional.empty(), TwitchHandle.find("https://www.twitch.tv/videos/12345", false), "a VOD, not a channel");
        assertEquals(Optional.empty(), TwitchHandle.find("https://www.twitch.tv/directory/game/Minecraft", false), "a site route");
    }

    @Test
    void atHandles() {
        assertEquals(Optional.of("steve_streams"), TwitchHandle.find("follow me @Steve_Streams", false));
        assertEquals(Optional.empty(), TwitchHandle.find("mail me a@b.c", false), "not an @ at a word start");
        assertEquals(Optional.of("steve_streams"), TwitchHandle.find("twitch.tv/steve_streams or @other", false), "the URL wins");
    }

    @Test
    void bareNamesOnlyWhenAskedAndAlone() {
        assertEquals(Optional.of("steve_streams"), TwitchHandle.find("  Steve_Streams ", true));
        assertEquals(Optional.empty(), TwitchHandle.find("Steve_Streams", false));
        assertEquals(Optional.empty(), TwitchHandle.find("i am steve_streams", true), "two words: not a bare handle");
        assertEquals(Optional.empty(), TwitchHandle.find("hi", true), "too short");
        assertEquals(Optional.empty(), TwitchHandle.find("settings", true), "a route");
        assertEquals(Optional.empty(), TwitchHandle.find("", true));
        assertEquals(Optional.empty(), TwitchHandle.find(null, true));
    }
}
