package games.brennan.dungeontrain.editor.workbench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The Workbench's own shelf: every staged build as a folder under the user tier, outside every
 * template store and registry.
 *
 * <pre>
 * workbench/&lt;stagedId&gt;/blocks.nbt     the folded block snapshot (CarriageBlockSnapshot tag)
 * workbench/&lt;stagedId&gt;/meta.json      WorkbenchStagedBuild minus the two documents below
 * workbench/&lt;stagedId&gt;/sidecars.json  the relay's sidecar document, verbatim
 * workbench/&lt;stagedId&gt;/prefabs.json   the loot prefabs it carried, id → document
 * </pre>
 *
 * <p>Nothing here is a template. No registry scans this folder, no weights file names it, the
 * train never draws from it, and — deliberately — no save hook promotes it into the source tree:
 * a staged build is a thing being looked at, not a thing that has been accepted. It becomes a
 * template only through {@code WorkbenchCommit}, which hands it to the ordinary install path and
 * then deletes it from here.</p>
 *
 * <p>A per-id cache of the metadata, kept because the category's model list is rebuilt often (every
 * label pass, every X-menu open) and the folder is cheap to list but the JSON is not free to parse.
 * The block tag is never cached — it is read for one stamp at a time.</p>
 */
public final class WorkbenchStagingStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final String SUBDIR = "workbench";
    static final String BLOCKS = "blocks.nbt";
    static final String META = "meta.json";
    static final String SIDECARS = "sidecars.json";
    static final String PREFABS = "prefabs.json";

    private static final Map<String, WorkbenchStagedBuild> CACHE = new LinkedHashMap<>();
    private static boolean scanned = false;

    private WorkbenchStagingStore() {}

    /** The folder every staged build lives under. */
    public static Path directory() {
        return UserContentPaths.dir(SUBDIR);
    }

    /** {@code stagedId}'s folder, whether or not it exists. */
    public static Path folderFor(String stagedId) {
        return directory().resolve(stagedId.toLowerCase(Locale.ROOT));
    }

    /** Forget everything read so far; the next call re-reads the folder. */
    public static synchronized void clearCache() {
        CACHE.clear();
        scanned = false;
    }

    /** Every staged build, oldest first. */
    public static synchronized List<WorkbenchStagedBuild> list() {
        ensureScanned();
        List<WorkbenchStagedBuild> out = new ArrayList<>(CACHE.values());
        out.sort(Comparator.comparingLong(WorkbenchStagedBuild::stagedAt).thenComparing(WorkbenchStagedBuild::stagedId));
        return List.copyOf(out);
    }

    /** The staged build called {@code stagedId}, if one is here. */
    public static synchronized Optional<WorkbenchStagedBuild> find(String stagedId) {
        if (stagedId == null || stagedId.isEmpty()) return Optional.empty();
        ensureScanned();
        return Optional.ofNullable(CACHE.get(stagedId.toLowerCase(Locale.ROOT)));
    }

    /** Whether {@code stagedId} is already taken here. */
    public static synchronized boolean exists(String stagedId) {
        return find(stagedId).isPresent();
    }

    /**
     * A staged id built from {@code buildName} and {@code relayId} that no staged build uses yet —
     * the plain form, else with {@code -2}, {@code -3}, … appended.
     */
    public static synchronized String freeId(String buildName, int relayId) {
        String base = WorkbenchStagedBuild.idFor(buildName, relayId);
        if (!exists(base)) return base;
        for (int n = 2; n < 1000; n++) {
            String candidate = base + "-" + n;
            if (!exists(candidate)) return candidate;
        }
        return base + "-" + System.currentTimeMillis();
    }

    /** Write {@code build} and its {@code blocks} tag; replaces an existing staged build of the same id. */
    public static synchronized void write(WorkbenchStagedBuild build, CompoundTag blocks) throws IOException {
        Path folder = folderFor(build.stagedId());
        Files.createDirectories(folder);
        NbtIo.writeCompressed(blocks, folder.resolve(BLOCKS));
        Files.writeString(folder.resolve(META), GSON.toJson(metaJson(build)), StandardCharsets.UTF_8);
        Files.writeString(folder.resolve(SIDECARS), build.sidecars(), StandardCharsets.UTF_8);
        Files.writeString(folder.resolve(PREFABS), GSON.toJson(prefabsJson(build.lootPrefabs())), StandardCharsets.UTF_8);
        ensureScanned();
        CACHE.put(build.stagedId().toLowerCase(Locale.ROOT), build);
    }

    /** Replace only the block tag of {@code stagedId} — what a Save in the Workbench does. */
    public static synchronized void replaceBlocks(String stagedId, CompoundTag blocks, Vec3i size) throws IOException {
        WorkbenchStagedBuild build = find(stagedId).orElseThrow(
                () -> new IOException("No staged build '" + stagedId + "'"));
        Path folder = folderFor(stagedId);
        Files.createDirectories(folder);
        NbtIo.writeCompressed(blocks, folder.resolve(BLOCKS));
        if (!size.equals(build.size())) {
            WorkbenchStagedBuild resized = build.withSize(size);
            Files.writeString(folder.resolve(META), GSON.toJson(metaJson(resized)), StandardCharsets.UTF_8);
            CACHE.put(stagedId.toLowerCase(Locale.ROOT), resized);
        }
    }

    /** The block snapshot tag of {@code stagedId}, or empty when the folder or file is missing. */
    public static Optional<CompoundTag> readBlocks(String stagedId) {
        Path file = folderFor(stagedId).resolve(BLOCKS);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Workbench: could not read {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /** Remove {@code stagedId} and every file under it. True when something was deleted. */
    public static synchronized boolean delete(String stagedId) throws IOException {
        ensureScanned();
        boolean known = CACHE.remove(stagedId.toLowerCase(Locale.ROOT)) != null;
        Path folder = folderFor(stagedId);
        if (!Files.isDirectory(folder)) return known;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(folder)) {
            files = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : files) Files.deleteIfExists(p);
        return true;
    }

    // ---- folder scan ----

    private static void ensureScanned() {
        if (scanned) return;
        scanned = true;
        CACHE.clear();
        Path dir = directory();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> folders = Files.list(dir)) {
            folders.filter(Files::isDirectory).sorted().forEach(folder -> {
                WorkbenchStagedBuild build = read(folder);
                if (build != null) CACHE.put(build.stagedId().toLowerCase(Locale.ROOT), build);
            });
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Workbench: could not list {}: {}", dir, e.toString());
        }
    }

    private static WorkbenchStagedBuild read(Path folder) {
        Path meta = folder.resolve(META);
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(folder.resolve(BLOCKS))) return null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            String sidecars = Files.isRegularFile(folder.resolve(SIDECARS))
                    ? Files.readString(folder.resolve(SIDECARS), StandardCharsets.UTF_8) : "";
            Map<String, String> prefabs = Files.isRegularFile(folder.resolve(PREFABS))
                    ? prefabsOf(JsonParser.parseString(Files.readString(folder.resolve(PREFABS), StandardCharsets.UTF_8)))
                    : Map.of();
            String id = str(o, "stagedId", folder.getFileName().toString());
            return new WorkbenchStagedBuild(id, o.has("relayId") ? o.get("relayId").getAsInt() : 0,
                    str(o, "kind", ""), str(o, "subKind", ""), str(o, "buildName", id), str(o, "stage", ""),
                    str(o, "ownerUuid", ""), str(o, "ownerName", ""),
                    o.has("mine") && o.get("mine").getAsBoolean(),
                    new Vec3i(o.get("l").getAsInt(), o.get("h").getAsInt(), o.get("w").getAsInt()),
                    sidecars, prefabs, o.has("stagedAt") ? o.get("stagedAt").getAsLong() : 0L);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Workbench: skipping unreadable staged build at {}: {}", folder, e.toString());
            return null;
        }
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    static JsonObject metaJson(WorkbenchStagedBuild b) {
        JsonObject o = new JsonObject();
        o.addProperty("stagedId", b.stagedId());
        o.addProperty("relayId", b.relayId());
        o.addProperty("kind", b.relayKind());
        o.addProperty("subKind", b.subKind());
        o.addProperty("buildName", b.buildName());
        o.addProperty("stage", b.stage());
        o.addProperty("ownerUuid", b.ownerUuid());
        o.addProperty("ownerName", b.ownerName());
        o.addProperty("mine", b.mine());
        o.addProperty("l", b.size().getX());
        o.addProperty("h", b.size().getY());
        o.addProperty("w", b.size().getZ());
        o.addProperty("stagedAt", b.stagedAt());
        return o;
    }

    static JsonObject prefabsJson(Map<String, String> prefabs) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, String> e : prefabs.entrySet()) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    static Map<String, String> prefabsOf(JsonElement doc) {
        Map<String, String> out = new LinkedHashMap<>();
        if (doc == null || !doc.isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> e : doc.getAsJsonObject().entrySet()) {
            if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
        }
        return out;
    }
}
