package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Named library of per-container loot-pool prefabs. Parallel to
 * {@link BlockVariantPrefabStore} for {@link ContainerContentsPool}.
 *
 * <p>Files live at {@code config/dungeontrain/prefabs/loot/<id>.json}. The
 * shape mirrors {@link ContainerContentsStore}'s per-cell pool format so
 * the parser can be a simple translation from the existing JSON layout.</p>
 *
 * <p>Schema:
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "fillMin": 0,
 *   "fillMax": -1,
 *   "entries": [
 *     { "id": "minecraft:wheat", "count": 5, "weight": 10 }
 *   ]
 * }
 * </pre>
 * </p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LootPrefabStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String SUBDIR = "dungeontrain/prefabs/loot";
    private static final String EXT = ".json";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    private static final TreeSet<String> IDS = new TreeSet<>();

    private LootPrefabStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(String id) {
        return directory().resolve(id + EXT);
    }

    public static synchronized List<String> allIds() {
        return new ArrayList<>(IDS);
    }

    public static synchronized boolean contains(String id) {
        return IDS.contains(id.toLowerCase(Locale.ROOT));
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    public static synchronized Optional<ContainerContentsPool> load(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) return Optional.empty();
            JsonObject obj = root.getAsJsonObject();
            int fillMin = obj.has("fillMin") && obj.get("fillMin").isJsonPrimitive()
                ? obj.get("fillMin").getAsInt() : 0;
            int fillMax = obj.has("fillMax") && obj.get("fillMax").isJsonPrimitive()
                ? obj.get("fillMax").getAsInt() : ContainerContentsPool.FILL_ALL;
            List<ContainerContentsEntry> entries = new ArrayList<>();
            if (obj.has("entries") && obj.get("entries").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("entries");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject e = el.getAsJsonObject();
                    if (!e.has("id") || !e.get("id").isJsonPrimitive()) continue;
                    String idStr = e.get("id").getAsString();
                    ResourceLocation rl = ResourceLocation.tryParse(idStr);
                    if (rl == null) continue;
                    int count = e.has("count") && e.get("count").isJsonPrimitive()
                        ? e.get("count").getAsInt() : 1;
                    int weight = e.has("weight") && e.get("weight").isJsonPrimitive()
                        ? e.get("weight").getAsInt() : 1;
                    entries.add(new ContainerContentsEntry(rl, count, weight));
                }
            }
            return Optional.of(new ContainerContentsPool(entries, fillMin, fillMax));
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read loot prefab '{}': {}", key, e.toString());
            return Optional.empty();
        }
    }

    public static synchronized boolean save(String id, ContainerContentsPool pool) throws IOException {
        if (!isValidName(id)) {
            throw new IOException("Invalid prefab name '" + id + "' — must match " + NAME_PATTERN.pattern());
        }
        if (pool == null || pool.isEmpty()) {
            throw new IOException("Loot prefab needs at least one entry");
        }
        String key = id.toLowerCase(Locale.ROOT);
        Path file = fileFor(key);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText(pool));
        }
        boolean isNew = IDS.add(key);
        LOGGER.info("[DungeonTrain] {} loot prefab '{}' ({} entries) at {}",
            isNew ? "Saved new" : "Overwrote", key, pool.entries().size(), file);
        return isNew;
    }

    public static synchronized boolean delete(String id) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        Path file = fileFor(key);
        boolean fileExisted = Files.deleteIfExists(file);
        IDS.remove(key);
        if (fileExisted) {
            LOGGER.info("[DungeonTrain] Deleted loot prefab '{}'", key);
        }
        return fileExisted;
    }

    public static synchronized void reload() {
        IDS.clear();
        Path dir = directory();
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + EXT)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.endsWith(EXT)) continue;
                String basename = name.substring(0, name.length() - EXT.length()).toLowerCase(Locale.ROOT);
                if (!isValidName(basename)) {
                    LOGGER.warn("[DungeonTrain] Ignoring loot prefab file with invalid name: {}", file);
                    continue;
                }
                IDS.add(basename);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan loot prefab dir {}: {}", dir, e.toString());
        }
        LOGGER.info("[DungeonTrain] Loot prefab registry loaded — {} prefab(s)", IDS.size());
    }

    public static synchronized void clear() {
        IDS.clear();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }

    private static String toJsonText(ContainerContentsPool pool) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        if (pool.fillMin() != 0) {
            sb.append("  \"fillMin\": ").append(pool.fillMin()).append(",\n");
        }
        if (pool.fillMax() != ContainerContentsPool.FILL_ALL) {
            sb.append("  \"fillMax\": ").append(pool.fillMax()).append(",\n");
        }
        sb.append("  \"entries\": [");
        boolean first = true;
        for (ContainerContentsEntry e : pool.entries()) {
            if (!first) sb.append(",");
            sb.append("\n    {")
                .append(" \"id\": \"").append(e.itemId().toString()).append("\",")
                .append(" \"count\": ").append(e.count()).append(",")
                .append(" \"weight\": ").append(e.weight())
                .append(" }");
            first = false;
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    /** Test helper. */
    public static synchronized void setIdsForTest(List<String> ids) {
        IDS.clear();
        for (String id : ids) IDS.add(id.toLowerCase(Locale.ROOT));
    }

    public static synchronized List<String> snapshotIds() {
        return Collections.unmodifiableList(new ArrayList<>(IDS));
    }
}
