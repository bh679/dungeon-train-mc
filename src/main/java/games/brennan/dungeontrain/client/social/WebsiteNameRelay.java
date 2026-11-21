package games.brennan.dungeontrain.client.social;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.client.credits.PublicNameSwitch;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The relay half of the Show my name switch ({@link PublicNameSwitch}): {@code POST /<CAP>/website-name
 * {uuid, name, on}}, read by the website's player reviews (dp-relay {@code website-name.js}).
 *
 * <p>A review reaches the relay through Discord carrying only the player's name, so the name travels
 * with the uuid; the relay remembers every name a uuid has sent. Nothing proves who sends a switch, so
 * the game re-sends the real one at every login (overwriting a forged one), on every flip and on a
 * content-mode change (Kid mode always sends off).</p>
 *
 * <p>Consent-gated, fire and forget, never throws. The body is a pure function for tests.</p>
 */
public final class WebsiteNameRelay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final String PATH = "/website-name";
    /** The relay's own name rule (website-name.js NAME_RE); anything else is sent as no name. */
    private static final Pattern NAME_RE = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private WebsiteNameRelay() {}

    /** Send the current switch to the relay. Fire and forget; a no-op without consent. */
    public static void syncNow() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getUser() == null || !DiscordPresenceClientConfig.isGranted()) return;
            UUID uuid = mc.getUser().getProfileId();
            if (uuid == null) return;
            post(buildBody(uuid.toString().replace("-", ""), mc.getUser().getName(), PublicNameSwitch.isShown()));
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] website-name sync failed: {}", e.toString());
        }
    }

    /** The {@code /website-name} body. A name the relay would refuse is left out rather than sent. */
    static JsonObject buildBody(String uuid, @Nullable String name, boolean on) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        if (name != null && NAME_RE.matcher(name).matches()) body.addProperty("name", name);
        body.addProperty("on", on);
        return body;
    }

    private static void post(JsonObject body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + PATH))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .thenAccept(r -> {
                if (r.statusCode() / 100 != 2) {
                    LOGGER.debug("[DungeonTrain] website-name answered {}", r.statusCode());
                }
            })
            .exceptionally(t -> {
                LOGGER.debug("[DungeonTrain] website-name failed: {}", t.toString());
                return null;
            });
    }
}
