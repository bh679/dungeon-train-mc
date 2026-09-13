package games.brennan.dungeontrain.client.videos;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Off-thread poster for {@code POST /<CAP>/videos/<id>/flag} — a player reporting a video.
 *
 * <p>What goes with the flag: the reason, the custom text if any, the client's <b>content mode</b>
 * (adult / kid — what lets one adult's "not safe for kids" pull a video from the kid list at once,
 * and what makes a kid's flag count toward the kids' threshold), and the player's uuid <b>only
 * when network consent is on</b> — the relay counts "different people" by uuid when it has one
 * and by connection otherwise. Nothing else.</p>
 */
public final class VideoFlagger {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The three things a player can say about a video; wire values match the relay's REASONS. */
    public enum Reason {
        NOT_DT("not_dt"), NSFK("nsfk"), CUSTOM("custom");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** Lang-key suffix under {@code gui.dungeontrain.videos.flag.reason.}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the relay did with it. */
    public record Outcome(boolean recorded, boolean hidden, boolean kidsHidden, boolean rateLimited) {
        static final Outcome FAILED = new Outcome(false, false, false, false);
        static final Outcome LIMITED = new Outcome(false, false, false, true);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private VideoFlagger() {}

    /** Post the flag; {@code onDone} runs on the HTTP thread — marshal to the render thread yourself. */
    public static void flagAsync(VideoEntry video, Reason reason, String text, Consumer<Outcome> onDone) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            DungeonTrain.relayBaseUrl() + "/videos/" + video.id() + "/flag"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(reason, text).toString(), StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        if (err != null) {
                            LOGGER.debug("[DungeonTrain] video flag failed: {}", err.toString());
                            onDone.accept(Outcome.FAILED);
                            return;
                        }
                        onDone.accept(interpret(resp.statusCode(), resp.body()));
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] video flag failed to start: {}", t.toString());
            onDone.accept(Outcome.FAILED);
        }
    }

    /** The request body — see the class comment for what is and is not in it. */
    static JsonObject body(Reason reason, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("reason", reason.wire());
        if (reason == Reason.CUSTOM && text != null && !text.isBlank()) {
            body.addProperty("text", text.trim());
        }
        body.addProperty("mode", ClientDisplayConfig.getContentMode().isKid() ? "kid" : "adult");
        if (DiscordPresenceClientConfig.isGranted()) {
            Minecraft mc = Minecraft.getInstance();
            UUID uuid = mc != null && mc.getUser() != null ? mc.getUser().getProfileId() : null;
            if (uuid != null) body.addProperty("uuid", uuid.toString().replace("-", ""));
        }
        return body;
    }

    /** Status + body → outcome. Package-private for the unit test. */
    static Outcome interpret(int status, String body) {
        if (status == 429) return Outcome.LIMITED;
        if (status / 100 != 2) return Outcome.FAILED;
        try {
            var el = JsonParser.parseString(body == null ? "" : body);
            if (!el.isJsonObject()) return Outcome.FAILED;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("status") || !"recorded".equals(o.get("status").getAsString())) return Outcome.FAILED;
            return new Outcome(true, bool(o, "hidden"), bool(o, "kidsHidden"), false);
        } catch (RuntimeException e) {
            return Outcome.FAILED;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        var v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }
}
