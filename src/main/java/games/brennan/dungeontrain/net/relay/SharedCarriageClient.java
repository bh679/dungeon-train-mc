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

    /** Cap on a relay-supplied contributor name before it reaches chat (the relay's own cap is 64). */
    private static final int MAX_NAME_CHARS = 32;

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

    /** One opaque delta from a lease: its {@code seq} + the base64 {@code cells} blob to fold. */
    public record DeltaRec(int seq, String cells) {}

    /**
     * Who made a leased carriage: its original builder plus the most recent distinct editors, newest
     * first. Display names only — the relay resolves these, because a contributor's uuid was authored in
     * a world this server has never seen and could never be looked up locally.
     *
     * <p>{@code editors} names at most 5 people, but {@code editorCount} counts every distinct editor
     * (including any whose name predates name capture), so "and N more" never overstates the list.
     * Everything may be blank/empty: a carriage stored before contributor names existed has nothing to
     * credit, and {@link #EMPTY} is what an older relay's lease response yields.
     */
    public record Credits(String creator, List<String> editors, int editorCount) {
        public static final Credits EMPTY = new Credits("", List.of(), 0);

        /** True when there is at least one name worth showing the player. */
        public boolean hasAny() {
            return !creator.isEmpty() || !editors.isEmpty();
        }
    }

    /**
     * A leased pooled carriage: id + token + its base blocks blob + dims, plus the delta log to fold on
     * top ({@code baseSeq} is the relay drop-watermark; the mod applies deltas with {@code seq > baseSeq}
     * in seq order). See {@code CarriageBlockSnapshot.applyDeltaCells}. {@code credits} is who built it.
     */
    public record PoolLease(int id, String token, String blocks, int l, int h, int w,
                            int baseSeq, List<DeltaRec> deltas, Credits credits) {}

    /** Outcome of a delta POST: transport status + whether the holder should re-baseline (soft/hard). */
    public record DeltaResult(CallStatus status, boolean compactNeeded, boolean mustCompact) {}

    // ---- submit (upload a fresh build; auto-leased back to us) ----

    public static CompletableFuture<Optional<LeaseResult>> submit(String ownerUuid, String ownerName,
                                                                  String blocksBase64,
                                                                  int l, int h, int w, String text, String stage) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", ownerUuid == null ? "" : ownerUuid);
        // The builder's name, so other worlds can credit them by name rather than an unresolvable uuid.
        // Only ever sent for a player who has granted network consent (see SharedCarriageGate).
        if (ownerName != null && !ownerName.isEmpty()) body.addProperty("name", ownerName);
        body.addProperty("world", WORLD);
        body.addProperty("blocks", blocksBase64);
        body.add("dims", dims(l, h, w));
        if (text != null && !text.isEmpty()) body.addProperty("text", text);
        // The stage this build was authored in — the relay pools it there, and refuses to lease a
        // carriage that has no stage at all.
        if (stage != null && !stage.isEmpty()) body.addProperty("stage", stage);
        return post("/carriages/submit", body).thenApply(resp -> {
            JsonObject o = okJson(resp);
            if (o == null || !o.has("id")) return Optional.empty();
            String token = o.has("token") && !o.get("token").isJsonNull() ? o.get("token").getAsString() : null;
            boolean deduped = o.has("deduped") && o.get("deduped").getAsBoolean();
            return Optional.of(new LeaseResult(o.get("id").getAsInt(), token, deduped));
        });
    }

    // ---- lease (pull an existing pooled carriage; PR C) ----

    public static CompletableFuture<Optional<PoolLease>> lease(String holderUuid, String holderName,
                                                               int l, int h, int w,
                                                               List<Integer> exclude, String stage) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", holderUuid == null ? "" : holderUuid);
        // Names this world's holder on the relay, so OUR edits are credited by name in the next world.
        if (holderName != null && !holderName.isEmpty()) body.addProperty("name", holderName);
        body.addProperty("world", WORLD);
        body.add("dims", dims(l, h, w));
        // Only carriages pooled under this stage are eligible; a slot with no stage never leases.
        if (stage != null && !stage.isEmpty()) body.addProperty("stage", stage);
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
            int baseSeq = o.has("baseSeq") && !o.get("baseSeq").isJsonNull() ? o.get("baseSeq").getAsInt() : 0;
            return Optional.of(new PoolLease(o.get("id").getAsInt(), o.get("token").getAsString(),
                    o.get("blocks").getAsString(), dl, dh, dw, baseSeq, parseDeltas(o), parseCredits(o)));
        });
    }

    /**
     * Parse the {@code credits:{creator,editors[],editorCount}} block off a lease response. A relay older
     * than this mod simply omits it, so anything missing/garbled yields {@link Credits#EMPTY} and the game
     * shows no credit line at all.
     */
    private static Credits parseCredits(JsonObject o) {
        if (!o.has("credits") || !o.get("credits").isJsonObject()) return Credits.EMPTY;
        JsonObject c = o.getAsJsonObject("credits");
        String creator = sanitizeName(str(c, "creator"));
        List<String> editors = new java.util.ArrayList<>();
        if (c.has("editors") && c.get("editors").isJsonArray()) {
            for (JsonElement el : c.getAsJsonArray("editors")) {
                if (el == null || !el.isJsonPrimitive()) continue;
                String name = sanitizeName(el.getAsString());
                if (!name.isEmpty()) editors.add(name);
            }
        }
        int count = 0;
        try {
            if (c.has("editorCount") && !c.get("editorCount").isJsonNull()) count = c.get("editorCount").getAsInt();
        } catch (RuntimeException ignored) { /* garbage count → fall back to what we can actually name */ }
        return new Credits(creator, List.copyOf(editors), Math.max(count, editors.size()));
    }

    /** A string field, or {@code ""} when absent/null — tolerates a relay that predates the field. */
    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /**
     * Make a relay-supplied name safe to drop into a chat component. The relay already strips these, but
     * these strings go straight into other players' chat, so never trust the wire: § would otherwise be
     * honoured as a legacy formatting code and control characters can corrupt the line.
     */
    private static String sanitizeName(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME_CHARS; i++) {
            char ch = raw.charAt(i);
            if (ch == '\u00a7' || ch < ' ' || ch == '\u007f') continue;
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    /** Parse the {@code deltas:[{seq,cells}]} array off a lease response (empty on absence/garbage). */
    private static List<DeltaRec> parseDeltas(JsonObject o) {
        List<DeltaRec> out = new java.util.ArrayList<>();
        if (o.has("deltas") && o.get("deltas").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("deltas");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject d = el.getAsJsonObject();
                if (d.has("seq") && d.has("cells") && !d.get("cells").isJsonNull()) {
                    out.add(new DeltaRec(d.get("seq").getAsInt(), d.get("cells").getAsString()));
                }
            }
        }
        return out;
    }

    // ---- delta (one per-change upload on a leased carriage) ----

    /**
     * Upload one per-change delta ({@code seq} + opaque base64 {@code cells}) to a leased carriage. The
     * delta doubles as a heartbeat. Resolves to a {@link DeltaResult}: {@code OK} (with {@code
     * compactNeeded} advising a proactive re-baseline), {@code FORBIDDEN}/{@code UNKNOWN} (lost/gone
     * lease), or {@code ERROR} with {@code mustCompact} set when the relay's delta log is full (409).
     */
    public static CompletableFuture<DeltaResult> delta(int id, String token, int seq, String cellsBase64, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("token", token);
        body.addProperty("seq", seq);
        body.addProperty("cells", cellsBase64);
        if (text != null && !text.isEmpty()) body.addProperty("text", text);
        return post("/carriages/delta", body).thenApply(resp -> {
            if (resp == null) return new DeltaResult(CallStatus.ERROR, false, false);
            int sc = resp.statusCode();
            if (sc == 409) return new DeltaResult(CallStatus.ERROR, false, true); // log full → re-baseline
            if (sc == 403) return new DeltaResult(CallStatus.FORBIDDEN, false, false);
            if (sc == 404) return new DeltaResult(CallStatus.UNKNOWN, false, false);
            if (sc / 100 != 2) return new DeltaResult(CallStatus.ERROR, false, false);
            boolean compactNeeded = false;
            try {
                JsonElement root = JsonParser.parseString(resp.body());
                if (root.isJsonObject()) {
                    JsonObject o = root.getAsJsonObject();
                    compactNeeded = o.has("compactNeeded") && o.get("compactNeeded").getAsBoolean();
                }
            } catch (Throwable ignored) { /* best-effort flag */ }
            return new DeltaResult(CallStatus.OK, compactNeeded, false);
        });
    }

    // ---- save / heartbeat / return ----

    /** Full save (also a compaction on the relay: clears the delta log, advances {@code baseSeq}). */
    public static CompletableFuture<CallStatus> save(int id, String token, String blocksBase64, String text, int baseSeq) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("token", token);
        body.addProperty("blocks", blocksBase64);
        body.addProperty("baseSeq", baseSeq);
        if (text != null && !text.isEmpty()) body.addProperty("text", text);
        return statusPost("/carriages/save", body);
    }

    public static CompletableFuture<CallStatus> heartbeat(int id, String token, String holderUuid, String holderName) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("token", token);
        // Carries the host uuid so the relay can backfill a lease claimed before any player was in the
        // level (world-load spawn) — otherwise those stay attributed to nobody for their whole hold.
        if (holderUuid != null && !holderUuid.isEmpty()) body.addProperty("uuid", holderUuid);
        // The name backfills on the same terms but independently: consent may be granted after the lease
        // was claimed, so the name can legitimately arrive later than the uuid did.
        if (holderName != null && !holderName.isEmpty()) body.addProperty("name", holderName);
        return statusPost("/carriages/heartbeat", body);
    }

    /** Return the lease. A non-empty {@code blocksBase64} does a final compacting save (advances {@code baseSeq}). */
    public static CompletableFuture<CallStatus> returnLease(int id, String token, String blocksBase64, String text, int baseSeq) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("token", token);
        if (blocksBase64 != null && !blocksBase64.isEmpty()) {
            body.addProperty("blocks", blocksBase64);
            body.addProperty("baseSeq", baseSeq);
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
