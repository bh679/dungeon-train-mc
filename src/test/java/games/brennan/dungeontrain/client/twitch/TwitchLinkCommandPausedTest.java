package games.brennan.dungeontrain.client.twitch;

import games.brennan.dungeontrain.client.twitch.TwitchLinkClient.Error;
import games.brennan.dungeontrain.client.twitch.TwitchLinkClient.Status;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /twitch}'s status lines say when the relay has the restream paused, and only then. */
class TwitchLinkCommandPausedTest {

    private static List<String> keys(Component c) {
        List<String> out = new ArrayList<>();
        collect(c, out);
        return out;
    }

    private static void collect(Component c, List<String> out) {
        if (c.getContents() instanceof TranslatableContents t) out.add(t.getKey());
        for (Component s : c.getSiblings()) collect(s, out);
    }

    @Test
    void pausedAddsTheLine() {
        Status paused = new Status(true, true, true, "pat_1", "Pat", true, false, true, Error.NONE);
        assertTrue(keys(TwitchLinkCommand.linkedLines(paused)).contains("chat.dungeontrain.twitch.restream_paused"));
    }

    @Test
    void runningOrAnOlderRelaySaysNothing() {
        Status running = new Status(true, true, true, "pat_1", "Pat", true, false, false, Error.NONE);
        Status old = new Status(true, true, true, "pat_1", "Pat", true, false, Error.NONE);
        assertFalse(keys(TwitchLinkCommand.linkedLines(running)).contains("chat.dungeontrain.twitch.restream_paused"));
        assertFalse(keys(TwitchLinkCommand.linkedLines(old)).contains("chat.dungeontrain.twitch.restream_paused"));
        assertTrue(keys(TwitchLinkCommand.linkedLines(running)).contains("chat.dungeontrain.twitch.restream_on"));
    }
}
