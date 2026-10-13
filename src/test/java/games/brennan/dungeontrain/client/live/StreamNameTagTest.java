package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.config.LiveFeedClientConfig.NameTag;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.StreamNameTag.effective;
import static games.brennan.dungeontrain.client.live.StreamNameTag.pillInset;
import static games.brennan.dungeontrain.client.live.StreamNameTag.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void pillRunsFullWidthThroughTheMiddle() {
        assertEquals(0, pillInset(14, 28));
        assertEquals(0, pillInset(13, 28));
    }

    @Test
    void pillEndsAreRound() {
        int h = 28;
        assertTrue(pillInset(0, h) > pillInset(3, h), "top row pulled in further than rows below it");
        for (int row = 1; row <= h / 2; row++) assertTrue(pillInset(row, h) <= pillInset(row - 1, h));
        assertTrue(pillInset(0, h) < h / 2, "top row still has some width");
    }

    @Test
    void pillIsSymmetricTopToBottom() {
        for (int h : new int[] {14, 15, 28, 42}) {
            for (int row = 0; row < h; row++) assertEquals(pillInset(row, h), pillInset(h - 1 - row, h));
        }
    }
}
