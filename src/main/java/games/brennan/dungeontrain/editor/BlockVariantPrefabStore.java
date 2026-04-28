package games.brennan.dungeontrain.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.util.BundledNbtScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Named library of per-cell block-variant snippets.
 *
 * <p>Each prefab is a {@code List<VariantState>} (the same shape a single
 * cell holds in {@link CarriageVariantBlocks}). Three storage tiers — mirrors
 * {@link games.brennan.dungeontrain.track.variant.TrackVariantStore}:
 *
 * <ol>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/prefabs/block_variants/<id>.json}
 *       on the classpath. Shipped with the mod jar; survives fresh installs.
 *       Authored in dev mode via {@link #saveToSource}.</li>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/prefabs/block_variants/<id>.json}.
 *       Per-install override; written by {@link #save} on every saver action so
 *       the just-saved prefab is immediately reachable in this session
 *       (dev-mode classpath reads via {@code copyIdeResources=true} lag the
 *       source-tree write until the next gradle resources sync).</li>
 *   <li><b>Empty</b> — caller falls back to "no states for this id" silently.</li>
 * </ol>
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

    static final String BUNDLED_RESOURCE_PREFIX = "/data/dungeontrain/prefabs/block_variants/";
    static final String SOURCE_RELATIVE_PATH = "src/main/resources/data/dungeontrain/prefabs/block_variants";

    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    private static final TreeSet<String> IDS = new TreeSet<>();

    private BlockVariantPrefabStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(String id) {
        return directory().resolve(id + EXT);
    }

    public static String bundledResourceFor(String id) {
        return BUNDLED_RESOURCE_PREFIX + id + EXT;
    }

    public static Path sourceFileFor(String id) {
        return sourceDirectory().resolve(id + EXT);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
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
     * Load a prefab's states list. Tries config-dir first (per-install
     * override), then the bundled classpath resource. Returns empty if the id
     * is unknown, the file is missing, or parsing fails.
     */
    public static synchronized Optional<List<VariantState>> load(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        Optional<List<VariantState>> fromConfig = loadFromConfig(key);
        if (fromConfig.isPresent()) return fromConfig;
        return loadFromResource(key);
    }

    /**
     * Save (or overwrite) a prefab to the per-install config dir. Returns true
     * if the id was new in {@link #IDS}, false on overwrite. Caller is
     * responsible for client sync after this lands.
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

    /**
     * Write {@code states} directly into the source tree at
     * {@link #SOURCE_RELATIVE_PATH} so the JSON file is committed via git and
     * shipped on the next mod build. Throws if the source tree isn't writable
     * (i.e. running from a packaged jar instead of {@code ./gradlew runClient}
     * in a checkout). Mirrors
     * {@link games.brennan.dungeontrain.track.variant.TrackVariantStore#saveToSource}.
     */
    public static synchronized void saveToSource(String id, List<VariantState> states) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        if (!isValidName(id)) {
            throw new IOException("Invalid prefab name '" + id + "' — must match " + NAME_PATTERN.pattern());
        }
        if (states == null || states.size() < CarriageVariantBlocks.MIN_STATES_PER_ENTRY) {
            throw new IOException("Block-variant prefab needs at least "
                + CarriageVariantBlocks.MIN_STATES_PER_ENTRY + " states");
        }
        String key = id.toLowerCase(Locale.ROOT);
        Path file = sourceFileFor(key);
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(toJsonText(states));
        }
        LOGGER.info("[DungeonTrain] Wrote bundled block-variant prefab '{}' to {}", key, file);
    }

    /**
     * Copy a previously-saved config-dir prefab into the source tree. Throws
     * if no config copy exists for {@code id} or the source tree isn't writable.
     */
    public static synchronized void promote(String id) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        Path src = fileFor(key);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved block-variant prefab '" + key + "' in config dir " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFileFor(key);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted block-variant prefab '{}' from {} to {}", key, src, dst);
    }

    /**
     * Delete the per-install config-dir copy of a prefab. If a bundled prefab
     * with the same id exists, the id stays in {@link #IDS} (jar is read-only;
     * we can't remove the bundled file). Returns true if a config-dir file was
     * actually removed.
     */
    public static synchronized boolean delete(String id) throws IOException {
        String key = id.toLowerCase(Locale.ROOT);
        Path file = fileFor(key);
        boolean fileExisted = Files.deleteIfExists(file);
        // If a bundled copy still exists, keep the id registered so the
        // bundled prefab is exposed again. Only drop the id when neither tier
        // has it.
        if (!hasBundled(key)) {
            IDS.remove(key);
        }
        if (fileExisted) {
            LOGGER.info("[DungeonTrain] Deleted block-variant prefab '{}' (bundled retained: {})",
                key, hasBundled(key));
        }
        return fileExisted;
    }

    /**
     * Whether a bundled prefab with this id exists on the classpath. Used by
     * {@link #delete} to decide whether to keep the id in {@link #IDS} after a
     * config-dir delete.
     */
    public static boolean hasBundled(String id) {
        try (InputStream in = BlockVariantPrefabStore.class.getResourceAsStream(bundledResourceFor(id))) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether a prefab id is "committed to repo" — either the source-tree JSON
     * exists on disk (saved with dev mode on, awaiting next gradle resources
     * sync to enter the classpath) or the bundled jar already ships it.
     * Drives the creative-menu tint: uncommitted prefabs (config-dir only)
     * render with a different slot background so the dev knows what's
     * ephemeral. {@code false} on hosted servers (no source tree) for any
     * prefab not pre-shipped in the jar.
     */
    public static boolean isCommitted(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (hasBundled(key)) return true;
        try {
            return sourceTreeAvailable() && Files.exists(sourceFileFor(key));
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized void reload() {
        IDS.clear();
        // Bundled (classpath) tier — shipped with the mod jar
        Set<String> bundled = BundledNbtScanner.scanBasenames(
            BlockVariantPrefabStore.class, BUNDLED_RESOURCE_PREFIX, LOGGER, EXT);
        for (String name : bundled) {
            if (!isValidName(name)) {
                LOGGER.warn("[DungeonTrain] Ignoring bundled block-variant prefab with invalid name: {}", name);
                continue;
            }
            IDS.add(name);
        }
        // Config-dir tier — per-install override / new authoring
        Path dir = directory();
        if (Files.isDirectory(dir)) {
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
        }
        LOGGER.info("[DungeonTrain] Block-variant prefab registry loaded — {} prefab(s) ({} bundled)",
            IDS.size(), bundled.size());
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

    private static Optional<List<VariantState>> loadFromConfig(String key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parseStates(r, key, "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read block-variant prefab '{}' from config: {}", key, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<List<VariantState>> loadFromResource(String key) {
        String resource = bundledResourceFor(key);
        try (InputStream in = BlockVariantPrefabStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parseStates(r, key, "bundled " + resource);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled block-variant prefab '{}': {}", key, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<List<VariantState>> parseStates(Reader reader, String key, String origin) {
        JsonElement root = JsonParser.parseReader(reader);
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
            LOGGER.warn("[DungeonTrain] Block-variant prefab '{}' ({}) has fewer than {} valid states — ignoring.",
                key, origin, CarriageVariantBlocks.MIN_STATES_PER_ENTRY);
            return Optional.empty();
        }
        return Optional.of(List.copyOf(states));
    }

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

    private static Path sourceDirectory() {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) {
            throw new IllegalStateException(
                "Cannot resolve source directory — FMLPaths.GAMEDIR has no parent."
            );
        }
        return projectRoot.resolve(SOURCE_RELATIVE_PATH);
    }

    private static Path resourcesRootOrNull() {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) return null;
        return projectRoot.resolve("src/main/resources");
    }

    private static Path projectRootOrNull() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        return gameDir.getParent();
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
