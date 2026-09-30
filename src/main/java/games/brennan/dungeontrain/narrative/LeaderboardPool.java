package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side cache of the public leaderboards, fetched from the relay's {@code /leaderboard}
 * endpoint and handed to {@link LeaderboardBookFactory} to write into found books.
 *
 * <p>Server-side for the same reason {@link BackerPool} is: book pages are baked into item NBT on
 * the server, so a client-side fetch would arrive long after the book already exists.</p>
 *
 * <h2>Cost</h2>
 * <p>Every fetch here is one request for one board, and a tick issues at most one. Boards are pulled
 * <em>lazily</em> — a category is only ever requested after a book has actually rolled it, so a
 * server whose players never see a category never asks for it. The relay serves these pre-serialized
 * behind a 300s edge cache, so the refresh interval here is deliberately unhurried: a leaderboard
 * that is five minutes stale is not wrong in any way a reader could notice.</p>
 *
 * <p>Per-player ranks are fetched from {@code /leaderboard/me} and cached by uuid: once at login,
 * and again shortly after each death, which is the moment a player's own position actually moves.
 * That is what lets a book's closing "where you stand" line cost nothing at the moment the book is
 * opened — by then the answer is already here. A per-player cooldown
 * ({@link #RANK_ATTEMPT_COOLDOWN_MS}) keeps a run of quick deaths down to one request.</p>
 *
 * <p>Never throws and never blocks. A failed, slow or empty response leaves the previous snapshot in
 * place, and a player with no network simply finds an ordinary random book instead.</p>
 */
public final class LeaderboardPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Rows to ask for — the relay's whole published slice, deliberately more than a book shows.
     *
     * <p>A book holds {@code LeaderboardBookFactory.MAX_ROWS} ranks, but asking for exactly that many
     * would cost the relay MORE, not less: it serves the full board from one pre-serialized buffer
     * and re-serializes per request for any shorter limit. Asking for all of it is the cheap path.</p>
     */
    static final int FETCH_LIMIT = 100;

    /** A board older than this is refetched the next time its category is wanted. */
    private static final long BOARD_TTL_MS = 300_000L;

    /**
     * How long a category that answered with nothing is left alone before being asked again.
     *
     * <p>{@link #BOARD_TTL_MS} can only throttle a category that produced rows, because a {@link Board}
     * is the only thing carrying a timestamp. A category the relay has no rows for — one nobody has
     * scored on yet, or an id this relay build does not serve and answers {@code bad_cat} — never
     * reaches {@link #BOARDS} at all, so without this it was re-requested on every call. That is not
     * hypothetical: {@link #warmAll()} runs from a stat room's per-tick stocking pass, so a single
     * unservable category became one request per tick for the rest of the session.</p>
     *
     * <p>Shorter than the board TTL, so a board that starts being served still appears promptly.</p>
     */
    private static final long EMPTY_ATTEMPT_COOLDOWN_MS = 60_000L;

    /**
     * Shortest gap between two rank fetches for the SAME player.
     *
     * <p>{@code /leaderboard/me} is the one leaderboard call that touches SQLite — an indexed count
     * per board, fifteen of them in a single request — so it is the one that must not be reachable in
     * a loop. Login asks once; a death asks again. A player dying every few seconds in lava would
     * otherwise ask every few seconds, and their rank does not meaningfully move in that time.</p>
     */
    static final long RANK_ATTEMPT_COOLDOWN_MS = 60_000L;

    /** Ceilings mirroring the relay's own, so an out-of-date or wrong relay can't push junk into a book. */
    static final int MAX_ROWS = 200;
    static final int MAX_NAME_LEN = 32;
    static final int MAX_ERAS = 64;
    static final int MAX_ERA_LABEL_LEN = 64;

    /**
     * The era list ({@code /leaderboard/eras}) changes only when a balancing release retires an era
     * or a year turns, so it is asked for rarely. Cheap on the relay either way: a pre-serialized
     * buffer, like a board.
     */
    private static final long ERA_TTL_MS = 1_800_000L;

    /** The era a board is read for when none is named: the relay's current one. */
    public static final String CURRENT = "";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1; avoids h2c against a bare-Node relay
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** One board's rows, in rank order. */
    public record Entry(String name, long score) {}

    /**
     * One era the one-life boards are cut into (relay: leaderboard-eras.js) — a game version's
     * stretch or a calendar year. {@code current} is the era new runs score into; everything else
     * is retired, kept for good, and what a retired-board book is about.
     */
    public record Era(String id, String kind, String label, String minVersion, boolean current) {
        public boolean isRetired() { return !current; }
        public boolean isYear() { return "year".equals(kind); }
        /** The founding version era: the one every jar older than the first retirement scores into. */
        public boolean isFounding() {
            return !isYear() && (minVersion.isEmpty() || "0.0.0".equals(minVersion) || "0.0".equals(minVersion));
        }
    }

    /**
     * A board's identity: its category and the era it is read for. {@link #CURRENT} names the
     * relay's current era, which is what every board was before eras existed and what the Stat Room
     * and the rank cache still mean by a bare category.
     */
    public record BoardKey(LeaderboardCategory category, String era) {
        public BoardKey {
            Objects.requireNonNull(category, "category");
            era = era == null ? CURRENT : era;
        }
        public boolean isCurrent() { return era.isEmpty(); }
    }

    /** A fetched board plus when it landed, so staleness is decidable without a second map. */
    public record Board(List<Entry> entries, long fetchedAt) {
        static final Board EMPTY = new Board(List.of(), 0L);
        public boolean isEmpty() { return entries.isEmpty(); }
    }

    /**
     * One player's standing on one board.
     *
     * <p>{@code rank} is 0 when the relay could not give an exact one — it caps how far it will scan,
     * because an exact rank costs more the further down you are and the login call asks for every
     * board at once. In that case {@code beyond} is the horizon it stopped at, and the book says
     * "outside the top N" rather than inventing a number.</p>
     */
    public record Standing(int rank, long score, int beyond) {
        /** True when the relay gave a real position rather than just a horizon. */
        public boolean isExact() { return rank > 0; }
    }

    private static final Map<BoardKey, Board> BOARDS = new ConcurrentHashMap<>();
    private static final Map<BoardKey, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();

    /** The relay's era list, oldest first — empty until the first fetch lands. */
    private static volatile List<Era> ERAS = List.of();
    private static volatile long erasFetchedAt = 0L;
    private static volatile long erasAttemptedAt = 0L;
    private static final AtomicBoolean ERAS_IN_FLIGHT = new AtomicBoolean(false);

    /**
     * When each category was last asked, whatever the answer turned out to be — the brake for every
     * outcome that leaves no {@link Board} behind: empty rows, a {@code bad_cat} 4xx, a transport
     * error. Stamped before dispatch rather than on completion, so a request that never answers
     * cannot spin either. See {@link #EMPTY_ATTEMPT_COOLDOWN_MS}.
     */
    private static final Map<BoardKey, Long> LAST_ATTEMPT_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<BoardKey, Standing>> RANKS = new ConcurrentHashMap<>();

    /**
     * When each player's ranks were last asked for, whatever the answer was — the brake described on
     * {@link #RANK_ATTEMPT_COOLDOWN_MS}. Stamped before dispatch, like {@link #LAST_ATTEMPT_MS}, so a
     * request that never answers cannot open the door for the next one.
     */
    private static final Map<UUID, Long> RANK_ATTEMPT_MS = new ConcurrentHashMap<>();

    /**
     * Set once a leaderboard book actually exists somewhere in the world. Until then this pool makes
     * no requests at all: a server whose loot never rolls one should cost the relay nothing.
     */
    private static volatile boolean wanted = false;

    /** Rotates {@link #warmNext()} through the categories so one tick is one request. */
    private static volatile int warmCursor = 0;

    private LeaderboardPool() {}

    /**
     * Note that a leaderboard book has been rolled into the world, so boards are worth fetching.
     * Called from the loot intercept — at container-load time, which is comfortably before anyone
     * opens the book.
     */
    public static void noteWanted() {
        wanted = true;
    }

    /**
     * Fetch at most ONE board, rotating through the categories. Called on the shared-book refresh
     * tick, so the whole set cycles in about twelve minutes and no tick ever issues more than one
     * request. Does nothing until a book has actually been rolled.
     */
    public static void warmNext() {
        if (!wanted) return;
        refreshEras();
        List<BoardKey> keys = warmKeys();
        BoardKey next = keys.get(Math.floorMod(warmCursor++, keys.size()));
        refresh(next.category(), next.era());
    }

    /**
     * Everything the rotation cycles through: every current board, then — when retired books are
     * wanted — every retired era's one-life boards. A retired era adds eight boards to the cycle,
     * each fetched once per {@link #BOARD_TTL_MS} at most, so the cost grows a little per era and
     * never per tick.
     */
    static List<BoardKey> warmKeys() {
        List<BoardKey> keys = new ArrayList<>();
        for (LeaderboardCategory c : LeaderboardCategory.values()) keys.add(new BoardKey(c, CURRENT));
        if (retiredBooksWanted()) {
            for (String era : circulatingEras()) {
                for (LeaderboardCategory c : eraCategories()) keys.add(new BoardKey(c, era));
            }
        }
        return keys;
    }

    /** The boards eras apply to: the one-life ones. Mirrors the relay's `kind: 'run'` list. */
    static List<LeaderboardCategory> eraCategories() {
        List<LeaderboardCategory> out = new ArrayList<>();
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            if (c.scope() == LeaderboardCategory.Scope.RUN) out.add(c);
        }
        return out;
    }

    /** Whether retired eras may become books at all — a server setting, read defensively. */
    static boolean retiredBooksWanted() {
        try {
            return DungeonTrainConfig.isRetiredLeaderboardBooks();
        } catch (Throwable t) {
            return DungeonTrainConfig.DEFAULT_RETIRED_LEADERBOARD_BOOKS;
        }
    }

    /**
     * The retired eras whose boards go into circulation: every past game version and every past
     * year the relay lists. Ids only, in the relay's order (versions oldest first, then years).
     */
    public static List<String> circulatingEras() {
        List<String> out = new ArrayList<>();
        for (Era e : ERAS) if (e.isRetired()) out.add(e.id());
        return out;
    }

    /** The relay's description of one era, when it has been fetched. */
    public static Optional<Era> era(String id) {
        if (id == null || id.isEmpty()) return Optional.empty();
        for (Era e : ERAS) if (e.id().equals(id)) return Optional.of(e);
        return Optional.empty();
    }

    /** Every era the relay listed, oldest first. Empty until the first fetch lands. */
    public static List<Era> eras() { return ERAS; }

    /**
     * The floor of the version era that FOLLOWED {@code era} — the first version its board no longer
     * covers — or empty when none is known. The relay lists version eras oldest first, so it is
     * simply the next version era in the list.
     */
    public static Optional<String> nextVersionFloor(String era) {
        boolean seen = false;
        for (Era e : ERAS) {
            if (e.isYear()) continue;
            if (seen && !e.minVersion().isEmpty()) return Optional.of(e.minVersion());
            if (e.id().equals(era)) seen = true;
        }
        return Optional.empty();
    }

    /**
     * Fetch the era list if it is missing or stale. Safe to call every tick: held off by
     * {@link #ERA_TTL_MS} once it has landed, by {@link #EMPTY_ATTEMPT_COOLDOWN_MS} after a failure,
     * and by an in-flight guard meanwhile.
     */
    public static void refreshEras() {
        long now = System.currentTimeMillis();
        if (erasFetchedAt > 0 && now - erasFetchedAt < ERA_TTL_MS) return;
        if (now - erasAttemptedAt < EMPTY_ATTEMPT_COOLDOWN_MS) return;
        if (!ERAS_IN_FLIGHT.compareAndSet(false, true)) return;
        erasAttemptedAt = now;
        try {
            String url = DungeonTrain.relayBaseUrl() + "/leaderboard/eras";
            get(url, LeaderboardPool::applyEras, () -> ERAS_IN_FLIGHT.set(false), "leaderboard-eras");
        } catch (Throwable t) {
            ERAS_IN_FLIGHT.set(false);
            LOGGER.debug("[DungeonTrain] leaderboard era fetch failed to start: {}", t.toString());
        }
    }

    /**
     * Ask for every board at once, rather than one per tick.
     *
     * <p>For the one caller that needs the whole set and not a sample: a stat room, whose shelves are
     * meant to hold every board there is. {@link #warmNext}'s rotation gets there eventually — about
     * twelve minutes — which is fine for loot that only ever shows one board at a time and much too
     * slow for a room a player is standing in.</p>
     *
     * <p>Cheap to call repeatedly: {@link #refresh} returns immediately for a board that is fresh or
     * already in flight, so a room asking again every tick issues requests only for what has actually
     * gone stale.</p>
     */
    public static void warmAll() {
        if (!wanted) return;
        refreshEras();
        for (LeaderboardCategory category : LeaderboardCategory.values()) {
            refresh(category);
        }
    }

    /** The cached CURRENT board for {@code category} — empty until a fetch succeeds. Never null. */
    public static Board board(LeaderboardCategory category) {
        return board(category, CURRENT);
    }

    /** The cached board for {@code category} in {@code era} ({@link #CURRENT} for the live one). Never null. */
    public static Board board(LeaderboardCategory category, String era) {
        return BOARDS.getOrDefault(new BoardKey(category, era), Board.EMPTY);
    }

    /** Current-era categories with rows to show. A Stat Room shelf can only be about one of these. */
    public static List<LeaderboardCategory> populated() {
        List<LeaderboardCategory> out = new ArrayList<>();
        for (Map.Entry<BoardKey, Board> e : BOARDS.entrySet()) {
            if (e.getKey().isCurrent() && !e.getValue().isEmpty()) out.add(e.getKey().category());
        }
        out.sort(java.util.Comparator.comparing(LeaderboardCategory::id)); // stable order for seeded picks
        return out;
    }

    /**
     * Retired-era boards with rows to show, in a stable order — the pool a retired-board book is
     * rolled from. Only eras the relay still lists as retired count: a board cached for an era that
     * has since vanished from the list is not offered.
     */
    public static List<BoardKey> populatedRetired() {
        List<String> eras = circulatingEras();
        List<BoardKey> out = new ArrayList<>();
        for (Map.Entry<BoardKey, Board> e : BOARDS.entrySet()) {
            BoardKey k = e.getKey();
            if (!k.isCurrent() && !e.getValue().isEmpty() && eras.contains(k.era())) out.add(k);
        }
        out.sort(java.util.Comparator.comparing((BoardKey k) -> k.category().id()).thenComparing(BoardKey::era));
        return out;
    }

    /** This player's standing on the CURRENT board of {@code category}, if the login fetch found one. */
    public static Optional<Standing> standing(UUID player, LeaderboardCategory category) {
        return standing(player, category, CURRENT);
    }

    /** This player's standing on {@code category} in {@code era}, if the login fetch found one. */
    public static Optional<Standing> standing(UUID player, LeaderboardCategory category, String era) {
        Map<BoardKey, Standing> mine = RANKS.get(player);
        return Optional.ofNullable(mine == null ? null : mine.get(new BoardKey(category, era)));
    }

    /** Drop a player's cached ranks — call on logout so the map doesn't grow with the session. */
    public static void forget(UUID player) {
        RANKS.remove(player);
        RANK_ATTEMPT_MS.remove(player);
    }

    /**
     * Whether a category is worth asking the relay about right now — over plain values, so the two
     * brakes can be tested without a relay.
     *
     * <p>Two separate throttles, because they cover different outcomes. A board that produced rows
     * carries its own {@code fetchedAt} and is good for {@link #BOARD_TTL_MS}. Everything else —
     * empty rows, {@code bad_cat}, a transport error — leaves no board at all, and is held off by
     * {@code lastAttempt} for {@link #EMPTY_ATTEMPT_COOLDOWN_MS} instead. Without the second one, a
     * category the relay cannot serve was asked again on every single call.</p>
     *
     * @param cached      the board held for this category, or null if none has ever landed
     * @param lastAttempt when it was last asked, or null if it never has been
     */
    static boolean dueForRequest(Board cached, Long lastAttempt, long now) {
        if (cached != null && now - cached.fetchedAt() < BOARD_TTL_MS) return false;
        return lastAttempt == null || now - lastAttempt >= EMPTY_ATTEMPT_COOLDOWN_MS;
    }

    /**
     * Fetch {@code category}'s board if it is missing or stale. Safe to call every tick: a fresh
     * board, one asked for too recently, and an in-flight one all return without a request.
     */
    public static void refresh(LeaderboardCategory category) {
        refresh(category, CURRENT);
    }

    /** As {@link #refresh(LeaderboardCategory)}, for one era's board ({@link #CURRENT} for the live one). */
    public static void refresh(LeaderboardCategory category, String era) {
        if (category == null) return;
        BoardKey key = new BoardKey(category, era);
        long now = System.currentTimeMillis();
        if (!dueForRequest(BOARDS.get(key), LAST_ATTEMPT_MS.get(key), now)) return;
        if (Boolean.TRUE.equals(IN_FLIGHT.putIfAbsent(key, Boolean.TRUE))) return;
        LAST_ATTEMPT_MS.put(key, now);
        try {
            String url = DungeonTrain.relayBaseUrl() + "/leaderboard?cat=" + enc(category.id())
                    + "&limit=" + FETCH_LIMIT
                    + (key.isCurrent() ? "" : "&era=" + enc(key.era()));
            get(url, body -> applyBoard(key, body), () -> IN_FLIGHT.remove(key),
                "leaderboard[" + category.id() + (key.isCurrent() ? "" : "@" + key.era()) + "]");
        } catch (Throwable t) {
            IN_FLIGHT.remove(key);
            LOGGER.debug("[DungeonTrain] leaderboard refresh failed to start: {}", t.toString());
        }
    }

    /**
     * Whether this player's ranks are worth asking the relay for right now — over plain values, so
     * the cooldown can be tested without a relay. See {@link #RANK_ATTEMPT_COOLDOWN_MS}.
     *
     * @param lastAttempt when they were last asked for, or null if they never have been
     */
    static boolean dueForRankRequest(Long lastAttempt, long now) {
        return rankRequestWaitMs(lastAttempt, now) == 0L;
    }

    /**
     * How long until this player may be asked about again — 0 when they may be asked now.
     *
     * <p>Callers that are acting on a DEATH use this rather than the boolean, so a refetch inside the
     * cooldown is deferred to the moment it expires instead of dropped. Dropping it loses the death
     * outright: a player who dies half a minute after joining would carry their pre-death standing
     * until some later death happened to fall outside a window. Rate-limited is not the same as
     * discarded.</p>
     */
    static long rankRequestWaitMs(Long lastAttempt, long now) {
        if (lastAttempt == null) return 0L;
        long elapsed = now - lastAttempt;
        return elapsed >= RANK_ATTEMPT_COOLDOWN_MS ? 0L : RANK_ATTEMPT_COOLDOWN_MS - elapsed;
    }

    /** As {@link #rankRequestWaitMs(Long, long)}, for the live cooldown of one player. */
    public static long rankRefreshWaitMs(UUID player, long now) {
        return player == null ? 0L : rankRequestWaitMs(RANK_ATTEMPT_MS.get(player), now);
    }

    /**
     * Fetch one player's standings across every board. Called at login and again a few seconds after
     * each of their deaths; the result is what the closing line of every leaderboard book they find is
     * written from. Held off by {@link #RANK_ATTEMPT_COOLDOWN_MS} when asked again too soon.
     */
    public static void refreshRanks(UUID player, String name) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        if (!dueForRankRequest(RANK_ATTEMPT_MS.get(player), now)) return;
        RANK_ATTEMPT_MS.put(player, now);
        try {
            String url = DungeonTrain.relayBaseUrl() + "/leaderboard/me?uuid=" + enc(player.toString())
                    + (name == null || name.isBlank() ? "" : "&name=" + enc(name));
            get(url, body -> applyRanks(player, body), () -> {}, "leaderboard-ranks");
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] leaderboard rank fetch failed to start: {}", t.toString());
        }
    }

    // ---- transport ----------------------------------------------------------

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void get(String url, java.util.function.Consumer<String> onBody, Runnable always, String what) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    try {
                        if (err != null) {
                            LOGGER.debug("[DungeonTrain] {} fetch failed: {}", what, err.toString());
                            return;
                        }
                        if (resp.statusCode() / 100 != 2) {
                            LOGGER.debug("[DungeonTrain] {} fetch -> HTTP {}", what, resp.statusCode());
                            return;
                        }
                        onBody.accept(resp.body());
                    } catch (Throwable t) {
                        LOGGER.debug("[DungeonTrain] {} parse failed: {}", what, t.toString());
                    } finally {
                        always.run();
                    }
                });
    }

    // ---- parsing (package-private: pure, and tested without a relay) ---------

    /** Publish a fetched CURRENT board. A malformed or empty body keeps the previous one. */
    static void applyBoard(LeaderboardCategory category, String body) {
        applyBoard(new BoardKey(category, CURRENT), body);
    }

    /** Publish a fetched board for one era. A malformed or empty body keeps the previous one. */
    static void applyBoard(BoardKey key, String body) {
        List<Entry> parsed = parseRows(body);
        if (parsed.isEmpty()) return;
        BOARDS.put(key, new Board(List.copyOf(parsed), System.currentTimeMillis()));
    }

    /** Publish a fetched era list. A malformed or empty body keeps the previous one. */
    static void applyEras(String body) {
        List<Era> parsed = parseEras(body);
        if (parsed.isEmpty()) return;
        ERAS = List.copyOf(parsed);
        erasFetchedAt = System.currentTimeMillis();
        LOGGER.debug("[DungeonTrain] leaderboard eras refreshed: {} ({} retired)", parsed.size(),
            parsed.stream().filter(Era::isRetired).count());
    }

    /**
     * The relay's {@code /leaderboard/eras} answer: {@code eras: [{id, kind, label, current}]}. An
     * era with no usable id or kind is skipped; the label falls back to the id; at most one era is
     * current per kind — the relay guarantees it, and this does not repair a relay that lies.
     */
    static List<Era> parseEras(String body) {
        List<Era> out = new ArrayList<>();
        JsonObject root = asObject(body);
        if (root == null || !root.has("eras") || !root.get("eras").isJsonArray()) return out;
        for (JsonElement el : root.getAsJsonArray("eras")) {
            if (out.size() >= MAX_ERAS) break;
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id");
            String kind = str(o, "kind");
            if (id.isEmpty() || !id.matches("[A-Za-z0-9._-]{1,32}")) continue;
            if (!"version".equals(kind) && !"year".equals(kind)) continue;
            String label = str(o, "label");
            if (label.length() > MAX_ERA_LABEL_LEN) label = label.substring(0, MAX_ERA_LABEL_LEN);
            // The lowest Dungeon Train version in a version era — what the book names it by.
            String minVersion = str(o, "minVersion");
            if (!minVersion.matches("\\d+(\\.\\d+){0,3}")) minVersion = "";
            boolean current = o.has("current") && o.get("current").isJsonPrimitive()
                && o.get("current").getAsJsonPrimitive().isBoolean() && o.get("current").getAsBoolean();
            out.add(new Era(id, kind, label.isEmpty() ? id : label, minVersion, current));
        }
        return out;
    }

    static List<Entry> parseRows(String body) {
        List<Entry> out = new ArrayList<>();
        JsonObject root = asObject(body);
        if (root == null || !root.has("rows") || !root.get("rows").isJsonArray()) return out;
        JsonArray rows = root.getAsJsonArray("rows");
        for (JsonElement el : rows) {
            if (out.size() >= MAX_ROWS) break;
            if (!el.isJsonObject()) continue;
            JsonObject row = el.getAsJsonObject();
            String name = str(row, "name");
            if (name.isEmpty()) continue;
            long score = num(row, "score");
            if (score <= 0) continue;
            out.add(new Entry(name, score));
        }
        return out;
    }

    /** Publish one player's standings. A malformed body leaves whatever was already known. */
    static void applyRanks(UUID player, String body) {
        Map<BoardKey, Standing> parsed = parseAllRanks(body);
        if (parsed.isEmpty()) return;
        RANKS.put(player, Map.copyOf(parsed));
        // The one line that says a refresh actually landed. Every other outcome here is already
        // logged (a failed fetch, a bad status), so without this a rank refresh — the login one and
        // the post-death one alike — is invisible even at debug, and "did it fire?" is unanswerable.
        LOGGER.debug("[DungeonTrain] leaderboard ranks refreshed for {}: {} boards", player, parsed.size());
    }

    /**
     * Every standing in a {@code /leaderboard/me} answer: {@code ranks} (the current era of every
     * board) keyed {@link #CURRENT}, plus {@code eras: {eraId: {cat: …}}} keyed by era.
     */
    static Map<BoardKey, Standing> parseAllRanks(String body) {
        Map<BoardKey, Standing> out = new java.util.HashMap<>();
        for (Map.Entry<LeaderboardCategory, Standing> e : parseRanks(body).entrySet()) {
            out.put(new BoardKey(e.getKey(), CURRENT), e.getValue());
        }
        JsonObject root = asObject(body);
        if (root == null || !root.has("eras") || !root.get("eras").isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> era : root.getAsJsonObject("eras").entrySet()) {
            String id = era.getKey();
            if (id.isEmpty() || !id.matches("[A-Za-z0-9._-]{1,32}") || !era.getValue().isJsonObject()) continue;
            for (Map.Entry<LeaderboardCategory, Standing> e : parseRankMap(era.getValue().getAsJsonObject()).entrySet()) {
                out.put(new BoardKey(e.getKey(), id), e.getValue());
            }
        }
        return out;
    }

    static Map<LeaderboardCategory, Standing> parseRanks(String body) {
        JsonObject root = asObject(body);
        if (root == null || !root.has("ranks") || !root.get("ranks").isJsonObject()) {
            return new EnumMap<>(LeaderboardCategory.class);
        }
        return parseRankMap(root.getAsJsonObject("ranks"));
    }

    private static Map<LeaderboardCategory, Standing> parseRankMap(JsonObject ranks) {
        Map<LeaderboardCategory, Standing> out = new EnumMap<>(LeaderboardCategory.class);
        for (Map.Entry<String, JsonElement> e : ranks.entrySet()) {
            LeaderboardCategory cat = LeaderboardCategory.byId(e.getKey()).orElse(null);
            if (cat == null || !e.getValue().isJsonObject()) continue; // a board this jar predates
            JsonObject o = e.getValue().getAsJsonObject();
            int rank = (int) Math.min(Integer.MAX_VALUE, num(o, "rank"));
            long score = num(o, "score");
            int beyond = (int) Math.min(Integer.MAX_VALUE, num(o, "beyond"));
            // Either an exact position, or a score with a horizon. Neither one means unranked.
            if (rank > 0 || beyond > 0) out.put(cat, new Standing(rank, score, beyond));
        }
        return out;
    }

    private static JsonObject asObject(String body) {
        try {
            JsonElement el = JsonParser.parseString(body == null ? "" : body);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        try {
            if (!o.has(key) || !o.get(key).isJsonPrimitive()) return "";
            String s = o.get(key).getAsString().replace('\n', ' ').trim();
            return s.length() > MAX_NAME_LEN ? s.substring(0, MAX_NAME_LEN) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static long num(JsonObject o, String key) {
        try {
            return !o.has(key) || !o.get(key).isJsonPrimitive() ? 0L : o.get(key).getAsLong();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Test seam — drop every cached board and rank. */
    static void clear() {
        BOARDS.clear();
        IN_FLIGHT.clear();
        LAST_ATTEMPT_MS.clear();
        RANKS.clear();
        RANK_ATTEMPT_MS.clear();
        ERAS = List.of();
        erasFetchedAt = 0L;
        erasAttemptedAt = 0L;
        ERAS_IN_FLIGHT.set(false);
        wanted = false;
        warmCursor = 0;
    }
}
