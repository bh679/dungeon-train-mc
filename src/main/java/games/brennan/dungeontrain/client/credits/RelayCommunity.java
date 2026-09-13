package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The Discord community's Value Adders — the Credits page's "Community" card.
 *
 * <p>Read off the relay's {@code /community} list: every member of the Dungeon Train Discord who
 * holds the <b>Value Adders</b> role, ranked by their MEE6 level (the relay polls MEE6 and Discord
 * hourly; see dp-relay's {@code community.js}). Rows carry a Discord display name and a level,
 * nothing else — no Discord ids, no uuids. Where this player stands comes from the {@code ?uuid=}
 * form ({@link #fetchStanding}), which the relay can only answer once the player has tied their
 * Discord account to their uuid with {@code /dtlink} ({@link DiscordLinkScreen}); consent-gated by
 * the caller and matched back to a row by {@code rank} as the Writers card does.</p>
 *
 * <p>Relay-only, cached to disk for an offline launch. Fetched each time the Credits page opens;
 * never throws, never blocks, never retries. Sibling of {@link RelayWriters}.</p>
 */
public final class RelayCommunity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final String PATH = "/community";
    static final String SUBDIR = "credits";
    static final String FILE = "community.json";

    /**
     * One member: Discord display name and MEE6 level. {@code anonymous} is a member who took their
     * name off the credits — thanked by level, unnamed. {@code rank} is the 1-based position on the
     * relay's list, kept through drops and in the cache so a {@link Standing} matches its row.
     */
    public record Member(String name, int level, boolean anonymous, int rank) {
        public Member {
            name = name == null ? "" : name.trim();
            level = Math.max(0, level);
            rank = Math.max(0, rank);
        }

        public Member(String name, int level) {
            this(name, level, false, 0);
        }
    }

    /** Where the asking player stands on the list, or {@code null} when not on it / unlinked / unknown. */
    public record Standing(int rank) {}

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static List<Member> members;

    private RelayCommunity() {}

    /** Members in the relay's order (highest level first) — this run's answer, else the disk cache. */
    public static synchronized List<Member> current() {
        if (members == null) members = load();
        return members;
    }

    /** Ask the relay again (one in flight at a time); {@code onUpdate} runs off-thread when the list changed. */
    public static void refresh(Runnable onUpdate) {
        if (IN_FLIGHT.compareAndSet(false, true)) fetchAsync(onUpdate);
    }

    private static void fetchAsync(Runnable onUpdate) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + PATH))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    IN_FLIGHT.set(false);
                    if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                        LOGGER.debug("[DungeonTrain] Credits: relay community unavailable — {}",
                            err != null ? err.toString() : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                        return;
                    }
                    if (apply(parse(resp.body())) && onUpdate != null) onUpdate.run();
                });
        } catch (Throwable t) {
            IN_FLIGHT.set(false);
            LOGGER.debug("[DungeonTrain] Credits: relay community fetch failed — {}", t.toString());
        }
    }

    static synchronized boolean apply(List<Member> fresh) {
        if (fresh.equals(current())) return false;
        members = List.copyOf(fresh);
        save(members);
        return true;
    }

    /**
     * Ask the relay where {@code uuid} stands ({@code /community?uuid=} → {@code you.rank}).
     * {@code onResult} runs off-thread with the standing, or {@code null} when not on the list
     * (which includes "not linked yet") or the relay did not answer. Consent-gated by the caller.
     */
    public static void fetchStanding(String uuid, Consumer<Standing> onResult) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + PATH + "?uuid=" + uuid))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                        onResult.accept(null);
                        return;
                    }
                    apply(parse(resp.body()));
                    onResult.accept(parseStanding(resp.body()));
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Credits: relay community standing fetch failed — {}", t.toString());
            onResult.accept(null);
        }
    }

    /** The {@code you} block of a list answer, or {@code null}. */
    static Standing parseStanding(String body) {
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);
            if (!root.isJsonObject()) return null;
            JsonElement you = root.getAsJsonObject().get("you");
            if (you == null || !you.isJsonObject()) return null;
            int rank = num(you.getAsJsonObject().get("rank"));
            return rank > 0 ? new Standing(rank) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code {"rows":[{"name","level","anonymous"?}]}} to members in the relay's order. A row flagged
     * {@code anonymous} is kept unnamed; a nameless row without the flag is nobody and is dropped, as
     * is a malformed body. Each member keeps its 1-based position ({@code rank}).
     */
    static List<Member> parse(String body) {
        List<Member> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);
            if (!root.isJsonObject()) return out;
            JsonElement arr = root.getAsJsonObject().get("rows");
            if (arr == null || !arr.isJsonArray()) return out;
            int position = 0;
            for (JsonElement el : arr.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                position++;
                boolean anonymous = o.has("anonymous") && o.get("anonymous").isJsonPrimitive() && o.get("anonymous").getAsBoolean();
                int rank = o.has("rank") ? num(o.get("rank")) : position;
                Member m = new Member(anonymous ? "" : str(o.get("name")), num(o.get("level")), anonymous, rank);
                if (anonymous || !m.name().isEmpty()) out.add(m);
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    private static String str(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : "";
    }

    private static int num(JsonElement el) {
        try {
            return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- disk cache: the wire's own shape, so load() is parse() ------------------

    private static Path file() {
        return PlayerDataPaths.dir(SUBDIR).resolve(FILE);
    }

    private static List<Member> load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return List.of();
        try {
            return List.copyOf(parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Credits: could not read {} — {}", path, e.toString());
            return List.of();
        }
    }

    private static void save(List<Member> list) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonArray rows = new JsonArray();
            for (Member m : list) {
                JsonObject o = new JsonObject();
                o.addProperty("name", m.name());
                o.addProperty("level", m.level());
                if (m.anonymous()) o.addProperty("anonymous", true);
                o.addProperty("rank", m.rank());
                rows.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("rows", rows);
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Credits: could not write {} — {}", path, e.toString());
        }
    }

    /** Test seam. */
    static synchronized void reset() {
        members = null;
    }
}
