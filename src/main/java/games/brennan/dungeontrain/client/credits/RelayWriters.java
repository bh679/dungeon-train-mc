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

/**
 * The community's most prolific book writers — the Credits page's "Writers" card.
 *
 * <p>Read off the relay's <b>Most Praised Writers</b> board ({@code /leaderboard?cat=books_praised},
 * the live pool): an author's shared books whose up-votes beat down-votes better than ten to one
 * — materialised by the relay's sweep, uuid-free, consent-gated on its side. Only writers with
 * {@link #MIN_BOOKS} or more such books are thanked here, in the collapsed list and the expanded one
 * alike: the card is a thank-you for a body of well-received work, not a second copy of a board.</p>
 *
 * <p>Nothing bundled to merge with — a book is written on the relay, never in the jar — so this is
 * the relay's list alone, cached to disk for an offline launch. Fetched each time the Credits page
 * opens; never throws, never blocks, never retries.</p>
 */
public final class RelayWriters {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** The relay caps a board read at 100 rows — more than enough past the bar below. */
    static final String PATH = "/leaderboard?cat=books_praised&limit=100";
    /** A writer needs at least this many praised books to be thanked — the card's footer says so. */
    public static final int MIN_BOOKS = 20;
    /** The relay's stand-in for a writer with no name — not a person to thank. */
    static final String ANONYMOUS = "Anonymous";
    static final String SUBDIR = "credits";
    static final String FILE = "writers.json";

    /** One writer and how many books the relay counts for them. */
    public record Writer(String name, int books) {
        public Writer {
            name = name == null ? "" : name.trim();
            books = Math.max(0, books);
        }
    }

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static List<Writer> writers;

    private RelayWriters() {}

    /** Writers past {@link #MIN_BOOKS}, most books first — this run's answer, else the disk cache. */
    public static synchronized List<Writer> current() {
        if (writers == null) writers = load();
        return writers;
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
                        LOGGER.debug("[DungeonTrain] Credits: relay writers unavailable — {}",
                            err != null ? err.toString() : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                        return;
                    }
                    if (apply(parse(resp.body())) && onUpdate != null) onUpdate.run();
                });
        } catch (Throwable t) {
            IN_FLIGHT.set(false);
            LOGGER.debug("[DungeonTrain] Credits: relay writers fetch failed — {}", t.toString());
        }
    }

    static synchronized boolean apply(List<Writer> fresh) {
        if (fresh.equals(current())) return false;
        writers = List.copyOf(fresh);
        save(writers);
        return true;
    }

    /**
     * {@code {"rows":[{"name","score"}]}} (the board's shape) to writers past the bar, in the
     * board's order. Anonymous rows and a malformed body are dropped.
     */
    static List<Writer> parse(String body) {
        List<Writer> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);
            if (!root.isJsonObject()) return out;
            JsonElement arr = root.getAsJsonObject().get("rows");
            if (arr == null || !arr.isJsonArray()) return out;
            for (JsonElement el : arr.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Writer w = new Writer(str(o.get("name")), num(o.get("score")));
                if (!w.name().isEmpty() && !ANONYMOUS.equals(w.name()) && w.books() >= MIN_BOOKS) out.add(w);
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

    // ---- disk cache: the board's own shape, so load() is parse() ------------------

    private static Path file() {
        return PlayerDataPaths.dir(SUBDIR).resolve(FILE);
    }

    private static List<Writer> load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return List.of();
        try {
            return List.copyOf(parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Credits: could not read {} — {}", path, e.toString());
            return List.of();
        }
    }

    private static void save(List<Writer> list) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonArray rows = new JsonArray();
            for (Writer w : list) {
                JsonObject o = new JsonObject();
                o.addProperty("name", w.name());
                o.addProperty("score", w.books());
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
        writers = null;
    }
}
