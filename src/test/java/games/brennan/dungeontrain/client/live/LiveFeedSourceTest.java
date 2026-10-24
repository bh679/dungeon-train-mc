package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

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
}
