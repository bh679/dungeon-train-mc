package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fetches the skins of players who died at a given carriage index, used to dress the
 * {@code player_head} blocks generated in that same carriage (see
 * {@link games.brennan.dungeontrain.train.DeathHeadSkins}).
 *
 * <p>Read-only and anonymous in the outbound direction: the request names a carriage number and
 * nothing else — not the asking player, not their world. The response carries each dead player's
 * skin URL and display name, which is what lets a mined head read "<Name>'s Head"; their uuid never
 * travels.</p>
 *
 * <p>Mirrors {@link ModPopularityClient}: same HTTP/1.1 pin (for local cleartext testing), same
 * timeouts, no-throw throughout, and the callback fires on the HTTP completion thread — never on the
 * server thread. A failed, slow, or malformed fetch simply never calls back, which leaves the heads
 * wearing the bundled PlayerMob skins they would have worn anyway.</p>
 */
public final class DeathSkinClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Sanity bound on a relay response — far above the {@code MAX_SKINS} the relay itself returns. */
    private static final int MAX_SKINS = 64;

    private DeathSkinClient() {}

    /**
     * One skin as the relay reports it: a Mojang CDN texture URL, its arm model, and the display name
     * of the player who wore it. {@code name} is {@code ""} when the death carried no usable one (or
     * the relay predates names), which dresses the head without titling it.
     */
    public record Skin(String url, boolean slim, String name) {}

    /**
     * Fetch the skins for {@code carriage} off-thread and hand them to {@code callback}. No-throw: a
     * failed / slow / malformed / non-2xx fetch never calls back, and an empty list is a perfectly
     * ordinary answer (a carriage index nobody has died at yet, or one the relay has not resolved).
     */
    public static void fetch(int carriage, Consumer<List<Skin>> callback) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/deaths/skins?carriage=" + Math.max(0, carriage);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] death skin fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] death skin fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            List<Skin> parsed = parse(resp.body());
                            if (parsed != null) callback.accept(parsed);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] death skin parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] death skin request failed to start: {}", t.toString());
        }
    }

    /**
     * Parse {@code {ok:true, carriage, skins:[{url, model}]}} into skins, or null when malformed.
     *
     * <p>Every entry is re-validated rather than trusted — this is data off the wire on its way into
     * a block entity. A URL that is not Mojang's CDN would produce an invisible head rather than a
     * skin, and a name that is not a plain username would be rejected by vanilla's profile codec and
     * take the whole encode down with it, so a bad one is dropped and the head goes untitled.</p>
     */
    static List<Skin> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;
        if (!o.has("skins") || !o.get("skins").isJsonArray()) return null;

        List<Skin> out = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray("skins")) {
            if (out.size() >= MAX_SKINS) break;
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            if (!e.has("url") || !e.get("url").isJsonPrimitive()) continue;
            String url = e.get("url").getAsString();
            if (!games.brennan.dungeontrain.train.HeadProfiles.isTextureUrl(url)) continue;
            boolean slim = e.has("model") && e.get("model").isJsonPrimitive()
                    && "slim".equals(e.get("model").getAsString());
            String name = e.has("name") && e.get("name").isJsonPrimitive() ? e.get("name").getAsString() : "";
            if (!games.brennan.dungeontrain.train.HeadProfiles.isPlayerName(name)) name = "";
            out.add(new Skin(url, slim, name));
        }
        return List.copyOf(out);
    }
}
