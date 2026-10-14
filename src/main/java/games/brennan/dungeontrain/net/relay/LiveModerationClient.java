package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.moderation.LiveModerationLadder;
import games.brennan.dungeontrain.moderation.LiveModerationLadder.Verdict;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The relay side of the Live Eye strike ladder, from the server's point of view.
 *
 * <pre>
 * GET  &lt;relayBase&gt;/live/moderation/status?uuid=&lt;mc-uuid&gt;
 *   200 { ok, strikes, removals, action:"none", banned, permanent, remainingSec }
 * POST &lt;relayBase&gt;/live/moderation/strike   { uuid, name, source, terms:[…] }   (via RelayOutbox)
 *   200 { ok, strikes, removals, action: warn|remove|ban, banned, permanent, remainingSec }
 * </pre>
 *
 * <p>{@link #fetchStatus} follows {@link DebugGrantClient}: own client, HTTP/1.1, every failure
 * swallowed, and the callback fires only for a well-formed {@code ok} body — so a relay outage never
 * bans anyone and never clears a ban already cached. The strike itself travels through the durable
 * {@link RelayOutbox} (see {@code event/LiveModeration}); {@link #strikeBody} builds its JSON.</p>
 */
public final class LiveModerationClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The outbox path strikes go out on and come back on. */
    public static final String STRIKE_PATH = "/live/moderation/strike";
    /** The outbox path a ban appeal goes out on ({@code /liveappeal}). */
    public static final String APPEAL_PATH = "/live/moderation/appeal";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private LiveModerationClient() {}

    /** Fetch {@code player}'s verdict off-thread; {@code callback} runs on the HTTP thread, only on a good answer. */
    public static void fetchStatus(UUID player, Consumer<Verdict> callback) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/live/moderation/status?uuid="
                + URLEncoder.encode(player.toString(), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] live moderation status fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] live moderation status -> HTTP {}", resp.statusCode());
                                return;
                            }
                            Verdict v = LiveModerationLadder.parse(resp.body());
                            if (v != null) callback.accept(v);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] live moderation status parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] live moderation status request failed to start: {}", t.toString());
        }
    }

    /** Longest line of evidence sent with a strike; the relay keeps the same. */
    public static final int MAX_EVIDENCE_TEXT = 500;

    /**
     * The {@code /live/moderation/strike} body. Pure. {@code terms} are what matched and {@code text}
     * the line as typed — the relay logs both as the evidence a ban appeal is judged against.
     */
    public static String strikeBody(UUID player, String name, String source, List<String> terms, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", player.toString().replace("-", ""));
        o.addProperty("name", name == null ? "" : name);
        o.addProperty("source", source == null ? "" : source);
        JsonArray arr = new JsonArray();
        if (terms != null) for (String t : terms) arr.add(t);
        o.add("terms", arr);
        if (text != null && !text.isEmpty()) {
            o.addProperty("text", text.length() > MAX_EVIDENCE_TEXT ? text.substring(0, MAX_EVIDENCE_TEXT) : text);
        }
        return o.toString();
    }

    /** The {@code /live/moderation/appeal} body: the logged line being appealed ({@code evidenceId}, may be null) and/or free text. Pure. */
    public static String appealBody(UUID player, String name, Long evidenceId, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", player.toString().replace("-", ""));
        o.addProperty("name", name == null ? "" : name);
        if (evidenceId != null) o.addProperty("evidenceId", evidenceId);
        o.addProperty("message", message == null ? "" : message);
        return o.toString();
    }

    /** The player a strike request was about, read back from its body (the relay's answer never repeats it). */
    public static UUID playerOf(String requestBody) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(requestBody).getAsJsonObject();
            String hex = req.get("uuid").getAsString().replace("-", "").trim();
            if (hex.length() != 32) return null;
            return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                    + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
        } catch (Throwable t) {
            return null;
        }
    }
}
