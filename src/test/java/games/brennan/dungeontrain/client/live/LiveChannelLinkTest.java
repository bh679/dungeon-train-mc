package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LiveChannelLinkTest {

    private static final LiveFeedClient.LinkedChannel ASH = new LiveFeedClient.LinkedChannel("ashseven", "AshSeven");

    private static LiveFeedClient.Status stream(String login) {
        return new LiveFeedClient.Status(true, "s", "https://x/live.m3u8", "AshSeven", 1, 0, false, null, false, List.of(), login);
    }

    @Test
    void joinLineOnlyAfterJoining() {
        assertNull(new LiveChannelLink().joinLine(stream("ashseven"), 1000));
    }

    @Test
    void joinLineIsGivenOnce() {
        LiveChannelLink link = new LiveChannelLink();
        link.onJoined(1000);
        assertNotNull(link.joinLine(stream("ashseven"), 1000));
        assertNull(link.joinLine(stream("ashseven"), 2000));
    }

    @Test
    void noChannelMeansNoLine() {
        LiveChannelLink link = new LiveChannelLink();
        link.onJoined(1000);
        assertNull(link.joinLine(stream(null), 1000));
    }

    @Test
    void joinLineWaitsForTheStatusButNotForever() {
        LiveChannelLink link = new LiveChannelLink();
        link.onJoined(1000);
        assertNull(link.joinLine(LiveFeedClient.Status.OFFLINE, 2000));
        assertNotNull(link.joinLine(stream("ashseven"), 3000));

        LiveChannelLink late = new LiveChannelLink();
        late.onJoined(1000);
        assertNull(late.joinLine(stream("ashseven"), 1000 + LiveChannelLink.JOIN_WINDOW_MS + 1));
    }

    @Test
    void relayLineRepeatingTheJoinLineIsDropped() {
        LiveChannelLink link = new LiveChannelLink();
        link.onJoined(1000);
        link.joinLine(stream("ashseven"), 1000);
        assertFalse(link.showRelayLine(ASH, 5000));
        assertTrue(link.showRelayLine(ASH, 1000 + LiveChannelLink.REPEAT_MS), "long after, it is news again");
    }

    @Test
    void relayLineFirstCancelsTheJoinLine() {
        LiveChannelLink link = new LiveChannelLink();
        link.onJoined(1000);
        assertTrue(link.showRelayLine(ASH, 2000));
        assertNull(link.joinLine(stream("ashseven"), 3000));
    }

    @Test
    void anotherChannelIsAlwaysNews() {
        LiveChannelLink link = new LiveChannelLink();
        assertTrue(link.showRelayLine(ASH, 1000));
        assertTrue(link.showRelayLine(new LiveFeedClient.LinkedChannel("bo_plays", "Bo"), 2000));
    }

    @Test
    void resetForgetsWhatWasLinked() {
        LiveChannelLink link = new LiveChannelLink();
        assertTrue(link.showRelayLine(ASH, 1000));
        link.reset();
        assertTrue(link.showRelayLine(ASH, 2000));
    }

    @Test
    void theLineCarriesAClickableChannelUrl() {
        Component line = LiveChannelLink.goLiveLine(ASH);
        ClickEvent click = findClick(line);
        assertNotNull(click);
        assertEquals(ClickEvent.Action.OPEN_URL, click.getAction());
        assertEquals("https://twitch.tv/ashseven", click.getValue());
    }

    private static ClickEvent findClick(Component c) {
        if (c.getStyle().getClickEvent() != null) return c.getStyle().getClickEvent();
        if (c.getContents() instanceof TranslatableContents t) {
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component a && findClick(a) != null) return findClick(a);
            }
        }
        for (Component s : c.getSiblings()) {
            if (findClick(s) != null) return findClick(s);
        }
        return null;
    }
}
