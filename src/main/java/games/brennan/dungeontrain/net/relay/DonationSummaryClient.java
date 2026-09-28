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
     * One rung of the relay's support ladder (funding-goals.js): running costs first, then
     * whatever the relay has been configured to ask for next. Support stacks — a rung's
     * {@code raised} only starts filling once every rung before it is complete — so the ladder
     * always sums to the month's total raised.
     *
     * <p>{@code label} is the relay's own English text, used verbatim only when this jar has no
     * translation for {@code id}. That is what lets a goal added relay-side appear on jars that
     * predate it (see {@code FundingGoals#label}).</p>
     */
    public record Goal(String id, String label, int targetAud, int raisedAud,
                       int percent, boolean complete) {}

    /**
     * The updates ledger the death screen's card reads: how many updates shipped in the last week,
     * in the last 30 days, and across the longest window on offer, plus when the newest release was
     * published (epoch millis, {@code 0} when the relay has no release timestamp).
     *
     * <p>{@code windowMonths} sizes {@code count}: the project's own age in months, capped at the
     * twelve the card renders as "1 year". The relay re-derives every figure at read time, so the
     * window widens on its own without a jar or a deploy.</p>
     */
    public record Updates(int count, int windowMonths, int month, int week, int day, int year,
                          long latestReleaseAtMs, String latestVersion) {}

    /**
     * One arm of a running UI experiment: an id this jar may or may not know how to draw, and its
     * relative weight. Weights are relative, not percentages — the client normalises over the arms
     * it can actually render (see {@code DonateExperiment}).
     */
    public record Arm(String id, double weight) {}

    /**
     * The running UI experiment, as published by the relay ({@code experiments.js}): an id, a salt
     * and the weighted arms. The client hashes {@code salt + id + its own uuid} to pick one, which
     * is what keeps this payload anonymous and cacheable while leaving the weights operator-owned.
     *
     * <p>Null against a relay that predates experiments, or one with none running — both of which
     * every jar reads as "draw the control layout".</p>
     */
    public record Experiment(String id, String salt, List<Arm> arms) {}

    /**
     * When work last landed on the project: the newest commit across the mod, its siblings and the
     * relay, as epoch millis. Null when the relay predates the block or its poll has not resolved.
     */
    public record Activity(long lastCommitAtMs, String repo) {}

    /**
     * The parsed ledger. {@code monthlyCostUsd}/{@code percentCovered} are -1 when the relay has no
     * cost snapshot yet; {@code hasYou} is false when the player hasn't consented / isn't a donor.
     * {@code goals} is empty (and {@code activeGoalId} null) against a relay that predates the
     * ladder, or when there is no cost snapshot to build the first rung from — the screen falls
     * back to {@code percentCovered} then.
     */
    public record Summary(int monthlyRaisedUsd, int totalRaisedUsd, int monthlyCostUsd,
                          int percentCovered, int patronCount, int patronMonthlyUsd,
                          List<Entry> monthly, List<Entry> allTime,
                          boolean hasYou, int youMonthlyUsd, int youTotalUsd,
                          List<Goal> goals, String activeGoalId, Updates updates,
                          Activity activity, Experiment experiment) {}

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

    /**
     * Parse the relay JSON body into {@link Summary}, or null when it isn't a well-formed ok
     * response. Package-private so the wire shape can be pinned against a real relay body in a
     * test — this payload is the one contract the relay and every shipped jar share.
     */
    static Summary parse(String body) {
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
                you != null ? optInt(you, "totalUsd", 0) : 0,
                parseGoals(o),
                o.has("activeGoalId") && o.get("activeGoalId").isJsonPrimitive()
                        ? o.get("activeGoalId").getAsString() : null,
                parseUpdates(o),
                parseActivity(o),
                parseExperiment(o));
    }

    /**
     * The activity block, or null against a relay that predates it or one whose commit poll has
     * never resolved — which the death screen reads as "draw no Last Active card". A non-positive
     * timestamp is treated the same way; the screen also declines a timestamp in the future, which
     * it can judge and this parser cannot (it has no clock).
     */
    private static Activity parseActivity(JsonObject o) {
        if (!o.has("activity") || !o.get("activity").isJsonObject()) return null;
        JsonObject a = o.getAsJsonObject("activity");
        long at = 0L;
        try {
            if (a.has("lastCommitAt") && a.get("lastCommitAt").isJsonPrimitive()) {
                at = a.get("lastCommitAt").getAsLong();
            }
        } catch (RuntimeException ignored) { /* unparseable timestamp — treat as unknown */ }
        if (at <= 0L) return null;
        String repo = a.has("repo") && a.get("repo").isJsonPrimitive() ? a.get("repo").getAsString() : "";
        return new Activity(at, repo);
    }

    /**
     * The running experiment, or null when the relay serves none — which every jar reads as "draw
     * the control layout". Arms without an id, or with a negative or unparseable weight, are
     * dropped rather than defaulted: a weight this jar had to invent would silently skew the split
     * away from what the operator configured. Fewer than two surviving arms is not an experiment,
     * so it resolves to null.
     */
    private static Experiment parseExperiment(JsonObject o) {
        if (!o.has("experiment") || !o.get("experiment").isJsonObject()) return null;
        JsonObject e = o.getAsJsonObject("experiment");
        String id = e.has("id") && e.get("id").isJsonPrimitive() ? e.get("id").getAsString() : null;
        String salt = e.has("salt") && e.get("salt").isJsonPrimitive() ? e.get("salt").getAsString() : null;
        if (id == null || id.isBlank() || salt == null || salt.isBlank()) return null;
        if (!e.has("arms") || !e.get("arms").isJsonArray()) return null;

        List<Arm> arms = new ArrayList<>();
        for (JsonElement el : e.getAsJsonArray("arms")) {
            if (!el.isJsonObject()) continue;
            JsonObject a = el.getAsJsonObject();
            String armId = a.has("id") && a.get("id").isJsonPrimitive() ? a.get("id").getAsString() : null;
            if (armId == null || armId.isBlank()) continue;
            double weight;
            try {
                weight = a.has("weight") && a.get("weight").isJsonPrimitive() ? a.get("weight").getAsDouble() : -1;
            } catch (RuntimeException ex) {
                continue;
            }
            if (!Double.isFinite(weight) || weight < 0) continue;
            arms.add(new Arm(armId, weight));
        }
        return arms.size() >= 2 ? new Experiment(id, salt, List.copyOf(arms)) : null;
    }

    /**
     * The support ladder, in relay order. Rungs without an {@code id} are dropped rather than
     * rendered as a nameless goal; a relay that sends no {@code goals} at all yields an empty list,
     * which the death screen reads as "no ladder" and falls back to the coverage percentage.
     */
    private static List<Goal> parseGoals(JsonObject o) {
        List<Goal> out = new ArrayList<>();
        if (!o.has("goals") || !o.get("goals").isJsonArray()) return out;
        for (JsonElement el : o.getAsJsonArray("goals")) {
            if (!el.isJsonObject()) continue;
            JsonObject g = el.getAsJsonObject();
            String id = g.has("id") && g.get("id").isJsonPrimitive() ? g.get("id").getAsString() : null;
            if (id == null || id.isBlank()) continue;
            String label = g.has("label") && g.get("label").isJsonPrimitive() ? g.get("label").getAsString() : id;
            boolean complete = g.has("complete") && g.get("complete").isJsonPrimitive()
                    && g.get("complete").getAsBoolean();
            out.add(new Goal(id, label, optInt(g, "targetAud", 0), optInt(g, "raisedAud", 0),
                    optInt(g, "percent", 0), complete));
        }
        return out;
    }

    /**
     * The updates block, or null against a relay that predates it (or one whose own upstream poll
     * has never resolved) — which the death screen reads as "fall back to the baked numbers".
     * A block with no count is treated the same way: an unknown figure is never rendered.
     */
    private static Updates parseUpdates(JsonObject o) {
        if (!o.has("updates") || !o.get("updates").isJsonObject()) return null;
        JsonObject u = o.getAsJsonObject("updates");
        int count = optInt(u, "count", 0);
        if (count <= 0) return null;
        long latestAt = 0L;
        try {
            if (u.has("latestReleaseAt") && u.get("latestReleaseAt").isJsonPrimitive()) {
                latestAt = u.get("latestReleaseAt").getAsLong();
            }
        } catch (RuntimeException ignored) { /* unparseable timestamp — treat as unknown */ }
        String latestVersion = u.has("latestVersion") && u.get("latestVersion").isJsonPrimitive()
                ? u.get("latestVersion").getAsString() : "";
        return new Updates(count, Math.max(0, optInt(u, "windowMonths", 0)),
                Math.max(0, optInt(u, "month", 0)), Math.max(0, optInt(u, "week", 0)),
                Math.max(0, optInt(u, "day", 0)), Math.max(0, optInt(u, "year", 0)),
                Math.max(0L, latestAt), latestVersion);
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
