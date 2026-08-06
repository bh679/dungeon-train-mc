package games.brennan.dungeontrain.narrative;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side, per-player cache of the AUTHOR's LANDED Death Note curses whose story they have not
 * read yet — the mirror image of {@link DeathNotePool}. Where that pool asks "who is coming for
 * <em>me</em>?", this one asks "what became of the curses <em>I</em> wrote?", and its answers become
 * the cursed welcome book ({@code CursedBookFactory}).
 *
 * <p>Fetched from the relay's {@code /deathnotes/landed} endpoint, which only returns a curse once it
 * has <em>settled</em> — the target's game reported an outcome, or the relay's grace window elapsed
 * with none — so a story is never told before its ending. Same transport shape as its sibling: the
 * fetch is fire-and-forget off-thread and swaps in a fresh immutable list per player; reads never
 * block or touch the network.</p>
 *
 * <p><b>One story per curse.</b> A story leaves the pool when the author <em>reads</em> its book
 * ({@link #consume}); an unread book simply burns and the story is offered again at the next welcome
 * strike. The read is latched twice — locally in {@link PlayerPlayedMarker} (per-installation, so it
 * survives the fresh-seed world every death creates) and on the relay — and {@link #peek} honours the
 * local latch, so a slow or failed relay can never re-tell a story that was already read.</p>
 */
public final class CursedStoryPool {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max stories requested per fetch — an author rarely has many curses land at once. */
    static final int POOL_LIMIT = 20;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // relay is HTTP/1.1 (matches DeathNotePool / SharedBookPool)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * One landed curse of the local player's, ready to be told.
     *
     * @param id            the relay note id — the story's identity, stamped on the book
     * @param targetName    the name the author wrote on page one
     * @param deathCarriage the carriage the author died at, where their echo lay in wait
     * @param signedTs      epoch millis the curse was uploaded (the author's death)
     * @param landedTs      epoch millis the echo spawned in the target's world, or 0 if unknown
     * @param outcome       {@code "echo_killed_target"} / {@code "target_killed_echo"}, or "" when the
     *                      ending was never reported — the story then says as much
     */
    public record Story(int id, String targetName, int deathCarriage,
                        long signedTs, long landedTs, String outcome, Encounter encounter) {}

    /**
     * The journal of the fight itself, as recorded by the target's game and stored on the relay (see
     * {@code CursedEncounterPayload}) — {@code null} on a curse that has no journal: an older relay, a
     * target whose game never opened one, or an echo whose encounter never finished.
     *
     * @param beats    ordered beat names ({@code MET}, {@code PLAYER_STRUCK_ECHO}, …)
     * @param items    descriptors of the gear the echo carried when it arrived
     * @param acquired descriptors of the gear it took during the encounter
     * @param end      the terminal reason ({@code ECHO_SLAIN_BY_YOU}, {@code LEFT_BEHIND}, …)
     * @param seconds  how long the encounter lasted
     */
    public record Encounter(List<String> beats, List<String> items, List<String> acquired,
                            String end, long seconds) {
        public Encounter {
            beats = List.copyOf(beats);
            items = List.copyOf(items);
            acquired = List.copyOf(acquired);
        }
    }

    /** authorUuid → their current immutable snapshot of untold stories, oldest first. */
    private static final Map<UUID, List<Story>> STORIES = new ConcurrentHashMap<>();
    /** Per-player in-flight guard so overlapping refreshes don't stack. */
    private static final Map<UUID, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();

    private CursedStoryPool() {}

    /**
     * The oldest story {@code authorUuid} is owed and has not read, or empty. Filters against the
     * local read latch as well as the snapshot, so a story read moments ago is never re-offered while
     * the relay catches up.
     */
    public static Optional<Story> peek(UUID authorUuid) {
        List<Story> stories = STORIES.get(authorUuid);
        if (stories == null || stories.isEmpty()) return Optional.empty();
        Set<String> read = PlayerPlayedMarker.readCursedStories(authorUuid);
        for (Story s : stories) {
            if (!read.contains(Integer.toString(s.id()))) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** True when {@code authorUuid} has a story waiting — the cheap gate for the delivery scan. */
    public static boolean hasPending(UUID authorUuid) {
        return peek(authorUuid).isPresent();
    }

    /** Drop story {@code id} from a player's snapshot once its book has been read. */
    public static void consume(UUID authorUuid, int id) {
        STORIES.computeIfPresent(authorUuid, (k, list) -> {
            List<Story> next = new ArrayList<>(list.size());
            for (Story s : list) if (s.id() != id) next.add(s);
            return List.copyOf(next);
        });
    }

    /** Forget a player's cache (on logout). */
    public static void forget(UUID authorUuid) {
        STORIES.remove(authorUuid);
        IN_FLIGHT.remove(authorUuid);
    }

    /**
     * Fetch {@code authorUuid}'s settled, untold curses off-thread and swap in the new snapshot.
     * No-throw; a failed / slow fetch leaves the existing snapshot in place. Skips if a fetch for this
     * player is already in flight.
     */
    public static void refreshForPlayer(UUID authorUuid) {
        if (authorUuid == null) return;
        if (IN_FLIGHT.putIfAbsent(authorUuid, Boolean.TRUE) != null) return;
        try {
            String url = DungeonTrain.relayBaseUrl()
                    + "/deathnotes/landed?author=" + authorUuid.toString().replace("-", "")
                    + "&limit=" + POOL_LIMIT;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> {
                        try {
                            if (err != null) {
                                LOGGER.debug("[DungeonTrain] cursed-story fetch failed: {}", err.toString());
                                return;
                            }
                            if (resp.statusCode() / 100 != 2) {
                                // 404 here just means the relay predates the story loop — no story, no noise.
                                LOGGER.debug("[DungeonTrain] cursed-story fetch -> HTTP {}", resp.statusCode());
                                return;
                            }
                            applyResponse(authorUuid, resp.body());
                        } catch (Throwable t) {
                            LOGGER.debug("[DungeonTrain] cursed-story parse failed: {}", t.toString());
                        } finally {
                            IN_FLIGHT.remove(authorUuid);
                        }
                    });
        } catch (Throwable t) {
            IN_FLIGHT.remove(authorUuid);
            LOGGER.debug("[DungeonTrain] cursed-story refresh failed to start: {}", t.toString());
        }
    }

    /** Parse the relay JSON body and publish {@code authorUuid}'s new immutable snapshot. */
    static void applyResponse(UUID authorUuid, String body) {
        List<Story> parsed = parseStories(body);
        if (parsed == null) return;                        // malformed → keep the last snapshot
        STORIES.put(authorUuid, List.copyOf(parsed));
        if (!parsed.isEmpty()) {
            LOGGER.debug("[DungeonTrain] cursed-story pool for {}: {} untold story(ies)",
                    authorUuid, parsed.size());
        }
    }

    /**
     * Pure parse of a {@code /deathnotes/landed} body into stories, oldest first (the relay already
     * orders by id). Returns {@code null} when the body is not a successful, well-formed response —
     * callers keep their previous snapshot rather than blanking it.
     */
    static List<Story> parseStories(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) return null;
        if (!obj.has("notes") || !obj.get("notes").isJsonArray()) return null;
        List<Story> out = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray("notes")) {
            if (!el.isJsonObject()) continue;
            Story s = parseStory(el.getAsJsonObject());
            if (s != null) out.add(s);
        }
        return out;
    }

    /** Materialise one story; {@code null} if it lacks the fields the book is written from. */
    private static Story parseStory(JsonObject o) {
        if (!o.has("id") || !o.has("deathCarriage")) return null;
        try {
            int id = o.get("id").getAsInt();
            if (id <= 0) return null;
            return new Story(id, str(o, "targetName"), o.get("deathCarriage").getAsInt(),
                    num(o, "ts"), num(o, "usedTs"), str(o, "outcome"), parseEncounter(o));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The encounter journal attached to a note, or {@code null} when it has none — an older relay, or
     * a curse whose fight was never journaled. A malformed journal is treated as absent rather than
     * failing the whole story: the book falls back to the bare facts.
     */
    static Encounter parseEncounter(JsonObject o) {
        if (!o.has("encounter") || !o.get("encounter").isJsonObject()) return null;
        JsonObject e = o.getAsJsonObject("encounter");
        try {
            return new Encounter(strings(e, "beats"), strings(e, "items"), strings(e, "acquired"),
                    str(e, "end"), num(e, "sec"));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@code k}'s array of strings, or an empty list when absent / malformed. Non-strings are skipped. */
    private static List<String> strings(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray(k)) {
            if (el.isJsonPrimitive()) out.add(el.getAsString());
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /** {@code k}'s epoch-millis value, or 0 when absent / null (an unknown timestamp). */
    private static long num(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
    }
}
