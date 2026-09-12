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
import java.util.Locale;
import java.util.Map;

/**
 * The edits this client has made to its own Credits-page lines, remembered so the page shows them
 * at once — the relay's boards rebuild on a five-minute sweep and the jar's baked credits only
 * change at the next release.
 *
 * <p>Per section: the name the player chose ({@code from → to}) and whether they asked to be
 * anonymous. {@link #apply} lays that over what the relay (or the jar) currently says about the
 * player's own row, and <b>forgets an entry the moment the relay has caught up</b> — a rename is
 * needed only while the relay still shows {@code from}, an opt-out only until the relay's row is
 * already anonymous — so a change made from another machine later is never masked by a stale
 * local copy. Translators are the exception: the jar bakes their names, so their opt-out is kept
 * until they restore ({@code TranslatorRenames} already keeps their aliases the same way).</p>
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

    private static final Map<Section, Entry> ENTRIES = new EnumMap<>(Section.class);
    private static boolean loaded;

    private CreditsSelfEdits() {}

    /** This section's remembered edits, {@link Entry#NONE} when there are none. */
    public static synchronized Entry get(Section section) {
        ensureLoaded();
        return ENTRIES.getOrDefault(section, Entry.NONE);
    }

    /** Remember that the player's credit in {@code section} is now {@code to} (was {@code from}). */
    public static synchronized void recordRename(Section section, String from, String to) {
        ensureLoaded();
        put(section, get(section).withRename(from, to));
    }

    /** Remember that the player asked to be anonymous in {@code section} — or not. */
    public static synchronized void setHidden(Section section, boolean hidden) {
        ensureLoaded();
        put(section, get(section).withHidden(hidden));
    }

    /**
     * The player's own row as it should be shown: the overlay laid over what the source currently
     * says ({@code name}, and whether it is already anonymous). Pure — see {@link #apply(Entry,
     * String, boolean)}; this form also drops the entry once the source has caught up.
     */
    public static synchronized Shown apply(Section section, String name, boolean anonymous) {
        ensureLoaded();
        Entry entry = get(section);
        Shown shown = apply(entry, name, anonymous);
        Entry trimmed = caughtUp(section, entry, name, anonymous);
        if (!trimmed.equals(entry)) put(section, trimmed);
        return shown;
    }

    /** The overlay rule, pure. Hidden wins; a rename applies only while the source still shows {@code from}. */
    static Shown apply(Entry entry, String name, boolean anonymous) {
        String n = name == null ? "" : name;
        if (anonymous || entry.hidden()) return new Shown("", true);
        if (entry.hasRename() && n.equalsIgnoreCase(entry.from())) return new Shown(entry.to(), false);
        return new Shown(n, false);
    }

    /**
     * The entry with whatever the source now agrees on removed — the rename once the source shows
     * {@code to} (or anything but {@code from}), the opt-out once the source's row is anonymous.
     * Translators keep their opt-out: their baked names would otherwise reappear next launch.
     */
    static Entry caughtUp(Section section, Entry entry, String name, boolean anonymous) {
        Entry out = entry;
        String n = name == null ? "" : name;
        if (out.hasRename() && !anonymous && !n.equalsIgnoreCase(out.from())) out = out.withoutRename();
        if (out.hidden() && anonymous && section != Section.TRANSLATIONS) out = out.withHidden(false);
        return out;
    }

    private static void put(Section section, Entry entry) {
        if (entry.isEmpty()) ENTRIES.remove(section);
        else ENTRIES.put(section, entry);
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
            for (Section section : Section.values()) {
                JsonElement el = root.getAsJsonObject().get(section.wire());
                if (el == null || !el.isJsonObject()) continue;
                Entry entry = parseEntry(el.getAsJsonObject());
                if (!entry.isEmpty()) ENTRIES.put(section, entry);
            }
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
            for (Map.Entry<Section, Entry> e : ENTRIES.entrySet()) {
                JsonObject o = new JsonObject();
                if (e.getValue().hasRename()) {
                    o.addProperty("from", e.getValue().from());
                    o.addProperty("to", e.getValue().to());
                }
                if (e.getValue().hidden()) o.addProperty("hidden", true);
                root.add(e.getKey().name().toLowerCase(Locale.ROOT), o);
            }
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Credits: could not save {} — {}", FILE, e.toString());
        }
    }

    /** Test seam — forget everything read from disk. */
    static synchronized void reset() {
        ENTRIES.clear();
        loaded = false;
    }
}
