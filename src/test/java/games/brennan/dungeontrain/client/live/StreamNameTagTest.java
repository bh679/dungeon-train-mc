package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.config.LiveFeedClientConfig.NameTag;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.StreamNameTag.effective;
import static games.brennan.dungeontrain.client.live.StreamNameTag.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The name on the stream is the one the streamer chose, never another, and none in Kid mode. */
class StreamNameTagTest {

    @Test
    void kidModeShowsNoName() {
        for (NameTag tag : NameTag.values()) assertEquals(NameTag.NONE, effective(tag, true));
        for (NameTag tag : NameTag.values()) assertEquals(tag, effective(tag, false));
    }

    @Test
    void minecraftShowsTheMinecraftName() {
        assertEquals("Steve", text(NameTag.MINECRAFT, "Steve", true, "steve#discord"));
    }

    @Test
    void discordShowsTheLinkedDiscordName() {
        assertEquals("brennan", text(NameTag.DISCORD, "Steve", true, " brennan "));
    }

    @Test
    void discordNeverFallsBackToTheMinecraftName() {
        assertEquals("", text(NameTag.DISCORD, "Steve", false, null));
        assertEquals("", text(NameTag.DISCORD, "Steve", false, "stale"));
        assertEquals("", text(NameTag.DISCORD, "Steve", true, ""));
        assertEquals("", text(NameTag.DISCORD, "Steve", true, null));
    }

    @Test
    void noneShowsNothing() {
        assertEquals("", text(NameTag.NONE, "Steve", true, "brennan"));
    }
}
