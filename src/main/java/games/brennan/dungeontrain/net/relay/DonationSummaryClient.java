package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Off-thread reader for the relay's {@code GET /<CAP>/donations/summary} endpoint — the
 * "support the line" ledger shown on the death screen's donation page: monthly + all-time donor
 * leaderboards, the rounded monthly running cost + percent covered, and (when the player has
 * consented to networking) that player's own contribution. Mirrors {@link BookStatsClient}'s
 * fire-and-forget GET pattern (own {@link HttpClient}, no-throw, best-effort).
 *
 * <p>Consent: the anonymous leaderboard + cost aggregates carry no player identity, so the fetch
 * runs regardless of the network-consent setting (like {@code OfficialLinksFetcher}). The
 * player's name is only appended (to resolve "your contribution") when
 * {@link DiscordPresenceClientConfig#isGranted()} — the same gate {@code UiAnalytics} uses.</p>
 */
public final class DonationSummaryClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for local cleartext testing — see BookStatsClient for the rationale.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private DonationSummaryClient() {}

    /** One donor row on a leaderboard. {@code source} is "revolut" (hand-entered) or "patreon". */
    public record Entry(String name, int amountUsd, String source) {}

    /**
     * The parsed ledger. {@code monthlyCostUsd}/{@code percentCovered} are -1 when the relay has no
     * cost snapshot yet; {@code hasYou} is false when the player hasn't consented / isn't a donor.
     */
    public record Summary(int monthlyRaisedUsd, int totalRaisedUsd, int monthlyCostUsd,
                          int percentCovered, int patronCount, int patronMonthlyUsd,
                          List<Entry> monthly, List<Entry> allTime,
                          boolean hasYou, int youMonthlyUsd, int youTotalUsd) {}

    /**
     * Fetch the donation summary off-thread and hand the parsed result to {@code callback} (invoked
     * on the HTTP completion thread — the caller hops back to the client thread before touching
     * game state). {@code playerName} may be null/blank; it is sent only when consent is granted.
     * No-throw: a failed / slow / malformed / non-2xx fetch never calls back.
     */
    public static void fetch(String playerName, Consumer<Summary> callback) {
        try {
            String url = DungeonTrain.relayBaseUrl() + "/donations/summary";
            // "Your contribution" needs the player's name → only send it with network consent.
            if (playerName != null && !playerName.isBlank() && DiscordPresenceClientConfig.isGranted()) {
                url += "?name=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] donations fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] donations fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            Summary summary = parse(resp.body());
                            if (summary != null) callback.accept(summary);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] donations parse failed: {}", t.toString());
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] donations request failed to start: {}", t.toString());
        }
    }

    /** Parse the relay JSON body into {@link Summary}, or null when it isn't a well-formed ok response. */
    private static Summary parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("ok") || !o.get("ok").getAsBoolean()) return null;

        JsonObject patrons = o.has("patrons") && o.get("patrons").isJsonObject() ? o.getAsJsonObject("patrons") : null;
        JsonObject you = o.has("you") && o.get("you").isJsonObject() ? o.getAsJsonObject("you") : null;

        return new Summary(
                optInt(o, "monthlyRaisedUsd", 0),
                optInt(o, "totalRaisedUsd", 0),
                optInt(o, "monthlyCostUsd", -1),   // relay sends null → treat as unknown
                optInt(o, "percentCovered", -1),
                patrons != null ? optInt(patrons, "count", 0) : 0,
                patrons != null ? optInt(patrons, "monthlyUsd", 0) : 0,
                parseEntries(o, "monthly"),
                parseEntries(o, "allTime"),
                you != null,
                you != null ? optInt(you, "monthlyUsd", 0) : 0,
                you != null ? optInt(you, "totalUsd", 0) : 0);
    }

    private static List<Entry> parseEntries(JsonObject o, String key) {
        List<Entry> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) return out;
        JsonArray arr = o.getAsJsonArray(key);
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            String name = e.has("name") && e.get("name").isJsonPrimitive() ? e.get("name").getAsString() : null;
            if (name == null || name.isBlank()) continue;
            String source = e.has("source") && e.get("source").isJsonPrimitive() ? e.get("source").getAsString() : "revolut";
            out.add(new Entry(name, optInt(e, "amountUsd", 0), source));
        }
        return out;
    }

    private static int optInt(JsonObject o, String k, int fallback) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
