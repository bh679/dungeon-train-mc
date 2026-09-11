package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The loot prefabs a template's chests link to, travelling with the template between installs.
 *
 * <p>{@link TemplateSidecars} carries the chest → prefab <em>links</em> (the {@code containers}
 * store) but deliberately not the prefabs, which are a library shared by every template on an
 * install. Without them a downloaded build lands with its chests pointing at ids the installing
 * machine has never heard of, and rolls nothing. So a save also sends the prefabs the build links
 * — only the ones this install authored, since a bundled prefab is on every install already — and a
 * download files the ones that arrive.</p>
 *
 * <h2>Never silently over a local prefab</h2>
 * <p>Installing a build must not change the loot of an unrelated template that happens to share a
 * prefab id. So an arriving prefab whose id this install already holds with <b>different</b>
 * contents is a {@link Conflict}, and the download stops to ask (see
 * {@code BuilderRelayDownload.Outcome#PREFAB_CONFLICT}) before anything is written. Missing ids
 * are installed, identical ones are left alone, and only the ids the player explicitly chose are
 * overwritten on the replay.</p>
 *
 * <h2>Verbatim text</h2>
 * <p>Prefabs travel as their file text, for the same reason sidecars do: it is the store's own
 * format, and re-encoding it here would make this a second writer to keep in step. Equality is
 * judged on the <em>parsed</em> pool, though — two files that differ only in whitespace or key
 * order are the same prefab, and asking about them would be noise.</p>
 *
 * <p>The disk is reached through a {@link Library}, defaulting to {@link LootPrefabStore}, so the
 * comparison and install rules can be tested without a game directory.</p>
 */
public final class TemplateLootPrefabs {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Refuse to carry a document past this — the same runaway guard {@link TemplateSidecars} has. */
    static final int MAX_DOC_CHARS = 200_000;
    /** Prefabs carried by one build. The relay caps its side at the same number. */
    public static final int MAX_PER_BUILD = 64;
    /** Cap on one prefab's text on the wire. A real file is a few hundred bytes. */
    public static final int MAX_TEXT_CHARS = 32_000;

    private TemplateLootPrefabs() {}

    /** Where prefabs live — the seam between the rules here and the store on disk. */
    public interface Library {
        /** This install's text for {@code id}, config tier first then bundled, or empty. */
        Optional<String> localText(String id);

        /** Whether {@code id} has a config-tier file — authored or overridden here. */
        boolean hasConfigFile(String id);

        /** File {@code text} as prefab {@code id} in the config tier. */
        void write(String id, String text) throws IOException;
    }

    /** The real library. */
    public static final Library STORE = new Library() {
        @Override public Optional<String> localText(String id) { return LootPrefabStore.localText(id); }
        @Override public boolean hasConfigFile(String id) { return LootPrefabStore.hasConfigFile(id); }
        @Override public void write(String id, String text) throws IOException { LootPrefabStore.writeText(id, text); }
    };

    /**
     * One prefab that arrived under an id this install already holds with different contents.
     *
     * @param id           the prefab id both sides use
     * @param localText    this install's file text
     * @param incomingText the build's file text
     */
    public record Conflict(String id, String localText, String incomingText) {}

    // ---- collect (upload side) ----

    /**
     * The prefabs template {@code id} links to that this install authored, as the wire document
     * ({@code {"<id>": "<file text>"}}), or {@code ""} when there are none.
     *
     * <p>Only config-tier prefabs are carried: a bundled one is on every install, and sending it
     * would only ever raise a pointless conflict against an install whose copy is the same file.
     * Never throws — a prefab that cannot be read is one the download will do without.</p>
     */
    public static String collect(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        return collect(kind, subKind, id, STORE);
    }

    static String collect(BuilderPhotoPaths.Kind kind, String subKind, String id, Library library) {
        String plotKey = TemplateSidecars.plotKeyFor(kind, subKind, id);
        if (plotKey == null) return "";
        SortedSet<String> linked;
        try {
            linked = ContainerContentsStore.loadFor(plotKey).linkedPrefabIds();
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Loot prefabs: could not read the container links of '{}': {}",
                    id, e.toString());
            return "";
        }
        return encode(textsOf(linked, library, id));
    }

    /** The config-tier text of each linked id, in id order; ids without one are skipped. */
    static Map<String, String> textsOf(Collection<String> linked, Library library, String forTemplate) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : new TreeSet<>(linked)) {
            String prefabId = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
            if (!LootPrefabStore.isValidName(prefabId) || !library.hasConfigFile(prefabId)) continue;
            if (out.size() >= MAX_PER_BUILD) {
                LOGGER.info("[DungeonTrain] Loot prefabs: '{}' links more than {} prefabs — the rest stay local.",
                        forTemplate, MAX_PER_BUILD);
                break;
            }
            Optional<String> text = library.localText(prefabId);
            if (text.isEmpty() || text.get().length() > MAX_TEXT_CHARS) continue;
            out.put(prefabId, text.get());
        }
        return out;
    }

    /** The wire document for {@code prefabs}, or {@code ""} for none or for one too big to carry. */
    static String encode(Map<String, String> prefabs) {
        if (prefabs.isEmpty()) return "";
        JsonObject doc = new JsonObject();
        for (Map.Entry<String, String> e : prefabs.entrySet()) doc.add(e.getKey(), new JsonPrimitive(e.getValue()));
        String text = doc.toString();
        if (text.length() > MAX_DOC_CHARS) {
            LOGGER.info("[DungeonTrain] Loot prefabs: document is {} chars, over the {} limit — "
                    + "uploading the build without them.", text.length(), MAX_DOC_CHARS);
            return "";
        }
        return text;
    }

    /**
     * The inverse of {@link #encode}: the prefabs a relay answer carries. Empty for a blank or
     * unreadable document, and skips any entry that is not an id → text pair — a relay that
     * predates the field, or one that says nothing, reads as "nothing to install".
     */
    public static Map<String, String> decode(JsonElement doc) {
        Map<String, String> out = new LinkedHashMap<>();
        if (doc == null || !doc.isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject().entrySet()) {
            String prefabId = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            JsonElement v = e.getValue();
            if (!LootPrefabStore.isValidName(prefabId) || v == null || !v.isJsonPrimitive()
                    || !v.getAsJsonPrimitive().isString()) continue;
            String text = v.getAsString();
            if (text.isBlank() || text.length() > MAX_TEXT_CHARS) continue;
            out.put(prefabId, text);
            if (out.size() >= MAX_PER_BUILD) break;
        }
        return out;
    }

    /** As {@link #decode(JsonElement)}, from text. */
    public static Map<String, String> decode(String doc) {
        if (doc == null || doc.isBlank()) return new LinkedHashMap<>();
        try {
            return decode(JsonParser.parseString(doc));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    // ---- compare + install (download side) ----

    /**
     * Which of {@code incoming} would land on an id this install already holds with different
     * contents. Identical (as parsed) or absent locally → not a conflict. An incoming prefab that
     * will not parse is not a conflict either: it is refused at install rather than asked about.
     */
    public static List<Conflict> conflicts(Map<String, String> incoming) {
        return conflicts(incoming, STORE);
    }

    static List<Conflict> conflicts(Map<String, String> incoming, Library library) {
        List<Conflict> out = new ArrayList<>();
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            String prefabId = e.getKey();
            Optional<String> local = library.localText(prefabId);
            if (local.isEmpty()) continue;
            if (sameParsed(prefabId, local.get(), e.getValue())) continue;
            out.add(new Conflict(prefabId, local.get(), e.getValue()));
        }
        return out;
    }

    /** Whether two prefab texts describe the same prefab. Unparseable on either side → different. */
    static boolean sameParsed(String id, String a, String b) {
        Optional<LootPrefabStore.Data> da = LootPrefabStore.parse(id, a);
        Optional<LootPrefabStore.Data> db = LootPrefabStore.parse(id, b);
        return da.isPresent() && db.isPresent() && da.get().equals(db.get());
    }

    /**
     * File the prefabs a build arrived with: every id this install lacks, plus the ids in
     * {@code overwrite} — the player's "use theirs" answers. Anything else already here stays
     * exactly as it is, which is the "keep mine" default and the identical-file no-op alike.
     *
     * <p>Per prefab, a failure is logged and stepped over: the template is already on disk by the
     * time this runs, and a build with one prefab missing is better than one refused outright.
     * Returns the ids actually written.</p>
     */
    public static List<String> install(Map<String, String> incoming, Set<String> overwrite) {
        return install(incoming, overwrite, STORE);
    }

    static List<String> install(Map<String, String> incoming, Set<String> overwrite, Library library) {
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            String prefabId = e.getKey();
            boolean present = library.localText(prefabId).isPresent();
            if (present && !overwrite.contains(prefabId)) continue;
            try {
                library.write(prefabId, e.getValue());
                written.add(prefabId);
            } catch (Exception ex) {
                LOGGER.warn("[DungeonTrain] Loot prefabs: could not install '{}': {}", prefabId, ex.toString());
            }
        }
        return written;
    }
}
