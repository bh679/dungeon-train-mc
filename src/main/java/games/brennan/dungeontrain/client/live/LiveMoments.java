package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.LiveMomentPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.MomentKind;
import org.slf4j.Logger;

/**
 * The streamer's moments — an advancement earned, a photo taken, a book signed — shared into the
 * stream's Live chat (Discord, Twitch, the website and TV wearers), each behind its own Stream setting.
 *
 * <p>The server spots the moment ({@code event/LiveMomentEvents}) and sends {@link LiveMomentPacket};
 * this client posts it to the relay with the stream token, which is what proves the poster is on air.
 * Best effort: a failure (an older relay, a rate limit) is logged once and the moment is dropped.</p>
 */
public final class LiveMoments {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean failureLogged;

    private LiveMoments() {}

    public static void onServerSaid(LiveMomentPacket packet) {
        LiveStreamController stream = LiveStreamController.get();
        String token = stream.streamToken();
        if (!shouldShare(packet.kind(), stream.streaming(), token, ClientDisplayConfig.isKidMode())) return;
        byte[] png = packet.kind() == MomentKind.PHOTO && packet.png().length > 0 ? packet.png() : null;
        LiveFeedClient.chatMoment(DungeonTrain.relayBaseUrl(), token, packet.kind(), packet.title(), packet.excerpt(), png)
            .thenAccept(r -> {
                if (!r.ok() && !failureLogged) {
                    failureLogged = true;
                    LOGGER.warn("[DungeonTrain] live moment share failed (status {}); further failures this session are silent", r.status());
                }
            });
    }

    /** Shared: streaming with a token, not Kid mode, and that kind's Stream setting on. */
    static boolean shouldShare(MomentKind kind, boolean streaming, String token, boolean kid) {
        if (!streaming || kid || token == null || token.isEmpty()) return false;
        return enabled(kind);
    }

    /** The Stream setting for {@code kind}. */
    public static boolean enabled(MomentKind kind) {
        return switch (kind) {
            case ADVANCEMENT -> LiveFeedClientConfig.liveMomentAdvancements();
            case PHOTO -> LiveFeedClientConfig.liveMomentPhotos();
            case BOOK -> LiveFeedClientConfig.liveMomentBooks();
        };
    }
}
