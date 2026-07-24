package games.brennan.dungeontrain.client.snapshot;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Client-side, best-effort mark that the local player chose to <b>Save</b> a ride photo from the
 * death-screen gallery. Posts the photo's client id (assigned at capture, {@link RideSnapshot#photoId()},
 * uploaded with the shot by {@code ShotUploadClient}) to the relay's {@code POST /<CAP>/shots/saved}, which
 * flips that row's {@code saved} flag.
 *
 * <p>The on-death gallery upload runs server-side and keys each relay row by a server-assigned timestamp,
 * so the client can't name its photos by that key — the shared {@code photoId} is the correlation handle
 * instead.</p>
 *
 * <p>Gated on the DiscordPresence network consent ({@link DiscordPresenceClientConfig#isGranted()}) and
 * fire-and-forget (own HTTP/1.1 client, no-throw), mirroring {@code ShotUploadClient} / {@code RelayChatClient}
 * — a dropped mark just leaves the photo untagged; it never affects the death screen or the tick.</p>
 */
public final class ShotSavedClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // HTTP/1.1 so plaintext http:// (local 127.0.0.1 testing) works; harmless in prod (see ShotUploadClient).
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private ShotSavedClient() {}

    /** Mark the photo with {@code photoId} as user-saved on the relay. No-op without network consent, a
     *  blank id, or a local player. Best-effort + off-thread. */
    public static void markSaved(String photoId) {
        try {
            if (photoId == null || photoId.isBlank()) return;
            if (!DiscordPresenceClientConfig.isGranted()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            String uuid = mc.player.getUUID().toString().replace("-", ""); // dashless, matching the upload key
            String body = "{\"uuid\":\"" + esc(uuid) + "\",\"photoid\":\"" + esc(photoId) + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/shots/saved"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            LOGGER.debug("[DungeonTrain] shot-saved mark failed: {}", err.toString());
                        } else if (resp.statusCode() / 100 != 2) {
                            LOGGER.debug("[DungeonTrain] shot-saved mark -> HTTP {}", resp.statusCode());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shot-saved mark failed to start: {}", t.toString());
        }
    }

    /** Minimal JSON string escaping for the two id fields (dashless UUIDs — but stay safe). */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
