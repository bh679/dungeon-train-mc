package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the relay's shared-carriage endpoints ({@code /carriages/*}). Unlike the durable
 * {@link RelayOutbox} (which discards the response body), a submit/lease must READ back the
 * relay-assigned {@code id} + lease {@code token}, so this uses its own async HTTP/1.1 client like
 * {@link games.brennan.dungeontrain.narrative.SharedBookPool}. All calls are best-effort and no-throw;
 * failures resolve to an empty/ERROR result so the caller (the events tick) can retry next cycle.
 */
public final class SharedCarriageClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1 (bare Node); avoid h2c
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Stable per-process token identifying THIS world/server as a lease holder to the relay (the
     * {@code world} field). One per JVM lifetime — a restart is a new holder, and the relay's TTL frees
     * any leases the old process didn't return.
     */
    public static final String WORLD = UUID.randomUUID().toString();

    private SharedCarriageClient() {}

    /** Outcome of a save/heartbeat/return call. */
    public enum CallStatus { OK, FORBIDDEN, UNKNOWN, ERROR }

    /** A relay lease handle: the row id + lease token (token may be null on a dedupe we couldn't claim). */
    public record LeaseResult(int id, String token, boolean deduped) {}

    /** A leased pooled carriage: id + token + its blocks blob and dims. */
    public record PoolLease(int id, String token, String blocks, int l, int h, int w) {}

    // ---- submit (upload a fresh build; auto-leased back to us) ----

    public static CompletableFuture<Optional<LeaseResult>> submit(String ownerUuid, String blocksBase64,
                                                                  int l, int h, int w, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", ownerUuid == null ? "" : ownerUuid);
        body.addProperty("world", WORLD);
        body.addProperty("blocks", blocksBase64);
        body.add("dims", dims(l, h, w));
        if (text != null && !text.isEmpty()) body.addProperty("text", text);
        return post("/carriages/submit", body).thenApply(resp -> {
            JsonObject o = okJson(resp);
            if (o == null || !o.has("id")) return Optional.empty();
            String token = o.has("token") && !o.get("token").isJsonNull() ? o.get("token").getAsString() : null;
            boolean deduped = o.has("deduped") && o.get("deduped").getAsBoolean();
            return Optional.of(new LeaseResult(o.get("id").getAsInt(), token, deduped));
        });
    }

    // ---- lease (pull an existing pooled carriage; PR C) ----

    public static CompletableFuture<Optional<PoolLease>> lease(String holderUuid, int l, int h, int w,
                                                               List<Integer> exclude) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", holderUuid == null ? "" : holderUuid);
        body.addProperty("world", WORLD);
        body.add("dims", dims(l, h, w));
        if (exclude != null && !exclude.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Integer id : exclude) if (id != null) arr.add(id);
            body.add("exclude", arr);
        }
        return post("/carriages/lease", body).thenApply(resp -> {
            JsonObject o = okJson(resp);
            if (o == null || (o.has("none") && o.get("none").getAsBoolean())) return Optional.empty();
            if (!o.has("id") || !o.has("token") || !o.has("blocks")) return Optional.empty();
            JsonObject d = o.has("dims") && o.get("dims").isJsonObject() ? o.getAsJsonObject("dims") : null;
            int dl = d != null && d.has("l") ? d.get("l").getAsInt() : l;
            int dh = d != null && d.has("h") ? d.get("h").getAsInt() : h;
            int dw = d != null && d.has("w") ? d.get("w").getAsInt() : w;
            return Optional.of(new PoolLease(o.get("id").getAsInt(), o.get("token").getAsString(),
                    o.get("blocks").getAsString(), dl, dh, dw));
        });
    }

    // ---- save / heartbeat / return ----

    public static CompletableFuture<CallStatus> save(int id, String token, String blocksBase64, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("token", token);
        body.addProperty("blocks", blocksBase64);
        if (text != null && !text.isEmpty()) body.addProperty("text", text);
        return statusPost("/carriages/save", body);
    }

    public static CompletableFuture<CallStatus> heartbeat(int id, String token) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("token", token);
        return statusPost("/carriages/heartbeat", body);
    }

    public static CompletableFuture<CallStatus> returnLease(int id, String token, String blocksBase64, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("token", token);
        if (blocksBase64 != null && !blocksBase64.isEmpty()) {
            body.addProperty("blocks", blocksBase64);
            if (text != null && !text.isEmpty()) body.addProperty("text", text);
        }
        return statusPost("/carriages/return", body);
    }

    // ---- report (fire-and-forget) ----

    public static void report(int id, String reporterUuid, String reason) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        if (reporterUuid != null) body.addProperty("uuid", reporterUuid);
        if (reason != null) body.addProperty("reason", reason);
        post("/carriages/report", body); // ignore result
    }

    // ---- transport ----

    private static JsonObject dims(int l, int h, int w) {
        JsonObject d = new JsonObject();
        d.addProperty("l", l);
        d.addProperty("h", h);
        d.addProperty("w", w);
        return d;
    }

    /** POST the JSON body; resolves to the HttpResponse (status < 0 on transport failure). */
    private static CompletableFuture<HttpResponse<String>> post(String path, JsonObject body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .exceptionally(e -> {
                        LOGGER.debug("[DungeonTrain] carriage {} failed: {}", path, e.toString());
                        return null;
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] carriage {} failed to start: {}", path, t.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    /** POST that only cares about success/forbidden/unknown for save/heartbeat/return. */
    private static CompletableFuture<CallStatus> statusPost(String path, JsonObject body) {
        return post(path, body).thenApply(resp -> {
            if (resp == null) return CallStatus.ERROR;
            int sc = resp.statusCode();
            if (sc / 100 == 2) return CallStatus.OK;
            if (sc == 403) return CallStatus.FORBIDDEN;
            if (sc == 404) return CallStatus.UNKNOWN;
            return CallStatus.ERROR;
        });
    }

    /** Parse a 2xx JSON object with {@code ok:true}, or null. */
    private static JsonObject okJson(HttpResponse<String> resp) {
        if (resp == null || resp.statusCode() / 100 != 2) return null;
        try {
            JsonElement root = JsonParser.parseString(resp.body());
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            return (o.has("ok") && o.get("ok").getAsBoolean()) ? o : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
