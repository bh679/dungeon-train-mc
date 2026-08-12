package games.brennan.dungeontrain.progress;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the relay's {@code /progress/*} endpoints — the player's canonical Ender Chest and
 * advancement/progress state.
 *
 * <p>Unlike the telemetry reporters, these calls both read back and overwrite the player's own items,
 * so every request past the auth handshake carries an {@code Authorization: Bearer <secret>} header;
 * a uuid in the body is not a credential (see {@link ProgressCredential}).</p>
 *
 * <p>Same posture as {@link games.brennan.dungeontrain.net.relay.SharedCarriageClient}: its own async
 * HTTP/1.1 client (the relay is bare Node — avoid h2c), and every call is best-effort and no-throw,
 * resolving to {@code null} on any failure so a caller can fall back to local state rather than
 * failing a login.</p>
 */
public final class ProgressClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private ProgressClient() {}

    /** The relay's view of an account's stored progress, plus what a write refused. */
    public record State(Map<String, ProgressBlob> blobs, List<String> conflicts) {
        public static final State EMPTY = new State(Map.of(), List.of());

        public ProgressBlob get(String kind) {
            return blobs.get(kind);
        }
    }

    // ---- auth -------------------------------------------------------------------

    /**
     * Ask the relay for a single-use {@code serverId} nonce for the Mojang join-server handshake.
     * Resolves to {@code null} when the relay is unreachable or the feature is off.
     */
    public static CompletableFuture<String> challenge() {
        return post("/progress/auth/challenge", "{}", null)
                .thenApply(json -> json == null ? null : optString(json, "serverId"));
    }

    /**
     * Complete the handshake: the client has already called {@code joinServer} against Mojang with
     * {@code serverId}; the relay now confirms it and returns this device's credential for the
     * {@code mojang:<uuid>} account. Resolves to {@code null} when Mojang did not confirm — the
     * expected outcome for an offline/cracked profile, and the caller's cue to use {@link #mintToken}.
     */
    public static CompletableFuture<ProgressCredential> verify(String serverId, String username) {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", serverId);
        body.addProperty("username", username);
        return post("/progress/auth/verify", body.toString(), null).thenApply(ProgressClient::readCredential);
    }

    /**
     * Mint a fresh {@code token:} account for a player who could not be verified. The returned secret
     * doubles as the player's recovery code.
     */
    public static CompletableFuture<ProgressCredential> mintToken(UUID uuid, String name) {
        JsonObject body = new JsonObject();
        if (uuid != null) body.addProperty("uuid", uuid.toString().replace("-", ""));
        if (name != null) body.addProperty("name", name);
        return post("/progress/auth/token", body.toString(), null).thenApply(ProgressClient::readCredential);
    }

    /**
     * Link this install to an existing account using a secret the player already holds — the recovery
     * code pasted from another machine. Resolves to {@code null} when the code is unknown, which the
     * screen reports as a wrong code rather than silently stranding the player on a new account.
     */
    public static CompletableFuture<ProgressCredential> attach(String secret) {
        JsonObject body = new JsonObject();
        body.addProperty("secret", secret);
        return post("/progress/auth/token", body.toString(), null)
                .thenApply(json -> {
                    ProgressCredential c = readCredential(json);
                    // The attach response echoes the account but not a secret (the caller already has it).
                    return c == null ? null : c.withSecret(secret);
                });
    }

    // ---- state ------------------------------------------------------------------

    /** Fetch every stored blob for the bearer's account. Resolves to {@code null} on any failure. */
    public static CompletableFuture<State> fetchState(String secret) {
        if (secret == null || secret.isBlank()) return CompletableFuture.completedFuture(null);
        HttpRequest req = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + "/progress/state"))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + secret)
                .GET()
                .build();
        return send(req, "state fetch").thenApply(ProgressClient::readState);
    }

    /**
     * Merge {@code blobs} into the account and resolve the FULL post-merge state, so one round trip
     * leaves this install consistent even when its Ender Chest lost to a newer one (the winner is in
     * the response, and its kind is named in {@link State#conflicts}).
     */
    public static CompletableFuture<State> pushState(String secret, List<ProgressBlob> blobs) {
        if (secret == null || secret.isBlank() || blobs == null || blobs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        JsonArray arr = new JsonArray();
        for (ProgressBlob b : blobs) arr.add(b.toJson());
        JsonObject body = new JsonObject();
        body.add("blobs", arr);
        return post("/progress/state", body.toString(), secret).thenApply(ProgressClient::readState);
    }

    // ---- plumbing ---------------------------------------------------------------

    private static CompletableFuture<JsonObject> post(String path, String body, String secret) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(DungeonTrain.relayBaseUrl() + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (secret != null) b.header("Authorization", "Bearer " + secret);
            return send(b.build(), "POST " + path);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] progress: request to {} could not start — {}", path, t.toString());
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Send and parse, resolving to {@code null} on any transport, status or parse failure. */
    private static CompletableFuture<JsonObject> send(HttpRequest req, String what) {
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> {
                    if (err != null || resp == null) {
                        LOGGER.debug("[DungeonTrain] progress: {} failed — {}", what,
                                err == null ? "no response" : err.toString());
                        return null;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        // 401 (stale credential) and 404 (unknown recovery code) are ordinary outcomes,
                        // not faults; the caller decides what they mean.
                        LOGGER.debug("[DungeonTrain] progress: {} → HTTP {}", what, resp.statusCode());
                        return null;
                    }
                    try {
                        JsonElement el = JsonParser.parseString(resp.body());
                        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
                    } catch (Throwable t) {
                        LOGGER.debug("[DungeonTrain] progress: {} parse failed — {}", what, t.toString());
                        return null;
                    }
                });
    }

    private static ProgressCredential readCredential(JsonObject json) {
        if (json == null || !json.has("account") || !json.get("account").isJsonObject()) return null;
        JsonObject a = json.getAsJsonObject("account");
        String accountId = a.has("accountId") ? String.valueOf(a.get("accountId").getAsInt()) : null;
        if (accountId == null) return null;
        return new ProgressCredential(
                accountId,
                optString(a, "kind"),
                optString(a, "principal"),
                optString(json, "secret"),
                optString(a, "uuid"),
                optString(a, "name"));
    }

    private static State readState(JsonObject json) {
        if (json == null || !json.has("blobs") || !json.get("blobs").isJsonArray()) return null;
        Map<String, ProgressBlob> blobs = new LinkedHashMap<>();
        for (JsonElement el : json.getAsJsonArray("blobs")) {
            if (!el.isJsonObject()) continue;
            ProgressBlob b = ProgressBlob.fromJson(el.getAsJsonObject());
            if (b != null) blobs.put(b.kind(), b);
        }
        List<String> conflicts = new ArrayList<>();
        if (json.has("conflicts") && json.get("conflicts").isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray("conflicts")) {
                if (el.isJsonPrimitive()) conflicts.add(el.getAsString());
            }
        }
        return new State(Map.copyOf(blobs), List.copyOf(conflicts));
    }

    private static String optString(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
