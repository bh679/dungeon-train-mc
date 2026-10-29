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
        fetchStatus(player, callback, () -> { });
    }

    /** {@link #fetchStatus(UUID, Consumer)}, with {@code onMiss} run (on the HTTP thread) when no good answer comes. */
    public static void fetchStatus(UUID player, Consumer<Verdict> callback, Runnable onMiss) {
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
                                onMiss.run();
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] live moderation status -> HTTP {}", resp.statusCode());
                                onMiss.run();
                                return;
                            }
                            Verdict v = LiveModerationLadder.parse(resp.body());
                            if (v != null) callback.accept(v);
                            else onMiss.run();
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] live moderation status parse failed: {}", t.toString());
                            onMiss.run();
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] live moderation status request failed to start: {}", t.toString());
            onMiss.run();
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

    /** The path a report on a streamer goes out on ({@code /livereport}). Sent directly: the 409 answer matters. */
    public static final String REPORT_PATH = "/live/report";

    /**
     * The {@code /live/report} body. {@code streamerUuid} may be null (a streamer on another server: the relay's
     * status names them but carries no uuid); it is then left out, never sent empty, and the relay takes the
     * holder of {@code channel}. Pure.
     */
    public static String reportBody(UUID player, String name, @org.jetbrains.annotations.Nullable UUID streamerUuid,
                                    String streamerName, String channel, String reason, boolean abandon) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", player.toString().replace("-", ""));
        o.addProperty("name", name == null ? "" : name);
        if (streamerUuid != null) o.addProperty("streamerUuid", streamerUuid.toString().replace("-", ""));
        o.addProperty("streamerName", streamerName == null ? "" : streamerName);
        o.addProperty("channel", LiveFeedClient.CHANNEL_SECOND.equals(channel) ? LiveFeedClient.CHANNEL_SECOND : LiveFeedClient.CHANNEL_MAIN);
        o.addProperty("reason", reason == null ? "" : reason);
        o.addProperty("abandon", abandon);
        return o.toString();
    }

    /** {@code body} with {@code abandon} set, for the [Abandon it and report again] resend. Pure. */
    public static String withAbandon(String body) {
        JsonObject o = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        o.addProperty("abandon", true);
        return o.toString();
    }

    /** What the relay said to a report. {@code existing*} are set for {@link Kind#OPEN} (createdAt 0 when unknown). */
    public record ReportReply(Kind kind, long existingCreatedAt, String existingStreamerName) {
        public enum Kind { SENT, OPEN, NOT_LIVE, NOT_FOUND, SELF, BANNED, RATE_LIMITED, FAILED }

        static ReportReply of(Kind kind) {
            return new ReportReply(kind, 0L, "");
        }
    }

    /**
     * A {@code /live/report} answer, from its status and body. A 400 is read by its {@code error}: only
     * {@code not_live} says the stream ended ({@code streamer_not_found}: someone else holds that channel,
     * {@code self_report}: the player's own stream); any other 400
     * ({@code bad_uuid}, {@code bad_channel}, ...) and anything unexpected is FAILED. Pure.
     */
    public static ReportReply parseReportReply(int status, String body) {
        switch (status) {
            case 400: return switch (errorOf(body)) {
                case "not_live" -> ReportReply.of(ReportReply.Kind.NOT_LIVE);
                case "streamer_not_found" -> ReportReply.of(ReportReply.Kind.NOT_FOUND);
                case "self_report" -> ReportReply.of(ReportReply.Kind.SELF);
                default -> ReportReply.of(ReportReply.Kind.FAILED);
            };
            case 403: return ReportReply.of(ReportReply.Kind.BANNED);
            case 429: return ReportReply.of(ReportReply.Kind.RATE_LIMITED);
            default: break;
        }
        try {
            JsonObject o = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (status / 100 == 2) {
                return o.has("ok") && o.get("ok").getAsBoolean()
                    ? ReportReply.of(ReportReply.Kind.SENT) : ReportReply.of(ReportReply.Kind.FAILED);
            }
            if (status == 409 && o.has("error") && "open_report".equals(o.get("error").getAsString())) {
                JsonObject ex = o.has("existing") && o.get("existing").isJsonObject() ? o.getAsJsonObject("existing") : new JsonObject();
                String name = ex.has("streamerName") && !ex.get("streamerName").isJsonNull() ? ex.get("streamerName").getAsString() : "";
                return new ReportReply(ReportReply.Kind.OPEN, createdAt(ex), name);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] live report reply unreadable (HTTP {}): {}", status, e.toString());
        }
        return ReportReply.of(ReportReply.Kind.FAILED);
    }

    /** A reply body's {@code error} code; "" when absent or unreadable. */
    static String errorOf(String body) {
        try {
            JsonObject o = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            return o.has("error") && o.get("error").isJsonPrimitive() ? o.get("error").getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** {@code createdAt} as epoch ms: a number (seconds or ms) or an ISO-8601 string; 0 when absent or unreadable. */
    static long createdAt(JsonObject existing) {
        if (!existing.has("createdAt") || existing.get("createdAt").isJsonNull()) return 0L;
        try {
            var v = existing.get("createdAt").getAsJsonPrimitive();
            if (v.isNumber()) {
                long n = v.getAsLong();
                return n < 100_000_000_000L ? n * 1000L : n;
            }
            return java.time.Instant.parse(v.getAsString()).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** POST a report off-thread; {@code callback} runs once on the HTTP thread with the answer (FAILED on any error). */
    public static void sendReport(String body, Consumer<ReportReply> callback) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + REPORT_PATH))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        ReportReply reply;
                        if (err != null) {
                            LOGGER.warn("[DungeonTrain] live report failed: {}", err.toString());
                            reply = ReportReply.of(ReportReply.Kind.FAILED);
                        } else {
                            reply = parseReportReply(resp.statusCode(), resp.body());
                            if (reply.kind() == ReportReply.Kind.FAILED) {
                                LOGGER.warn("[DungeonTrain] live report -> HTTP {} {}", resp.statusCode(), errorOf(resp.body()));
                            }
                        }
                        callback.accept(reply);
                    });
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] live report request failed to start: {}", t.toString());
            callback.accept(ReportReply.of(ReportReply.Kind.FAILED));
        }
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
