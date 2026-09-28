package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.template.BuilderCredit;
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
 * The builders the relay credits for shipped templates — {@code GET /templates/builders} — beside
 * the ones the jar already names.
 *
 * <p>The jar's {@code weights.json} can only credit people who were credited when the build was
 * cut. A credit set in the editor after a release is on the relay the same minute and would
 * otherwise go unthanked until the next update — so the relay's list rides along and the Credits
 * page merges it onto the bundled one ({@link TemplateBuilderCredits#merged()}).</p>
 *
 * <p>Always the live pool ({@link RelayTarget#live()}) — that is where credits are mirrored, on
 * every branch. Anonymous and UNGATED, like {@code /translations/coverage}: it carries no uuid and asks nothing
 * about this player. Cached to disk so an offline launch still thanks everybody it last saw. Fetched
 * each time the Credits page opens; never throws, never blocks, never retries.</p>
 */
public final class RelayTemplateBuilders {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final String PATH = "/templates/builders";
    /** A guard against a hostile or corrupt answer, not a real limit — a few dozen people at most. */
    static final int MAX_ROWS = 500;
    static final String SUBDIR = "credits";
    static final String FILE = "template-builders.json";

    /**
     * One relay row: a person and how many shipped templates the relay credits them with.
     * {@code anonymous} is a builder who took their name off the credits ({@code CreditEditClient}):
     * the uuid is still on the wire (so the page can find the player's own row) but no name is.
     */
    public record Row(String uuid, String name, int templates, boolean anonymous) {
        public Row {
            uuid = BuilderCredit.normaliseUuid(uuid);
            name = anonymous ? "" : BuilderCredit.normaliseName(name);
            templates = Math.max(0, templates);
        }

        public Row(String uuid, String name, int templates) {
            this(uuid, name, templates, false);
        }

        public boolean known() {
            return !uuid.isEmpty() || !name.isEmpty();
        }
    }

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static List<Row> rows;

    private RelayTemplateBuilders() {}

    /** What the relay last said — this run's answer, else the disk cache, else nothing. Never null. */
    public static synchronized List<Row> current() {
        if (rows == null) rows = load();
        return rows;
    }

    /**
     * Ask the relay again — once per opening of the Credits page, never two in flight at once;
     * {@code onUpdate} runs (off-thread) when a fresh answer differs from what was cached. A
     * credit set in the editor a minute ago is on the page the next time it is opened.
     */
    public static void refresh(Runnable onUpdate) {
        if (IN_FLIGHT.compareAndSet(false, true)) fetchAsync(onUpdate);
    }

    private static void fetchAsync(Runnable onUpdate) {
        try {
            // The LIVE pool whatever branch this is: credits are mirrored there (TemplateCreditClient),
            // and they are facts about shipped content, not about the relay the editor happens to use.
            HttpRequest req = HttpRequest.newBuilder(URI.create(RelayTarget.live() + PATH))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    IN_FLIGHT.set(false);
                    if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                        // Includes a relay older than the endpoint. Debug only: the page falls back
                        // to the cached + bundled lists, which is where it was before.
                        LOGGER.debug("[DungeonTrain] Credits: relay builders unavailable — {}",
                            err != null ? err.toString() : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                        return;
                    }
                    if (apply(parse(resp.body())) && onUpdate != null) onUpdate.run();
                });
        } catch (Throwable t) {
            IN_FLIGHT.set(false);
            LOGGER.debug("[DungeonTrain] Credits: relay builders fetch failed — {}", t.toString());
        }
    }

    /** Replace the list and persist; true when it changed. */
    static synchronized boolean apply(List<Row> fresh) {
        List<Row> prev = current();
        if (fresh.equals(prev)) return false;
        rows = List.copyOf(fresh);
        save(rows);
        return true;
    }

    /** {@code {"builders":[{"uuid","name","templates"}]}} to rows; a malformed body is an empty list. */
    static List<Row> parse(String body) {
        List<Row> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);
            if (!root.isJsonObject()) return out;
            JsonElement arr = root.getAsJsonObject().get("builders");
            if (arr == null || !arr.isJsonArray()) return out;
            for (JsonElement el : arr.getAsJsonArray()) {
                if (out.size() >= MAX_ROWS) break;
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                boolean anonymous = o.has("anonymous") && o.get("anonymous").isJsonPrimitive() && o.get("anonymous").getAsBoolean();
                Row row = new Row(str(o.get("uuid")), str(o.get("name")), num(o.get("templates")), anonymous);
                if (row.known() && row.templates() > 0) out.add(row);
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

    // ---- disk cache ----------------------------------------------------------

    private static Path file() {
        return PlayerDataPaths.dir(SUBDIR).resolve(FILE);
    }

    private static List<Row> load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return List.of();
        try {
            return List.copyOf(parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // No relay credits is a worse outcome than stale ones, but neither is worth a crash.
            LOGGER.warn("[DungeonTrain] Credits: could not read {} — {}", path, e.toString());
            return List.of();
        }
    }

    private static void save(List<Row> list) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonArray arr = new JsonArray();
            for (Row r : list) {
                JsonObject o = new JsonObject();
                if (!r.uuid().isEmpty()) o.addProperty("uuid", r.uuid());
                o.addProperty("name", r.name());
                o.addProperty("templates", r.templates());
                if (r.anonymous()) o.addProperty("anonymous", true);
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("builders", arr);
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Credits: could not write {} — {}", path, e.toString());
        }
    }

    /** Test seam — forget everything read from disk. */
    static synchronized void reset() {
        rows = null;
    }
}
