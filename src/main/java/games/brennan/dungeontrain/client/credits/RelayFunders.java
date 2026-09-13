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
 * The people who fund the game — the Credits page's "Funders" card.
 *
 * <p>Read off the relay's {@code /funders} list: every backer in its ledger (Stripe, Revolut,
 * Patreon) ranked by all-time contribution, whole Australian dollars, the same numbers the death
 * screen's donation page already shows. Rows are uuid-free on the wire; where this player stands
 * comes from the {@code ?uuid=} form ({@link #fetchStanding}), consent-gated by the caller, and is
 * matched back to a row by {@code rank} exactly as the Writers card does.</p>
 *
 * <p>A funder who hid their figure ({@code amountHidden}) is listed by name alone; one who removed
 * their name ({@code anonymous}) is listed by figure alone — the two are independent, so a donor can
 * be "Anonymous — A$120" or "Ada" as they prefer (see {@code CreditEditScreen}).</p>
 *
 * <p>Nothing bundled to merge with — a donation is on the relay, never in the jar — so this is the
 * relay's list alone, cached to disk for an offline launch. Fetched each time the Credits page
 * opens; never throws, never blocks, never retries. Sibling of {@link RelayWriters}.</p>
 */
public final class RelayFunders {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final String PATH = "/funders";
    static final String SUBDIR = "credits";
    static final String FILE = "funders.json";

    /**
     * One funder: name, whole-dollar AUD total, and the two independent self-edit flags. {@code rank}
     * is the 1-based position on the relay's list, kept through drops and in the cache so a
     * {@link Standing} matches its row.
     */
    public record Funder(String name, int amountAud, boolean anonymous, boolean amountHidden, int rank) {
        public Funder {
            name = name == null ? "" : name.trim();
            amountAud = Math.max(0, amountAud);
            rank = Math.max(0, rank);
        }

        public Funder(String name, int amountAud) {
            this(name, amountAud, false, false, 0);
        }
    }

    /** Where the asking player stands on the list, or {@code null} when not on it / unknown. */
    public record Standing(int rank) {}

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static List<Funder> funders;

    private RelayFunders() {}

    /** Funders in the relay's order (biggest contribution first) — this run's answer, else the disk cache. */
    public static synchronized List<Funder> current() {
        if (funders == null) funders = load();
        return funders;
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
                        LOGGER.debug("[DungeonTrain] Credits: relay funders unavailable — {}",
                            err != null ? err.toString() : "HTTP " + (resp == null ? "?" : resp.statusCode()));
                        return;
                    }
                    if (apply(parse(resp.body())) && onUpdate != null) onUpdate.run();
                });
        } catch (Throwable t) {
            IN_FLIGHT.set(false);
            LOGGER.debug("[DungeonTrain] Credits: relay funders fetch failed — {}", t.toString());
        }
    }

    static synchronized boolean apply(List<Funder> fresh) {
        if (fresh.equals(current())) return false;
        funders = List.copyOf(fresh);
        save(funders);
        return true;
    }

    /**
     * Ask the relay where {@code uuid} stands ({@code /funders?uuid=} → {@code you.rank}).
     * {@code onResult} runs off-thread with the standing, or {@code null} when not on the list or
     * the relay did not answer. Consent-gated by the caller — this carries a uuid.
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
                    // The same body carries the rows; take them while they are here, so the
                    // standing and the rows it indexes come from one answer.
                    apply(parse(resp.body()));
                    onResult.accept(parseStanding(resp.body()));
                });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Credits: relay funders standing fetch failed — {}", t.toString());
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
     * {@code {"rows":[{"name","amountAud"|null,"anonymous"?,"amountHidden"?}]}} to funders in the
     * relay's order. A row flagged {@code anonymous} is kept unnamed; one flagged {@code amountHidden}
     * (or with a null amount) is kept figureless. A row with neither a name nor a flag is nobody and
     * is dropped, as is a malformed body. Each funder keeps its 1-based position ({@code rank}).
     */
    static List<Funder> parse(String body) {
        List<Funder> out = new ArrayList<>();
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
                boolean anonymous = flag(o, "anonymous");
                JsonElement amount = o.get("amountAud");
                boolean amountHidden = flag(o, "amountHidden") || amount == null || amount.isJsonNull();
                int rank = o.has("rank") ? num(o.get("rank")) : position;
                Funder f = new Funder(anonymous ? "" : str(o.get("name")), amountHidden ? 0 : num(amount), anonymous, amountHidden, rank);
                if (anonymous || !f.name().isEmpty()) out.add(f);
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    private static boolean flag(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean();
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

    private static List<Funder> load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return List.of();
        try {
            return List.copyOf(parse(Files.readString(path, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Credits: could not read {} — {}", path, e.toString());
            return List.of();
        }
    }

    private static void save(List<Funder> list) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonArray rows = new JsonArray();
            for (Funder f : list) {
                JsonObject o = new JsonObject();
                o.addProperty("name", f.name());
                if (f.amountHidden()) o.addProperty("amountHidden", true);
                else o.addProperty("amountAud", f.amountAud());
                if (f.anonymous()) o.addProperty("anonymous", true);
                o.addProperty("rank", f.rank());
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
        funders = null;
    }
}
