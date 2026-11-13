package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Mic;
import org.jetbrains.annotations.Nullable;

/**
 * Which of a stream's audio tracks a viewer's speaker plays. A voice stream carries two: track 0 is
 * the game with the streamer's mic over it, track 1 the game alone. Everyone hears track 0, except a
 * client in Kid mode ({@link ClientDisplayConfig#isKidMode()}): there a streamer's voice reaches the
 * speaker only when the relay has approved it for kids ({@link Mic#kids()}). Without that approval,
 * or on a relay too old to say, Kid mode plays the game alone. A stream without a mic track has only
 * track 0 either way.
 */
public final class LiveSoundTrack {

    /** The game with the mic over it. */
    public static final int MIX = 0;
    /** The game alone. */
    public static final int GAME_ONLY = 1;

    private LiveSoundTrack() {}

    /** The track to play for a stream the status describes with {@code mic}, on this client. */
    public static int forViewer(@Nullable Mic mic) {
        return choose(mic, ClientDisplayConfig.isKidMode());
    }

    /** {@link #forViewer} with Kid mode given. Pure, for the tests. */
    public static int choose(@Nullable Mic mic, boolean kid) {
        if (!kid || mic == null || !mic.enabled()) return MIX;
        return mic.kids() ? MIX : GAME_ONLY;
    }
}
