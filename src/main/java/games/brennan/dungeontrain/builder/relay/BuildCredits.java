package games.brennan.dungeontrain.builder.relay;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.UserContentPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who originally built each template this install downloaded from somebody else.
 *
 * <p>A build fetched from another player's profile lands in the local library as an ordinary
 * template — the same files a locally-authored one writes — and from that moment nothing on disk
 * says whose work it is. This is the record that says so: the creator's uuid, and their display
 * name as the screen that offered the build showed it.</p>
 *
 * <h2>Write-once</h2>
 * <p>{@link #put} keeps whatever is already filed. A downloaded build is opened, edited and saved
 * like any other, and each of those is a moment where a later writer could plausibly claim it —
 * so the <em>first</em> credit recorded for a name is the one that stands. The two ways a credit
 * legitimately goes away are explicit: {@link #forget} (this build turned out to be the player's
 * own) and {@link #move} (the template was renamed and its credit follows it).</p>
 *
 * <h2>One file, every kind</h2>
 * <p>Keyed by {@link BuilderRelayBuilds#keyOf} — the {@code (kind, subKind, id)} triple every
 * other part of the download path identifies a template by, because {@code standard} is both a
 * floor part and a door part. One keyed file rather than a sidecar beside each {@code .nbt}: a
 * carriage group has no sidecar family at all ({@code TemplateSidecars.filesFor} returns nothing
 * for it), and it would have been the one kind that silently lost its attribution.</p>
 *
 * <p>Filed in the active content package, like the templates it describes — switching package
 * switches both together, which is the only way the two cannot disagree.</p>
 *
 * <p>Nothing here throws out to a caller. A credit that cannot be read or written is logged and
 * stepped over: attribution is worth recording and never worth failing an install over.</p>
 */
public final class BuildCredits {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Its own directory rather than the user root, so a template scan can never see the file. */
    static final String SUBDIR = "credits";
    static final String FILENAME = "build-credits.json";

    private static final String K_UUID = "uuid";
    private static final String K_NAME = "name";
    private static final String K_AT = "at";

    /** Loaded on first use and kept — every read is on the server thread, every write rewrites it. */
    private static Map<String, Credit> cache;

    private BuildCredits() {}

    /**
     * One template's original creator.
     *
     * @param creatorUuid the durable identity — what the relay knows them by
     * @param creatorName their display name when the build was downloaded, or empty. A cache for
     *                    the screen: names change, and the uuid is what identifies anybody
     * @param recordedAtMs wall clock at the moment the build landed here
     */
    public record Credit(String creatorUuid, String creatorName, long recordedAtMs) {
        public Credit {
            creatorUuid = creatorUuid == null ? "" : creatorUuid.trim();
            creatorName = creatorName == null ? "" : creatorName.trim();
        }

        /** Whether this says anything at all — a credit naming nobody is not worth filing. */
        public boolean known() {
            return !creatorUuid.isEmpty() || !creatorName.isEmpty();
        }

        /** What to show a player: the name when there is one, else the uuid that stands in for it. */
        public String display() {
            return creatorName.isEmpty() ? creatorUuid : creatorName;
        }
    }

    /** The key one template is filed under — the download path's own {@code (kind, subKind, id)}. */
    public static String keyOf(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        return BuilderRelayBuilds.keyOf(kind == null ? "" : kind.id(), subKind, id);
    }

    /** Who built {@code id}, or null when this install has no record of anyone but the player. */
    public static synchronized Credit get(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        if (kind == null || id == null || id.isEmpty()) return null;
        return load().get(keyOf(kind, subKind, id));
    }

    /**
     * File {@code credit} against {@code id}, unless something is already filed there.
     *
     * @return true when this call is what put the credit on disk
     */
    public static synchronized boolean put(BuilderPhotoPaths.Kind kind, String subKind, String id,
                                           Credit credit) {
        if (kind == null || id == null || id.isEmpty() || credit == null || !credit.known()) return false;
        Map<String, Credit> map = load();
        String key = keyOf(kind, subKind, id);
        // Write-once: a build's creator is decided the first time this install is told who it is.
        if (map.containsKey(key)) return false;
        Map<String, Credit> next = new LinkedHashMap<>(map);
        next.put(key, credit);
        return save(next);
    }

    /** Drop {@code id}'s credit — the build turned out to be the downloading player's own. */
    public static synchronized void forget(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        if (kind == null || id == null || id.isEmpty()) return;
        Map<String, Credit> map = load();
        String key = keyOf(kind, subKind, id);
        if (!map.containsKey(key)) return;
        Map<String, Credit> next = new LinkedHashMap<>(map);
        next.remove(key);
        save(next);
    }

    /**
     * Carry {@code oldId}'s credit over to {@code newId} — the template itself has just moved.
     *
     * <p>Silently does nothing when the old name had no credit, which is the ordinary case: a
     * locally-authored build being shoved aside to make room for a download.</p>
     */
    public static synchronized void move(BuilderPhotoPaths.Kind kind, String subKind, String oldId,
                                         String newId) {
        if (kind == null || oldId == null || newId == null || oldId.isEmpty() || newId.isEmpty()) return;
        Map<String, Credit> map = load();
        Credit credit = map.get(keyOf(kind, subKind, oldId));
        if (credit == null) return;
        Map<String, Credit> next = new LinkedHashMap<>(map);
        next.remove(keyOf(kind, subKind, oldId));
        next.put(keyOf(kind, subKind, newId), credit);
        save(next);
    }

    /** Everything filed, in the order it was recorded. Read by tests and by the exporter's report. */
    public static synchronized Map<String, Credit> all() {
        return Map.copyOf(load());
    }

    /** Drop what's cached, so the next read goes back to disk (package switch, test, reload). */
    public static synchronized void invalidate() {
        cache = null;
    }

    // ---- persistence ----

    static Path file() {
        return UserContentPaths.activeSubDir(SUBDIR).resolve(FILENAME);
    }

    private static Map<String, Credit> load() {
        if (cache != null) return cache;
        cache = readFrom(file());
        return cache;
    }

    /**
     * The credits in {@code path}, or an empty map.
     *
     * <p>A garbled file reads as no credits rather than as a failure: the alternative is a builder
     * world that will not open because a display line has nowhere to read a name from.</p>
     */
    static Map<String, Credit> readFrom(Path path) {
        Map<String, Credit> out = new LinkedHashMap<>();
        if (path == null || !Files.isRegularFile(path)) return out;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return out;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                Credit credit = decode(e.getValue());
                if (credit != null) out.put(e.getKey(), credit);
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Build credits: could not read {}: {}", path, e.toString());
        }
        return out;
    }

    /** One entry, or null when it names nobody — the same tolerance the file as a whole gets. */
    public static Credit decode(JsonElement entry) {
        if (entry == null || !entry.isJsonObject()) return null;
        JsonObject o = entry.getAsJsonObject();
        Credit credit = new Credit(str(o, K_UUID), str(o, K_NAME),
                o.has(K_AT) && o.get(K_AT).isJsonPrimitive() ? o.get(K_AT).getAsLong() : 0L);
        return credit.known() ? credit : null;
    }

    public static JsonObject encode(Credit credit) {
        JsonObject o = new JsonObject();
        o.addProperty(K_UUID, credit.creatorUuid());
        o.addProperty(K_NAME, credit.creatorName());
        o.addProperty(K_AT, credit.recordedAtMs());
        return o;
    }

    /** The whole map as it goes to disk — separated out so the shape can be tested without a world. */
    static JsonObject toJson(Map<String, Credit> credits) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Credit> e : credits.entrySet()) root.add(e.getKey(), encode(e.getValue()));
        return root;
    }

    /** Replace the file and the cache together, so a failed write cannot leave the two disagreeing. */
    private static boolean save(Map<String, Credit> next) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path,
                    new GsonBuilder().setPrettyPrinting().create().toJson(toJson(next)),
                    StandardCharsets.UTF_8);
            cache = next;
            return true;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Build credits: could not write {}: {}", path, e.toString());
            return false;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
