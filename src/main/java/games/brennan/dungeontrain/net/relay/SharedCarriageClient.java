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
     * in seq order). See {@code CarriageBlockSnapshot.applyDeltaCells}.
     *
     * <p>{@code owner} is the build's author uuid as the relay recorded it at submit (empty when the
     * relay didn't report one). It lets the spawn path tell a player "you built this" even when the
     * lease came from the ordinary unfiltered pool. {@code credits} is who built and changed it, by
     * display name, for the on-enter credit line.</p>
     */
    public record PoolLease(int id, String token, String blocks, int l, int h, int w,
                            int baseSeq, List<DeltaRec> deltas, String owner, Credits credits) {}

    /** Outcome of a delta POST: transport status + whether the holder should re-baseline (soft/hard). */
    public record DeltaResult(CallStatus status, boolean compactNeeded, boolean mustCompact) {}

    // ---- submit (upload a fresh build; auto-leased back to us) ----

    public static CompletableFuture<Optional<LeaseResult>> submit(String ownerUuid, String ownerName,
                                                                  String blocksBase64,
                                                                  int l, int h, int w, String text, String stage,
                                                                  String mode) {
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
        // Which pool it joins. Free Play builds and normal builds never mix; the relay defaults an
        // absent mode to normal, so this is sent whenever we know it.
        if (mode != null && !mode.isEmpty()) body.addProperty("mode", mode);
        return post("/carriages/submit", body).thenApply(resp -> {
            JsonObject o = okJson(resp);
            if (o == null || !o.has("id")) return Optional.empty();
            String token = o.has("token") && !o.get("token").isJsonNull() ? o.get("token").getAsString() : null;
            boolean deduped = o.has("deduped") && o.get("deduped").getAsBoolean();
            return Optional.of(new LeaseResult(o.get("id").getAsInt(), token, deduped));
        });
    }

    // ---- Train Builder profiles (upload / list / publish / claim) ----

    /**
     * One of this player's builds as the relay lists it — metadata only, no blocks. The mirror of the
     * relay's {@code listByOwner} row, minus the fields nothing in game reads.
     *
     * @param visibility {@code published} once it has been submitted to the train, else {@code profile}
     * @param flag       the moderation verdict; a flagged build is withheld from the pool however
     *                   published it is, which is the only way the player can be told why theirs
     *                   isn't turning up
     */
    public record ProfileBuild(int id, String kind, String subKind, String buildName, String visibility,
                               String source, String stage, String flag, int l, int h, int w,
                               int changeCount, long updatedTs) {}

    /**
     * Upload a Train Builder save. {@code visibility} is {@code profile} for a build that is only in
     * its author's profile so far; {@code kind}/{@code subKind}/{@code buildName} say which template it
     * is, so a later save of the same template updates this row instead of making a second one.
     *
     * <p>Resolves to the relay's {@code (id, token, secret)}. The <b>secret</b> is the durable owner
     * capability and is issued exactly once, here — {@link games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds}
     * persists it, because nothing can re-derive it and without it the build can never be published or
     * claimed back.</p>
     */
    public static CompletableFuture<Optional<BuildUpload>> submitBuild(String ownerUuid, String ownerName,
                                                                       String blocksBase64, int l, int h, int w,
                                                                       String text, String stage, String mode,
                                                                       String kind, String subKind, String buildName,
                                                                       String visibility) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", ownerUuid == null ? "" : ownerUuid);
        if (ownerName != null && !ownerName.isEmpty()) body.addProperty("name", ownerName);
        body.addProperty("world", WORLD);
        body.addProperty("blocks", blocksBase64);
        body.add("dims", dims(l, h, w));
        if (text != null && !text.isEmpty()) body.addProperty("text", text);
        if (stage != null && !stage.isEmpty()) body.addProperty("stage", stage);
        if (mode != null && !mode.isEmpty()) body.addProperty("mode", mode);
        body.addProperty("kind", kind == null ? "" : kind);
        if (subKind != null && !subKind.isEmpty()) body.addProperty("subKind", subKind);
        if (buildName != null && !buildName.isEmpty()) body.addProperty("buildName", buildName);
        body.addProperty("visibility", visibility == null ? "" : visibility);
        // What made it. The relay keeps builder uploads and in-play captures in separate dedupe scopes,
        // so an identical blob never collapses one into the other.
        body.addProperty("source", "builder");
        return post("/carriages/submit", body).thenApply(resp -> {
            JsonObject o = okJson(resp);
            if (o == null || !o.has("id")) {
                logFailure("/carriages/submit", resp);
                return Optional.empty();
            }
            return Optional.of(new BuildUpload(o.get("id").getAsInt(), str(o, "token"), str(o, "secret"),
                    o.has("deduped") && o.get("deduped").getAsBoolean()));
        });
    }

    /** What a build upload got back: the relay's id, the lease token, and the durable owner secret. */
    public record BuildUpload(int id, String token, String secret, boolean deduped) {}

    /**
     * Every build the relay holds for {@code ownerUuid} — what the builder's My Builds screen lists.
     *
     * <p>Resolves to {@code null} when the relay could not be reached or its answer was unusable, and to
     * an empty list when this player simply has no builds. The screen says different things about those
     * two, so they must not collapse into one another.</p>
     */
    public static CompletableFuture<List<ProfileBuild>> listMine(String ownerUuid) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", ownerUuid == null ? "" : ownerUuid);
        return post("/carriages/mine", body).thenApply(resp -> {
            JsonObject o = okJson(resp);
            if (o == null || !o.has("carriages") || !o.get("carriages").isJsonArray()) return null;
            List<ProfileBuild> out = new java.util.ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("carriages")) {
                if (!el.isJsonObject()) continue;
                JsonObject r = el.getAsJsonObject();
                if (!r.has("id")) continue;
                JsonObject d = r.has("dims") && r.get("dims").isJsonObject() ? r.getAsJsonObject("dims") : null;
                out.add(new ProfileBuild(r.get("id").getAsInt(), str(r, "kind"), str(r, "subKind"),
                        str(r, "buildName"), str(r, "visibility"), str(r, "source"), str(r, "stage"),
                        str(r, "flag"), intOf(d, "l"), intOf(d, "h"), intOf(d, "w"),
                        intOf(r, "changeCount"), longOf(r, "updatedTs")));
            }
            return List.copyOf(out);
        });
    }

    /**
     * Put one of this player's builds on the train, or take it back to their profile. Authed by the
     * build's owner {@code secret}.
     *
     * <p>{@link VisibilityResult#inUse()} is an ordinary answer rather than a failure: a build another
     * world is actively holding cannot be withdrawn without stranding that session's edits, so the
     * player is told to try again rather than the relay silently doing nothing.</p>
     */
    public static CompletableFuture<VisibilityResult> publish(int id, String secret, boolean publish) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("secret", secret == null ? "" : secret);
        body.addProperty("publish", publish);
        return post("/carriages/publish", body).thenApply(resp -> {
            if (resp == null) return new VisibilityResult(CallStatus.ERROR, false, false, "");
            int sc = resp.statusCode();
            if (sc == 403) return new VisibilityResult(CallStatus.FORBIDDEN, false, false, "");
            if (sc == 404) return new VisibilityResult(CallStatus.UNKNOWN, false, false, "");
            JsonObject o = sc / 100 == 2 ? asObject(resp) : null;
            if (o == null) return new VisibilityResult(CallStatus.ERROR, false, false, "");
            boolean ok = o.has("ok") && o.get("ok").getAsBoolean();
            boolean inUse = !ok && "in_use".equals(str(o, "reason"));
            return new VisibilityResult(ok ? CallStatus.OK : CallStatus.ERROR, ok, inUse, str(o, "token"));
        });
    }

    /**
     * Outcome of a publish/withdraw: whether it took, whether the build is out on someone's train right
     * now, and — on a withdraw — the fresh lease token the relay handed back so editing can continue.
     */
    public record VisibilityResult(CallStatus status, boolean ok, boolean inUse, String token) {}

    /**
     * Take a lease on one build this player owns, so a later save can write to it. Needed whenever the
     * world isn't holding the lease already — after publishing (which frees it) or after the lease
     * expired. {@link ClaimResult#inUse()} means another world has it right now.
     */
    public static CompletableFuture<ClaimResult> claim(int id, String secret, String holderUuid, String holderName) {
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        body.addProperty("secret", secret == null ? "" : secret);
        if (holderUuid != null && !holderUuid.isEmpty()) body.addProperty("uuid", holderUuid);
        if (holderName != null && !holderName.isEmpty()) body.addProperty("name", holderName);
        body.addProperty("world", WORLD);
        return post("/carriages/claim", body).thenApply(resp -> {
            if (resp == null) {
                logFailure("/carriages/claim", null);
                return new ClaimResult(CallStatus.ERROR, "", false);
            }
            int sc = resp.statusCode();
            if (sc == 403) return new ClaimResult(CallStatus.FORBIDDEN, "", false);
            if (sc == 404) return new ClaimResult(CallStatus.UNKNOWN, "", false);
            JsonObject o = sc / 100 == 2 ? asObject(resp) : null;
            if (o == null) {
                logFailure("/carriages/claim", resp);
                return new ClaimResult(CallStatus.ERROR, "", false);
            }
            boolean ok = o.has("ok") && o.get("ok").getAsBoolean();
            return new ClaimResult(ok ? CallStatus.OK : CallStatus.ERROR, str(o, "token"),
                    !ok && "in_use".equals(str(o, "reason")));
        });
    }

    /** Outcome of a claim: the lease token when it succeeded, or why it didn't. */
    public record ClaimResult(CallStatus status, String token, boolean inUse) {}

    /** An int field, or 0 when absent/garbled — the same tolerance {@link #str} has for strings. */
    private static int intOf(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long longOf(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** The response body as a JSON object, or null — unlike {@link #okJson} this tolerates {@code ok:false}. */
    private static JsonObject asObject(HttpResponse<String> resp) {
        try {
            JsonElement root = JsonParser.parseString(resp.body());
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- lease (pull an existing pooled carriage; PR C) ----

    /**
     * Lease a carriage from the pool. {@code ownerUuid}, when non-empty, narrows the pool to builds by
     * that one author — this is how a player gets their own work handed back. Null/empty leases from
     * the whole pool, as before. {@code mode} picks which pool entirely (Free Play or normal) and is
     * never optional in effect: the relay reads an absent mode as normal. {@code holderName} names this
     * world's holder so OUR edits are credited by name in whichever world leases it next.
     */
    public static CompletableFuture<Optional<PoolLease>> lease(String holderUuid, String holderName,
                                                               int l, int h, int w,
                                                               List<Integer> exclude, String stage,
                                                               String ownerUuid, String mode) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", holderUuid == null ? "" : holderUuid);
        // Names this world's holder on the relay, so OUR edits are credited by name in the next world.
        if (holderName != null && !holderName.isEmpty()) body.addProperty("name", holderName);
        body.addProperty("world", WORLD);
        body.add("dims", dims(l, h, w));
        // Only carriages pooled under this stage are eligible; a slot with no stage never leases.
        if (stage != null && !stage.isEmpty()) body.addProperty("stage", stage);
        if (ownerUuid != null && !ownerUuid.isEmpty()) body.addProperty("owner", ownerUuid);
        if (mode != null && !mode.isEmpty()) body.addProperty("mode", mode);
        if (exclude != null && !exclude.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Integer id : exclude) if (id != null) arr.add(id);
            body.add("exclude", arr);
        }
        // Kid mode narrows the pool to builds the relay flagged kid-safe. Sent only when set, so an
        // Adult world's request is byte-identical to before and an older relay ignores it either way —
        // in which case a Kid world simply gets the unfiltered pool, so the relay must ship first.
        if (games.brennan.dungeontrain.event.SharedCarriageGate.leasesKidSafeOnly()) {
            body.addProperty("kidSafe", true);
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
            String owner = o.has("owner") && !o.get("owner").isJsonNull() ? o.get("owner").getAsString() : "";
            return Optional.of(new PoolLease(o.get("id").getAsInt(), o.get("token").getAsString(),
                    o.get("blocks").getAsString(), dl, dh, dw, baseSeq, parseDeltas(o), owner, parseCredits(o)));
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

    /**
     * Say why a build-lifecycle call failed, at WARN.
     *
     * <p>{@link #post} reports the underlying exception at DEBUG, and the callers collapse every
     * failure into a single "couldn't upload" for the player — so without this a timeout, a refused
     * connection and an HTTP 400 are indistinguishable in the log, and diagnosing one means reading
     * timestamps. Deliberately NOT inside {@link #post} or {@link #statusPost}: those also carry the
     * in-play save/heartbeat/contribute traffic, which fails on a cadence for any offline player and
     * would turn ordinary offline play into a wall of warnings. Uploading a build is rare and
     * deliberate, so one line per failure earns its place.</p>
     *
     * @param resp the response, or null when there was none — timed out, or never connected
     */
    private static void logFailure(String path, HttpResponse<String> resp) {
        if (resp == null) {
            LOGGER.warn("[DungeonTrain] relay {} failed: no response (timed out after {}s, or could not connect)",
                    path, REQUEST_TIMEOUT.toSeconds());
            return;
        }
        String body = resp.body() == null ? "" : resp.body();
        if (body.length() > 200) {
            body = body.substring(0, 200) + "\u2026";
        }
        LOGGER.warn("[DungeonTrain] relay {} failed: HTTP {} {}", path, resp.statusCode(), body);
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
