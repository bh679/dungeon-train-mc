package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import javax.annotation.Nullable;
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
 * Named library of per-cell block-variant snippets.
 *
 * <p>Each prefab is a {@code List<VariantState>} (the same shape a single
 * cell holds in {@link CarriageVariantBlocks}). Files live at
 * {@code config/dungeontrain/prefabs/block_variants/<id>.json} so authors
 * can hand-edit, copy between worlds, or commit to source control.</p>
 *
 * <p>Combines registry + store responsibilities: scans the directory on
 * {@link ServerStartingEvent}, drops cache on {@link ServerStoppedEvent},
 * exposes {@link #allIds()} / {@link #load(String)} / {@link #save(String, List)} /
 * {@link #delete(String)} as the single authority.</p>
 *
 * <p>Schema:
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "states": [
 *     "minecraft:stone",
 *     { "state": "minecraft:cobblestone", "weight": 3 }
 *   ]
 * }
 * </pre>
 * The {@code states} array uses the same per-element shape as the
 * cell-level sidecar, parsed via {@link CarriageVariantBlocks#parseVariantElement}
 * and serialised via {@link CarriageVariantBlocks#appendVariantJson}.</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlockVariantPrefabStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String SUBDIR = "dungeontrain/prefabs/block_variants";
    private static final String EXT = ".json";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    private static final TreeSet<String> IDS = new TreeSet<>();

    private BlockVariantPrefabStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(String id) {
        return directory().resolve(id + EXT);
    }

    /** Sorted snapshot of registered prefab ids. */
    public static synchronized List<String> allIds() {
        return new ArrayList<>(IDS);
    }

    public static synchronized boolean contains(String id) {
        return IDS.contains(id.toLowerCase(Locale.ROOT));
    }

    /** Validate a candidate name against {@link #NAME_PATTERN}. */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Load a prefab's states list from disk. Returns empty if the id is
     * unregistered, the file is missing, or parsing fails.
     */
    public static synchronized Optional<List<VariantState>> load(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) return Optional.empty();
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("states") || !obj.get("states").isJsonArray()) return Optional.empty();
            JsonArray arr = obj.getAsJsonArray("states");
            HolderLookup.RegistryLookup<net.minecraft.world.level.block.Block> blocks =
                BuiltInRegistries.BLOCK.asLookup();
            List<VariantState> states = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                VariantState parsed = CarriageVariantBlocks.parseVariantElement(
                    el, blocks, key, BlockPos.ZERO);
                if (parsed != null) states.add(parsed);
            }
            if (states.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
                LOGGER.warn("[DungeonTrain] Block-variant prefab '{}' has fewer than {} valid states — ignoring.",
                    key, CarriageVariantBlocks.MIN_STATES_PER_ENTRY);
                return Optional.empty();
            }
            return Optional.of(List.copyOf(states));
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read block-variant prefab '{}': {}", key, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Save (or overwrite) a prefab. Returns true if the id was new, false on
     * overwrite. Caller is responsible for client sync after this lands.
     */
    public static synchronized boolean save(String id, List<VariantState> states) throws IOException {
        if (!isValidName(id)) {
            throw new IOException("Invalid prefab name '" + id + "' — must match " + NAME_PATTERN.pattern());
        }
        if (states == null || states.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
            throw new IOException("Block-variant prefab needs at least "
                + CarriageVariantBlocks.MIN_STATES_PER_ENTRY + " states");
        }
        String key = id.toLowerCase(Locale.ROOT);
        Path file = fileFor(key);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText(states));
        }
        boolean isNew = IDS.add(key);
        LOGGER.info("[DungeonTrain] {} block-variant prefab '{}' ({} states) at {}",
            isNew ? "Saved new" : "Overwrote", key, states.size(), file);
        return isNew;
    }

    public static synchronized boolean delete(String id) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        Path file = fileFor(key);
        boolean fileExisted = Files.deleteIfExists(file);
        IDS.remove(key);
        if (fileExisted) {
            LOGGER.info("[DungeonTrain] Deleted block-variant prefab '{}'", key);
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
                    LOGGER.warn("[DungeonTrain] Ignoring block-variant prefab file with invalid name: {}", file);
                    continue;
                }
                IDS.add(basename);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan block-variant prefab dir {}: {}", dir, e.toString());
        }
        LOGGER.info("[DungeonTrain] Block-variant prefab registry loaded — {} prefab(s)", IDS.size());
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

    @Nullable
    private static String toJsonText(List<VariantState> states) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(CURRENT_SCHEMA_VERSION).append(",\n");
        sb.append("  \"states\": [");
        boolean first = true;
        for (VariantState s : states) {
            if (!first) sb.append(",");
            sb.append("\n    ");
            CarriageVariantBlocks.appendVariantJson(sb, s);
            first = false;
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    /** Test helper: replace the in-memory id list (does not touch disk). */
    public static synchronized void setIdsForTest(List<String> ids) {
        IDS.clear();
        for (String id : ids) IDS.add(id.toLowerCase(Locale.ROOT));
    }

    /** Snapshot for serialization (defensive copy). */
    public static synchronized List<String> snapshotIds() {
        return Collections.unmodifiableList(new ArrayList<>(IDS));
    }
}
