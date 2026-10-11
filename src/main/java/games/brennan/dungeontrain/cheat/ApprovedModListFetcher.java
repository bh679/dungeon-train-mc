package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Off-thread reader for the relay's {@code GET /approved-mods} endpoint — the updatable overlay for
 * {@link ApprovedModList}. Modelled on {@link CheatModListFetcher}: own {@link HttpClient},
 * fire-and-forget, fully no-throw. Any failure just leaves the baked ∪ last-cached list in place,
 * which is the safe direction — a relay outage must never invent unapproved mods.
 *
 * <p>The request is anonymous — no uuid or session, only {@code ?dt=<this jar's version>} — so it
 * runs regardless of the network-consent setting (same deliberate product decision as the cheat-mod
 * and official-links overlays). The version is what lets the relay hold an approval back from jars
 * too old for it: an approval set with a minimum DT version is served as {@code approved} to a jar
 * at or above it, and as {@code revoked} to anything older — including every jar from before this
 * parameter existed, which sends none. It is always on: this is an integrity feature, and an off-switch would only let a
 * cheater freeze the list at whatever their jar shipped with.</p>
 */
public final class ApprovedModListFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Pin HTTP/1.1 for the same reason CheatModListFetcher does: Java's default HTTP/2 client can't
    // h2c-upgrade over plaintext http://, which breaks local 127.0.0.1 relay testing.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /**
     * How long to wait for {@link ModFileScanner} before giving up on the hash-verified POST and
     * sending the plain GET instead. The scan is a few seconds for a 150-jar modpack; past this the
     * launch should not keep waiting on it, and the cached hash list stands for this session.
     */
    static final long SCAN_WAIT_SECONDS = 20;

    private static volatile boolean attempted;
    private static volatile boolean failed;
    /** True once the current attempt has finished, successfully or not. */
    private static volatile boolean settled;

    private ApprovedModListFetcher() {}

    /** Kick off the one-per-session relay fetch (retrying a previously failed attempt). No-throw. */
    public static void ensureFetched() {
        if (attempted && !failed) return;
        attempted = true;
        failed = false;
        settled = false;
        fetchAsync();
    }

    /**
     * Has the latest fetch attempt finished (landed, failed, or never started)? The Unsupported Mods
     * popup waits on this briefly so it judges against live approvals rather than a stale cache.
     */
    public static boolean isSettled() {
        return settled || !attempted;
    }

    /**
     * {@code <base>/approved-mods?dt=<version>}, or without the parameter when the version is
     * unknown (the relay then treats this jar as older than every floor). Pure, for tests.
     */
    static String requestUrl(String base, String dtVersion) {
        String url = base + "/approved-mods";
        String v = dtVersion == null ? "" : dtVersion.trim();
        // VersionInfo reports "?" when it can't read the jar's version — send nothing rather than junk.
        if (v.isEmpty() || !Character.isDigit(v.charAt(0))) return url;
        return url + "?dt=" + URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** {@code <base>/approved-mods/verify} — the hash-verified POST. Pure, for tests. */
    static String verifyUrl(String base) {
        return base + "/approved-mods/verify";
    }

    /**
     * The verify body: {@code {dt, files:[{sha1, murmur2, size, fileName, modIds}]}}. {@code dt} is
     * omitted when the version is unreadable, as the GET omits its parameter. Pure, for tests.
     */
    static String verifyBody(String dtVersion, List<ModFileFingerprint> files) {
        JsonObject body = new JsonObject();
        String v = dtVersion == null ? "" : dtVersion.trim();
        if (!v.isEmpty() && Character.isDigit(v.charAt(0))) body.addProperty("dt", v);
        JsonArray arr = new JsonArray();
        for (ModFileFingerprint f : files) arr.add(f.toJson());
        body.add("files", arr);
        return body.toString();
    }

    /**
     * Fetch the approved-mod list off-thread; results land in {@link ApprovedModList}. No-throw.
     *
     * <p>Two shapes. When {@link ModFileScanner} has fingerprinted the installed jars, they go up in
     * a {@code POST /approved-mods/verify}, whose answer is the GET's payload plus the approved
     * file hashes and a per-file verdict. When the scan is empty or late, or the relay predates the
     * route (404/405), it is the plain GET — the id list still refreshes, and the cached hash list
     * stands.</p>
     */
    static void fetchAsync() {
        try {
            ModFileScanner.scan()
                .orTimeout(SCAN_WAIT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(t -> List.of())
                .thenAccept(files -> {
                    if (files.isEmpty()) sendGet();
                    else sendVerify(files);
                })
                .exceptionally(t -> {
                    LOGGER.debug("[DungeonTrain] approved-mod list request failed to start: {}", t.toString());
                    failed = true;
                    settled = true;
                    return null;
                });
        } catch (Throwable t) {
            // Building the request failed synchronously — swallow; baked ∪ cache stay in force.
            LOGGER.debug("[DungeonTrain] approved-mod list request failed to start: {}", t.toString());
            failed = true;
            settled = true;
        }
    }

    private static void sendGet() {
        try {
            String url = requestUrl(DungeonTrain.relayBaseUrl(), VersionInfo.VERSION);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            settle(resp, err);
                        } finally {
                            settled = true;
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] approved-mod list request failed to start: {}", t.toString());
            failed = true;
            settled = true;
        }
    }

    private static void sendVerify(List<ModFileFingerprint> files) {
        try {
            String body = verifyBody(VersionInfo.VERSION, files);
            HttpRequest req = HttpRequest.newBuilder(URI.create(verifyUrl(DungeonTrain.relayBaseUrl())))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        // A relay from before the route answers 404 (or 405): fall back to the GET so
                        // the id list still refreshes. Everything else settles here.
                        if (err == null && (resp.statusCode() == 404 || resp.statusCode() == 405)) {
                            LOGGER.debug("[DungeonTrain] approved-mod verify -> HTTP {} — relay predates it, using GET",
                                resp.statusCode());
                            sendGet();
                            return;
                        }
                        try {
                            settle(resp, err);
                            if (err == null && resp.statusCode() / 100 == 2) logVerdicts(resp.body(), files.size());
                        } finally {
                            settled = true;
                        }
                    });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] approved-mod verify failed to start, using GET: {}", t.toString());
            sendGet();
        }
    }

    /** One INFO line naming the files the relay could not verify — the first place to look when a run is Free Play. */
    static void logVerdicts(String body, int sent) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("files")) return;
            java.util.List<String> unverified = new java.util.ArrayList<>();
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("files")) {
                if (!e.isJsonObject()) continue;
                JsonObject f = e.getAsJsonObject();
                String status = f.has("status") ? f.get("status").getAsString() : "";
                if (!"approved".equals(status)) {
                    unverified.add((f.has("sha1") ? f.get("sha1").getAsString().substring(0, 8) : "?")
                        + ":" + status + (f.has("reason") ? "/" + f.get("reason").getAsString() : ""));
                }
            }
            if (unverified.isEmpty()) {
                LOGGER.info("[DungeonTrain] approved-mod verify: all {} installed file(s) verified", sent);
            } else {
                LOGGER.info("[DungeonTrain] approved-mod verify: {} of {} file(s) not verified: {}",
                    unverified.size(), sent, String.join(", ", unverified));
            }
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] approved-mod verify: could not read verdicts: {}", t.toString());
        }
    }

    private static void settle(HttpResponse<String> resp, Throwable err) {
        try {
            if (err != null) {
                LOGGER.debug("[DungeonTrain] approved-mod list fetch failed: {}", err.toString());
                failed = true;
                return;
            }
            if (resp.statusCode() / 100 != 2) {
                LOGGER.debug("[DungeonTrain] approved-mod list fetch -> HTTP {}", resp.statusCode());
                failed = true;
                return;
            }
            ApprovedModList.Payload payload = ApprovedModList.parse(resp.body());
            if (payload == null) {
                // Unreadable body: keep baked ∪ cache, including the enforce flag.
                LOGGER.debug("[DungeonTrain] approved-mod list fetch -> unreadable body");
                failed = true;
                return;
            }
            ApprovedModList.accept(payload);
            LOGGER.info("[DungeonTrain] approved-mod list updated from relay "
                    + "({} approval(s), {} revocation(s), enforce={}, {} file hash(es), enforceHashes={})",
                payload.approved().size(), payload.revoked().size(), payload.enforce(),
                payload.hashes().size(), payload.enforceHashes());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] approved-mod list parse failed: {}", t.toString());
            failed = true;
        }
    }
}
