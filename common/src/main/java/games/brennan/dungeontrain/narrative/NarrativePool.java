package games.brennan.dungeontrain.narrative;
import games.brennan.dungeontrain.DtCore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-side, read-mostly cache of approved player-written narrative SERIES fetched from the Dungeon
 * Train relay's {@code /narratives/pool} endpoint, for the narrative-lectern DISCOVERY half. Lectern
 * selection ({@code BookFactory.buildOrRandomForLectern}) reads a snapshot to (sometimes, weighted +
 * tapered) serve a player's narrative instead of a hand-authored mod story, advancing through its
 * letters world-wide as they are read.
 *
 * <h3>Series vs shared books</h3>
 * <p>Unlike {@link SharedBookPool} (which serves one book per roll), a player narrative is a multi-letter
 * SERIES read across many lecterns over a world's life. The pool is therefore a lookup source: the mod
 * {@link #resolve resolves} an in-progress series by id and {@link #pickUnstarted picks} a fresh one to
 * start, rather than rolling a finished item. Continuity is guaranteed by the {@code include=<pinned>}
 * fetch parameter: every refresh forces the world's in-progress seriesIds back into the response (hence
 * the snapshot) even after they rotate past the random window, so a series stays resolvable to its end.</p>
 *
 * <h3>Threading</h3>
 * <p>The snapshot is a single {@code volatile} reference to an immutable list; {@link #resolve} /
 * {@link #pickUnstarted} only read it and never block or touch the network — safe on the server thread.
 * {@link #refreshAsync} is fire-and-forget off-thread (its own {@link HttpClient}) and swaps the snapshot
 * wholesale, so a reader always sees a consistent snapshot.</p>
 */
public final class NarrativePool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max series requested per fetch (the random window; pinned in-progress series come on top). */
    static final int POOL_LIMIT = 20;

    /** Upper bound on the accumulated served/seen seriesId set passed as {@code exclude}. */
    static final int SEEN_CAP = 200;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1; avoids h2c against a bare-Node relay (matches DeathReporter/BookStatsClient)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One approved letter within a series. */
    public record SeriesLetter(int letterIndex, String title, List<String> pages) {}

    /** One approved player narrative series, materialised from the relay's pool response (approved letters only). */
    public record Series(String seriesId, String author, List<SeriesLetter> letters) {}

    /** Current immutable snapshot. Replaced wholesale by {@link #refreshAsync}; read by {@link #resolve}/{@link #pickUnstarted}. */
    private static volatile List<Series> snapshot = List.of();

    /**
     * Total count of APPROVED LETTERS in the relay pool (the letter-granular taper denominator {@code P},
     * NOT the ≤{@link #POOL_LIMIT} series window). Reported by the relay's {@code /narratives/pool}
     * response {@code total} field and refreshed on every fetch. Stays 0 against an unreachable / old
     * relay, which the lectern taper reads as "nothing to discover" → mod-only selection.
     */
    private static volatile int approvedTotal = 0;

    /**
     * SeriesIds the world has served / seen, used as the {@code exclude} filter so repeat fetches walk
     * toward fresh series. Insertion-ordered so the oldest evict first at {@link #SEEN_CAP}. In-progress
     * series are forced back in via {@code include} regardless of this set.
     */
    private static final Set<String> SEEN_IDS = new LinkedHashSet<>();

    /** Prevents overlapping in-flight fetches (a slow relay shouldn't stack requests every tick). */
    private static volatile boolean fetchInFlight = false;

    private NarrativePool() {}

    /** Whether the pool currently holds any series (cheap volatile read). */
    public static boolean isEmpty() {
        return snapshot.isEmpty();
    }

    /**
     * Total approved LETTERS in the relay pool as of the last successful fetch, or 0 when the relay is
     * unreachable / has never replied / is too old to report it. The lectern taper treats 0 as "nothing
     * to discover" and serves only mod stories, so this is safe to read without a loading guard.
     */
    public static int approvedTotal() {
        return approvedTotal;
    }

    /**
     * Resolve a series by id from the current snapshot (which, while the relay is reachable, always holds
     * the world's pinned in-progress series). {@link Optional#empty()} when the series is not currently in
     * the snapshot (e.g. relay went empty/offline right after a restart) — the caller then falls through to
     * mod stories and retries on the next refresh, never hard-failing a lectern.
     */
    public static Optional<Series> resolve(String seriesId) {
        if (seriesId == null) return Optional.empty();
        for (Series s : snapshot) {
            if (seriesId.equals(s.seriesId())) return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * Deterministically pick a series the world has not started yet — from the snapshot, skipping any id in
     * {@code excludeStarted} and any series with no servable letters. Deterministic per {@code seed} so the
     * same lectern at the same world state always starts the same series. {@link Optional#empty()} when the
     * snapshot has no fresh series (empty pool, or every windowed series already started).
     */
    public static Optional<Series> pickUnstarted(long seed, Set<String> excludeStarted) {
        List<Series> pool = snapshot; // single volatile read → consistent snapshot
        List<Series> avail = new ArrayList<>();
        for (Series s : pool) {
            if (!s.letters().isEmpty() && !excludeStarted.contains(s.seriesId())) avail.add(s);
        }
        if (avail.isEmpty()) return Optional.empty();
        int index = (int) Long.remainderUnsigned(mix(seed), avail.size());
        return Optional.of(avail.get(index));
    }

    /**
     * Fetch a fresh pool from the relay off-thread and swap in the new snapshot. No-throw; a failed or slow
     * fetch leaves the existing snapshot in place. Skips if a fetch is already in flight.
     *
     * @param pinnedInProgress in-progress seriesIds to force back into the response via {@code include=} so
     *                         a mid-read series stays resolvable even after it rotates past the random window.
     * @param hostLang         the host player's raw client locale (e.g. {@code "en_us"}) for language-matched
     *                         delivery, or {@code ""}/{@code null} to leave the pool unfiltered. See
     *                         {@link WorldLanguage#hostLocale}. Pinned in-progress series still resolve
     *                         regardless of language (relay-side), so continuity never breaks.
     */
    public static void refreshAsync(Set<String> pinnedInProgress, String hostLang) {
        if (fetchInFlight) return;
        fetchInFlight = true;
        try {
            String exclude = excludeCsv();
            boolean hadExclude = !exclude.isEmpty();
            String include = idsCsv(pinnedInProgress);
            String url = DtCore.relayBaseUrl()
                    + "/narratives/pool?exclude=" + exclude + "&include=" + include + "&limit=" + POOL_LIMIT
                    + langParam(hostLang);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] narrative pool fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                LOGGER.debug("[DungeonTrain] narrative pool fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            applyResponse(resp.body(), hadExclude);
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] narrative pool parse failed: {}", t.toString());
                        } finally {
                            fetchInFlight = false;
                        }
                    });
        } catch (Throwable t) {
            fetchInFlight = false;
            LOGGER.debug("[DungeonTrain] narrative pool refresh failed to start: {}", t.toString());
        }
    }

    /**
     * Parse the relay JSON body, build the new snapshot, and accumulate served ids into the seen-set.
     * {@code hadExclude} disambiguates the two zero-series outcomes exactly as {@link SharedBookPool}: an
     * exclude-starvation (seen-set covers the whole pool) resets the seen-set and KEEPS the current snapshot
     * (silent full cycle), whereas a genuinely empty pool (no exclude, still nothing) clears the snapshot.
     */
    static void applyResponse(String body, boolean hadExclude) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) {
            LOGGER.debug("[DungeonTrain] narrative pool response not ok");
            return;
        }
        // Capture the relay-reported approved-letter total before the series-array checks, so it refreshes
        // even on the exclude-starvation / empty branches (the relay counts the whole pool, not the window).
        if (obj.has("total") && obj.get("total").isJsonPrimitive()) {
            try {
                approvedTotal = Math.max(0, obj.get("total").getAsInt());
            } catch (RuntimeException ignored) {
                // non-numeric total — keep the last known value
            }
        }
        if (!obj.has("series") || !obj.get("series").isJsonArray()) {
            LOGGER.debug("[DungeonTrain] narrative pool response missing series array — keeping last snapshot");
            return;
        }
        List<Series> parsed = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("series")) {
            if (!el.isJsonObject()) continue;
            Series s = parseSeries(el.getAsJsonObject());
            if (s != null) parsed.add(s);
        }
        if (parsed.isEmpty()) {
            if (hadExclude) {
                resetSeen();
                LOGGER.debug("[DungeonTrain] narrative pool exhausted by exclude filter — reset seen-set, keeping {} series",
                        snapshot.size());
            } else {
                snapshot = List.of();
                LOGGER.debug("[DungeonTrain] narrative pool is empty");
            }
            return;
        }
        snapshot = List.copyOf(parsed);
        rememberSeen(parsed);
        LOGGER.debug("[DungeonTrain] narrative pool refreshed: {} series", parsed.size());
    }

    /** Materialise one series; returns {@code null} if it lacks a seriesId or has no servable letters. */
    private static Series parseSeries(JsonObject o) {
        if (!o.has("seriesId") || o.get("seriesId").isJsonNull()) return null;
        String seriesId = o.get("seriesId").getAsString();
        if (seriesId.isEmpty()) return null;
        String author = o.has("author") && !o.get("author").isJsonNull() ? o.get("author").getAsString() : "";
        List<SeriesLetter> letters = new ArrayList<>();
        if (o.has("letters") && o.get("letters").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("letters")) {
                if (!el.isJsonObject()) continue;
                SeriesLetter l = parseLetter(el.getAsJsonObject());
                if (l != null) letters.add(l);
            }
        }
        if (letters.isEmpty()) return null; // no approved content → not servable
        return new Series(seriesId, author, letters);
    }

    /** Materialise one letter; returns {@code null} without a positive letterIndex. */
    private static SeriesLetter parseLetter(JsonObject o) {
        if (!o.has("letterIndex") || o.get("letterIndex").isJsonNull()) return null;
        int letterIndex;
        try {
            letterIndex = o.get("letterIndex").getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
        if (letterIndex <= 0) return null;
        String title = o.has("title") && !o.get("title").isJsonNull() ? o.get("title").getAsString() : "";
        List<String> pages = new ArrayList<>();
        if (o.has("pages") && o.get("pages").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("pages");
            for (JsonElement p : arr) {
                pages.add(p.isJsonNull() ? "" : p.getAsString());
            }
        }
        return new SeriesLetter(letterIndex, title, pages);
    }

    private static synchronized void rememberSeen(List<Series> series) {
        for (Series s : series) {
            SEEN_IDS.add(s.seriesId());
        }
        while (SEEN_IDS.size() > SEEN_CAP) {
            var it = SEEN_IDS.iterator();
            it.next();
            it.remove();
        }
    }

    private static synchronized String excludeCsv() {
        if (SEEN_IDS.isEmpty()) return "";
        return String.join(",", SEEN_IDS);
    }

    private static synchronized void resetSeen() {
        SEEN_IDS.clear();
    }

    /** Comma-join a set of seriesIds for the {@code include}/{@code exclude} query params (empty → ""). */
    private static String idsCsv(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        return ids.stream().filter(s -> s != null && !s.isEmpty()).collect(Collectors.joining(","));
    }

    /** The {@code &lang=<locale>} query fragment for language-matched delivery, or {@code ""} when blank. */
    static String langParam(String hostLang) {
        if (hostLang == null || hostLang.isBlank()) return "";
        return "&lang=" + URLEncoder.encode(hostLang, StandardCharsets.UTF_8);
    }

    /** Splittable-mix so a raw lectern seed spreads uniformly across the series index. */
    private static long mix(long seed) {
        long state = seed ^ 0x4E415252415449L; // "NARRATI"
        state = (state ^ (state >>> 30)) * 0xBF58476D1CE4E5B9L;
        state = (state ^ (state >>> 27)) * 0x94D049BB133111EBL;
        state = state ^ (state >>> 31);
        return state;
    }

    /** Test/reset hook: drop the snapshot and seen-set (used by unit tests and on server stop). */
    public static synchronized void clear() {
        snapshot = List.of();
        SEEN_IDS.clear();
        fetchInFlight = false;
        approvedTotal = 0;
    }
}
