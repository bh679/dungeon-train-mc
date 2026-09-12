package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.credits.CreditEditClient.Section;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * The edit this client has made to its own Credits-page lines, remembered so the page shows it at
 * once — the relay's boards rebuild on a five-minute sweep and the jar's baked credits only change
 * at the next release.
 *
 * <p>One identity: the name the player chose ({@code from → to}) and whether they asked to be
 * anonymous, applied to their row on <b>every</b> card. {@link #apply} lays that over what the relay
 * (or the jar) currently says about the row, and <b>forgets the rename the moment the relay has
 * caught up</b> on that card — it is needed only while the relay still shows {@code from} — so a
 * change made from another machine later is never masked by a stale local copy. The opt-out is
 * kept until the player restores: the jar bakes translator and builder names, so the relay's rows
 * catching up is not the whole page catching up.</p>
 *
 * <p>Only ever about <i>this</i> player. It says nothing about anybody else's line, and the page
 * applies it to rows it has already identified as the player's own.</p>
 */
public final class CreditsSelfEdits {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SUBDIR = "credits";
    static final String FILE = "self.json";

    /** One section's remembered edits. Immutable; a change is a new value. */
    public record Entry(String from, String to, boolean hidden) {
        public static final Entry NONE = new Entry("", "", false);

        public Entry {
            from = from == null ? "" : from.trim();
            to = to == null ? "" : to.trim();
        }

        public boolean hasRename() {
            return !from.isEmpty() && !to.isEmpty() && !from.equals(to);
        }

        public boolean isEmpty() {
            return !hasRename() && !hidden;
        }

        Entry withRename(String f, String t) {
            return new Entry(f, t, hidden);
        }

        Entry withHidden(boolean h) {
            return new Entry(from, to, h);
        }

        Entry withoutRename() {
            return new Entry("", "", hidden);
        }
    }

    /** What the page should print for the player's own row after the overlay. */
    public record Shown(String name, boolean anonymous) {}

    private static Entry entry = Entry.NONE;
    private static boolean loaded;

    private CreditsSelfEdits() {}

    /** The remembered edit, {@link Entry#NONE} when there is none. */
    public static synchronized Entry get() {
        ensureLoaded();
        return entry;
    }

    /** Remember that the player's credit is now {@code to} (was {@code from}). */
    public static synchronized void recordRename(String from, String to) {
        ensureLoaded();
        put(entry.withRename(from, to));
    }

    /** Remember that the player asked to be anonymous — or not. */
    public static synchronized void setHidden(boolean hidden) {
        ensureLoaded();
        put(entry.withHidden(hidden));
    }

    /**
     * The player's own row on one card as it should be shown: the overlay laid over what the
     * source currently says ({@code name}, and whether it is already anonymous). Pure — see
     * {@link #apply(Entry, String, boolean)}. Between {@link #beginPage} and {@link #endPage} the
     * page reports every own row it lays out; the rename is dropped at {@link #endPage} once none
     * of them still shows {@code from}.
     */
    public static synchronized Shown apply(Section section, String name, boolean anonymous) {
        ensureLoaded();
        Shown shown = apply(entry, name, anonymous);
        noteSeen(section, name, anonymous);
        return shown;
    }

    /** Which cards, this page open, still show {@code from}; reset by {@link #beginPage}. */
    private static final Map<Section, Boolean> STILL_OLD = new EnumMap<>(Section.class);

    /** A new lay-out of the page: start counting afresh which cards still show the old name. */
    public static synchronized void beginPage() {
        STILL_OLD.clear();
    }

    /**
     * The page is laid out. Forget the rename once every own row it showed has moved on from
     * {@code from} — one card lagging (the boards sweep every five minutes) must not bring the old
     * name back on the others, and a card the player is not on says nothing either way.
     */
    public static synchronized void endPage() {
        if (entry.hasRename() && !STILL_OLD.isEmpty() && !STILL_OLD.containsValue(true)) {
            put(entry.withoutRename());
        }
        STILL_OLD.clear();
    }

    private static void noteSeen(Section section, String name, boolean anonymous) {
        if (entry.hasRename()) STILL_OLD.put(section, stillOld(entry, name, anonymous));
    }

    /** The overlay rule, pure. Hidden wins; a rename applies only while the source still shows {@code from}. */
    static Shown apply(Entry entry, String name, boolean anonymous) {
        String n = name == null ? "" : name;
        if (anonymous || entry.hidden()) return new Shown("", true);
        if (entry.hasRename() && n.equalsIgnoreCase(entry.from())) return new Shown(entry.to(), false);
        return new Shown(n, false);
    }

    /**
     * Whether one card's current row still needs the rename: it is not anonymous and still shows
     * {@code from}. Pure, for the tests; the page-level rule that forgets the rename is in
     * {@link #apply(Section, String, boolean)}.
     */
    static boolean stillOld(Entry entry, String name, boolean anonymous) {
        String n = name == null ? "" : name;
        return entry.hasRename() && !anonymous && n.equalsIgnoreCase(entry.from());
    }

    private static void put(Entry e) {
        entry = e;
        save();
    }

    // ---- disk -------------------------------------------------------------------

    private static Path file() {
        return PlayerDataPaths.dir(SUBDIR).resolve(FILE);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true; // set first: a failed read must not retry on every credits render
        Path path;
        try {
            path = file();
        } catch (Exception e) {
            return;
        }
        if (!Files.isRegularFile(path)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            entry = parseEntry(root.getAsJsonObject());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Credits: could not read {} — {}", path, e.toString());
        }
    }

    static Entry parseEntry(JsonObject o) {
        return new Entry(str(o.get("from")), str(o.get("to")),
            o.has("hidden") && o.get("hidden").isJsonPrimitive() && o.get("hidden").getAsBoolean());
    }

    private static String str(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : "";
    }

    private static void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            if (entry.hasRename()) {
                root.addProperty("from", entry.from());
                root.addProperty("to", entry.to());
            }
            if (entry.hidden()) root.addProperty("hidden", true);
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Credits: could not save {} — {}", FILE, e.toString());
        }
    }

    /** Test seam — forget everything read from disk. */
    static synchronized void reset() {
        entry = Entry.NONE;
        STILL_OLD.clear();
        loaded = false;
    }
}
