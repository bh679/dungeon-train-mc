package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The Stage definitions that travel with a relay-saved build.
 *
 * <p>A build links Stages by id — its own top-level link, and for a carriage the per-entry links
 * in its parts sidecar — but a Stage is a library object shared by every template on the install,
 * so the {@link TemplateSidecars} mechanism carries the links and not the definitions. This class
 * is the other half: on upload, the author's <b>user-authored</b> Stages
 * ({@link StageStore#userAuthored()} — new ones, and bundled ones they edited; an untouched bundled
 * Stage already exists everywhere) go up as {@code stages: { id: json }}, alongside
 * {@code stageIds}, the ids this build links. The relay files the definitions once per author and
 * hands back the linked ones on fetch. On download, the ones missing here are written, and the
 * ones that already exist here with different settings are the player's call — see
 * {@link #conflicts} and {@link #install}.</p>
 *
 * <p>The pure halves ({@link #encodeLibrary}, {@link #linkedIds(String, Optional)},
 * {@link #conflicts(Map, Function)}, {@link #toInstall}) take their inputs as arguments so they can
 * be unit-tested without a store; the wrappers read the live stores.</p>
 */
public final class TemplateStages {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Wire cap on the encoded library, mirroring {@link TemplateSidecars#MAX_DOC_CHARS}. */
    static final int MAX_DOC_CHARS = 200_000;

    /** As many stage ids as one build could plausibly link — the relay bounds its column the same. */
    static final int MAX_LINKED = 64;

    private TemplateStages() {}

    // ---------- upload ----------

    /**
     * The author's user-authored Stages as the wire document — a JSON object of
     * {@code "<id>": "<stage json text>"} — or {@code null} when there are none or the document
     * would be absurdly large. Null is "say nothing", which the relay reads as leaving the
     * author's library untouched.
     */
    public static String collectLibrary() {
        return encodeLibrary(StageStore.userAuthored());
    }

    /** Pure form of {@link #collectLibrary}. */
    static String encodeLibrary(Map<String, Stage> authored) {
        if (authored == null || authored.isEmpty()) return null;
        JsonObject out = new JsonObject();
        for (Stage s : new TreeMap<>(authored).values()) {
            if (s == null || s.id().isBlank()) continue;
            out.addProperty(s.id(), StageStore.toJsonText(s));
        }
        String text = out.toString();
        if (text.length() > MAX_DOC_CHARS) {
            LOGGER.info("[DungeonTrain] Builder relay upload: stage library is {} chars, over the {} limit — not sent.",
                    text.length(), MAX_DOC_CHARS);
            return null;
        }
        return text;
    }

    /**
     * The Stage ids this build links: its top-level link plus, for a carriage, every distinct
     * per-entry link in its parts assignment. Lowercased, deduped, in first-seen order.
     */
    public static List<String> linkedIds(BuilderPhotoPaths.Kind kind, String id, String topLevelStageId) {
        Optional<CarriagePartAssignment> parts = Optional.empty();
        if (kind == BuilderPhotoPaths.Kind.CARRIAGE && id != null && !id.isEmpty()) {
            parts = CarriageVariantRegistry.find(id).flatMap(CarriageVariantPartsStore::get);
        }
        return linkedIds(topLevelStageId, parts);
    }

    /** Pure form of {@link #linkedIds(BuilderPhotoPaths.Kind, String, String)}. */
    static List<String> linkedIds(String topLevelStageId, Optional<CarriagePartAssignment> parts) {
        Set<String> ids = new LinkedHashSet<>();
        addId(ids, topLevelStageId);
        parts.ifPresent(assignment -> {
            for (CarriagePartKind partKind : CarriagePartKind.values()) {
                for (CarriagePartAssignment.WeightedName entry : assignment.entries(partKind)) {
                    addId(ids, entry.stageId());
                }
            }
        });
        List<String> out = new ArrayList<>(ids);
        return out.size() > MAX_LINKED ? out.subList(0, MAX_LINKED) : out;
    }

    private static void addId(Set<String> into, String id) {
        if (id == null) return;
        String key = id.trim().toLowerCase(Locale.ROOT);
        if (!key.isEmpty()) into.add(key);
    }

    // ---------- download ----------

    /**
     * A Stage the relay sent that already exists here with different settings. Both sides as the
     * wire text, so the client can show the comparison without a store of its own.
     */
    public record Conflict(String id, String localJson, String incomingJson) {}

    /** The relay's {@code stages} object as {@code id → json text}, tolerant of anything else. */
    public static Map<String, String> decode(JsonObject stages) {
        Map<String, String> out = new LinkedHashMap<>();
        if (stages == null) return out;
        for (Map.Entry<String, JsonElement> e : stages.entrySet()) {
            JsonElement v = e.getValue();
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                String key = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) out.put(key, v.getAsString());
            }
        }
        return out;
    }

    /**
     * Which of {@code incoming} collide with a Stage this install already has — same id, different
     * name, levels or phases. An id missing here is not a conflict (it just installs), and neither is
     * one that matches exactly. Incoming text that does not parse is skipped: it will not install
     * either, so there is nothing to ask about.
     */
    public static List<Conflict> conflicts(Map<String, String> incoming) {
        return conflicts(incoming, StageStore::get);
    }

    /** Pure form of {@link #conflicts(Map)}. */
    static List<Conflict> conflicts(Map<String, String> incoming, Function<String, Optional<Stage>> local) {
        List<Conflict> out = new ArrayList<>();
        if (incoming == null) return out;
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            Stage theirs = StageStore.parseJsonText(e.getKey(), e.getValue());
            if (theirs == null) continue;
            Optional<Stage> mine = local.apply(theirs.id());
            if (mine.isEmpty() || mine.get().equals(theirs)) continue;
            out.add(new Conflict(theirs.id(), StageStore.toJsonText(mine.get()), e.getValue()));
        }
        return out;
    }

    /**
     * Write the Stages a build needs: every incoming id missing here, plus those in
     * {@code overwrite} — the ones the player chose "Use theirs" for — over the local copy. The rest
     * are left exactly as they are ("Keep mine"). Never throws: a failed write is logged, and the
     * build install carries on, because a template with a dangling Stage link is still a template.
     */
    public static void install(Map<String, String> incoming, Set<String> overwrite) {
        Map<String, Stage> toWrite = toInstall(incoming, overwrite, StageStore::exists);
        if (toWrite.isEmpty()) return;
        try {
            StageStore.installAll(toWrite);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Builder relay download: could not write stages {}: {}",
                    toWrite.keySet(), e.toString());
        }
    }

    /** Pure form of {@link #install}: what would be written, given which ids already exist. */
    static Map<String, Stage> toInstall(Map<String, String> incoming, Set<String> overwrite,
                                        Function<String, Boolean> exists) {
        Map<String, Stage> out = new TreeMap<>();
        if (incoming == null) return out;
        Set<String> over = overwrite == null ? Set.of() : overwrite;
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            Stage s = StageStore.parseJsonText(e.getKey(), e.getValue());
            if (s == null) {
                LOGGER.warn("[DungeonTrain] Builder relay download: stage '{}' would not parse — skipped", e.getKey());
                continue;
            }
            if (!exists.apply(s.id()) || over.contains(s.id())) out.put(s.id(), s);
        }
        return out;
    }
}
