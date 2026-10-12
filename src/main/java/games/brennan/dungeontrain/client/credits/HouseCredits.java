package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The team's own rows on the Credits page's Builders and Writers cards — the shipped game's
 * uncredited templates and its built-in books, which no player built or wrote and the relay knows
 * nothing about.
 *
 * <p>Read from the bundled {@code assets/dungeontrain/credits/house_credits.json}, which
 * {@code scripts/credits/house-credits.py} counts from the jar's own weights files and books (CI
 * fails when it is stale). The website's Credits page reads the same committed file, so the two
 * show the same figures. Loaded once per session — the jar does not change under a running game.</p>
 *
 * <p>House rows carry no uuid and no rank, so they are never the viewing player's own row and never
 * get an Edit button.</p>
 */
public final class HouseCredits {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String RESOURCE = "/assets/dungeontrain/credits/house_credits.json";

    /** The parsed file: builder rows and writer rows, each in file order. */
    record Rows(List<TemplateBuilderCredits.Builder> builders, List<RelayWriters.Writer> writers) {
        static final Rows EMPTY = new Rows(List.of(), List.of());
    }

    private static Rows cached;

    private HouseCredits() {}

    /** The team's builder rows; empty when the file is missing or unreadable. */
    public static List<TemplateBuilderCredits.Builder> builders() {
        return rows().builders();
    }

    /** The team's writer rows; empty when the file is missing or unreadable. */
    public static List<RelayWriters.Writer> writers() {
        return rows().writers();
    }

    /**
     * The Writers card's list: the relay's writers with the team's laid in, most books first. The
     * team's rows skip {@link RelayWriters#MIN_BOOKS} — that bar is for player books. Pure.
     */
    static List<RelayWriters.Writer> withWriters(List<RelayWriters.Writer> relay, List<RelayWriters.Writer> house) {
        List<RelayWriters.Writer> out = new ArrayList<>(relay);
        out.addAll(house);
        out.sort(Comparator.comparingInt(RelayWriters.Writer::books).reversed()
                .thenComparing(w -> w.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }

    /** {@link #withWriters} over the live relay list and the bundled rows. */
    public static List<RelayWriters.Writer> mergedWriters() {
        return withWriters(RelayWriters.current(), writers());
    }

    private static synchronized Rows rows() {
        if (cached == null) cached = load();
        return cached;
    }

    /** Drop the cache — for tests. */
    static synchronized void reset() {
        cached = null;
    }

    private static Rows load() {
        try (InputStream in = HouseCredits.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return Rows.EMPTY;
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader));
            }
        } catch (Exception e) {
            // A row that cannot be read costs a line on a page, never the page.
            LOGGER.warn("[DungeonTrain] Credits: could not read house credits from {}: {}", RESOURCE, e.toString());
            return Rows.EMPTY;
        }
    }

    /** Parse the generated file; rows without a name or with a count below one are dropped. Pure. */
    static Rows parse(JsonElement root) {
        if (root == null || !root.isJsonObject()) return Rows.EMPTY;
        JsonObject o = root.getAsJsonObject();
        List<TemplateBuilderCredits.Builder> builders = new ArrayList<>();
        for (JsonObject row : objects(o.get("builders"))) {
            String name = str(row.get("name"));
            int builds = num(row.get("builds"));
            if (!name.isEmpty() && builds > 0) builders.add(new TemplateBuilderCredits.Builder("", name, builds));
        }
        List<RelayWriters.Writer> writers = new ArrayList<>();
        for (JsonObject row : objects(o.get("writers"))) {
            String name = str(row.get("name"));
            int books = num(row.get("books"));
            if (!name.isEmpty() && books > 0) writers.add(new RelayWriters.Writer(name, books));
        }
        return new Rows(List.copyOf(builders), List.copyOf(writers));
    }

    private static List<JsonObject> objects(JsonElement el) {
        List<JsonObject> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        JsonArray arr = el.getAsJsonArray();
        for (JsonElement e : arr) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    private static String str(JsonElement el) {
        return el != null && el.isJsonPrimitive() ? el.getAsString().trim() : "";
    }

    private static int num(JsonElement el) {
        try {
            return el != null && el.isJsonPrimitive() ? el.getAsInt() : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
