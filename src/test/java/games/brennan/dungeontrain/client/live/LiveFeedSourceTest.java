package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which stream each source shows for a poll: the second source falls back to the first while there is no second. */
final class LiveFeedSourceTest {

    private static Status live(String session, Status second) {
        return new Status(true, session, "https://x/main/" + session + "/live.m3u8", "Alice", 3, 100, true,
            null, false, List.of(), null, second);
    }

    @Test
    @DisplayName("the first source always shows the poll's own stream")
    void mainShowsThePrimary() {
        Status second = live("s2", null);
        Status polled = live("s1", second);
        assertSame(polled, LiveFeedSource.statusFor(polled, false));
    }

    @Test
    @DisplayName("the second source shows the second stream while there is one, else the first")
    void secondFallsBackToThePrimary() {
        Status second = live("s2", null);
        Status both = live("s1", second);
        assertSame(second, LiveFeedSource.statusFor(both, true));
        Status one = live("s1", null);
        assertSame(one, LiveFeedSource.statusFor(one, true));
        assertSame(Status.OFFLINE, LiveFeedSource.statusFor(Status.OFFLINE, true));
    }

    @Test
    @DisplayName("holding the second channel, the second source shows our own stream until a poll names the second")
    void ownSecondChannelShowsOwnStream() {
        String mine = "https://x/second/me/live.m3u8";
        Status one = live("s1", null);
        Status own = LiveFeedSource.statusFor(one, true, mine, "Bob");
        assertTrue(own.live());
        assertTrue(own.slot());
        assertEquals(mine, own.playlistUrl());
        assertEquals("Bob", own.streamer());
        assertEquals(mine, LiveFeedSource.statusFor(Status.OFFLINE, true, mine, "Bob").playlistUrl());
        // Once the relay reports the second stream, its status (variants and all) wins.
        Status second = live("s2", null);
        assertSame(second, LiveFeedSource.statusFor(live("s1", second), true, mine, "Bob"));
        // The first source never borrows it; not holding the second channel falls back as before.
        assertSame(one, LiveFeedSource.statusFor(one, false, mine, "Bob"));
        assertSame(one, LiveFeedSource.statusFor(one, true, null, null));
    }

    @Test
    @DisplayName("with something to play, a client without ffmpeg shows the needs-FFmpeg card, never snow")
    void noFfmpegIsNotStatic() {
        assertEquals(LiveFeedSource.Kind.NO_FFMPEG, LiveFeedSource.kindBeforeDecode(FfmpegSupport.State.OFF));
        assertEquals(LiveFeedSource.Kind.NO_FFMPEG, LiveFeedSource.kindBeforeDecode(FfmpegSupport.State.FAILED));
        assertEquals(LiveFeedSource.Kind.DOWNLOADING, LiveFeedSource.kindBeforeDecode(FfmpegSupport.State.DOWNLOADING));
        assertNull(LiveFeedSource.kindBeforeDecode(FfmpegSupport.State.READY));
    }
}
