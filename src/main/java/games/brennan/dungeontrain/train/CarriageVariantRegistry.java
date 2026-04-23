package games.brennan.dungeontrain.train;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

/**
 * Ordered list of all carriage variants available for spawning and editing.
 * The four {@link CarriageType} built-ins come first in declared enum order;
 * custom variants follow in alphabetical order by id.
 *
 * <p>Custom variants are discovered from two tiers at server start:
 * <ol>
 *   <li><b>Bundled manifest</b> — {@code /data/dungeontrain/templates/customs.json}
 *       on the classpath lists the ids that ship with the mod jar. These always
 *       load on a fresh install and pair with the {@code loadFromResource}
 *       fallback in {@link CarriageTemplateStore}.</li>
 *   <li><b>Config directory</b> — {@link CarriageTemplateStore#directory()}
 *       scanned for {@code .nbt} files whose basenames are not built-in ids.
 *       Entries here cover both player-authored customs and local overrides of
 *       shipped customs (same id is deduplicated by the underlying TreeSet).</li>
 * </ol>
 * The registry holds only the identifiers — the template bytes stay in
 * {@link CarriageTemplateStore}. Adding or removing a {@code .nbt} file
 * without going through the editor requires a server restart (or
 * {@link #reload()}) to pick up.
 *
 * <p>Wired to {@link ServerStartingEvent} for the scan and
 * {@link ServerStoppedEvent} to release state between worlds.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CarriageVariantRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<CarriageVariant> BUILTINS;
    static {
        List<CarriageVariant> list = new ArrayList<>();
        for (CarriageType t : CarriageType.values()) list.add(CarriageVariant.of(t));
        BUILTINS = List.copyOf(list);
    }

    /** Sorted custom variant names. Mutations go through register/unregister/reload. */
    private static final TreeSet<String> CUSTOMS = new TreeSet<>();

    private CarriageVariantRegistry() {}

    /**
     * Every variant, built-ins first (enum order), then customs alphabetical.
     * The returned list is a snapshot — safe to iterate without locking even if
     * another thread mutates the registry.
     */
    public static synchronized List<CarriageVariant> allVariants() {
        List<CarriageVariant> all = new ArrayList<>(BUILTINS.size() + CUSTOMS.size());
        all.addAll(BUILTINS);
        for (String name : CUSTOMS) all.add(new CarriageVariant.Custom(name));
        return all;
    }

    public static synchronized List<CarriageVariant> builtins() {
        return BUILTINS;
    }

    /**
     * Look up a variant by id. Returns empty if {@code id} is neither a
     * built-in nor a registered custom.
     */
    public static synchronized Optional<CarriageVariant> find(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        for (CarriageType t : CarriageType.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(key)) {
                return Optional.of(CarriageVariant.of(t));
            }
        }
        if (CUSTOMS.contains(key)) return Optional.of(new CarriageVariant.Custom(key));
        return Optional.empty();
    }

    /**
     * Register a new custom variant. Returns false if a variant with the same
     * id is already present (built-in or custom). Caller is responsible for
     * writing the backing .nbt file before calling this.
     */
    public static synchronized boolean register(CarriageVariant.Custom variant) {
        if (CarriageVariant.isReservedBuiltinName(variant.name())) return false;
        return CUSTOMS.add(variant.name());
    }

    /**
     * Remove a custom variant from the registry. Built-ins can't be
     * unregistered. Returns false if the id was not a registered custom.
     * Caller is responsible for deleting the backing .nbt file.
     */
    public static synchronized boolean unregister(String id) {
        if (CarriageVariant.isReservedBuiltinName(id)) return false;
        return CUSTOMS.remove(id.toLowerCase(Locale.ROOT));
    }

    /** Classpath location of the shipped-customs manifest. */
    static final String BUNDLED_MANIFEST_RESOURCE = "/data/dungeontrain/templates/customs.json";

    /** Reload custom variants from the bundled manifest + the per-install config dir. */
    public static synchronized void reload() {
        CUSTOMS.clear();
        int bundled = loadBundledManifest();
        int config = loadConfigDir();

        LOGGER.info("[DungeonTrain] Carriage variant registry loaded — {} built-in + {} custom ({} bundled, {} config)",
            BUILTINS.size(), CUSTOMS.size(), bundled, config);
    }

    /**
     * Read the shipped manifest at {@link #BUNDLED_MANIFEST_RESOURCE} and add
     * each valid id to {@link #CUSTOMS}. Returns the count of ids added (for
     * logging). Silently accepts a missing manifest — the file is optional.
     */
    private static int loadBundledManifest() {
        try (InputStream in = CarriageVariantRegistry.class.getResourceAsStream(BUNDLED_MANIFEST_RESOURCE)) {
            if (in == null) return 0;
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) {
                LOGGER.warn("[DungeonTrain] {} is not a JSON array — ignoring", BUNDLED_MANIFEST_RESOURCE);
                return 0;
            }
            JsonArray arr = root.getAsJsonArray();
            int added = 0;
            for (JsonElement el : arr) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    LOGGER.warn("[DungeonTrain] Skipping non-string manifest entry: {}", el);
                    continue;
                }
                String id = el.getAsString().toLowerCase(Locale.ROOT);
                if (!acceptCustomId(id, "bundled manifest")) continue;
                if (CUSTOMS.add(id)) added++;
            }
            return added;
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled manifest {}: {}", BUNDLED_MANIFEST_RESOURCE, e.toString());
            return 0;
        }
    }

    /**
     * Scan the per-install config directory for {@code .nbt} files and add
     * their basenames as customs. Returns the count of ids added that weren't
     * already present (duplicates from the bundled manifest are fine — same id
     * already resolves to the config-dir copy first in {@code CarriageTemplateStore}).
     */
    private static int loadConfigDir() {
        Path dir = CarriageTemplateStore.directory();
        if (!Files.isDirectory(dir)) return 0;

        int added = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".nbt")) continue;
                String basename = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                if (CarriageVariant.isReservedBuiltinName(basename)) continue;
                if (!acceptCustomId(basename, "config dir " + file.getFileName())) continue;
                if (CUSTOMS.add(basename)) added++;
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan templates directory {}: {}", dir, e.toString());
        }
        return added;
    }

    /**
     * Validate a candidate custom id against the name pattern and the reserved
     * built-in namespace. Emits a warning on rejection so stray entries surface
     * in the log rather than silently disappearing.
     */
    private static boolean acceptCustomId(String id, String origin) {
        if (CarriageVariant.isReservedBuiltinName(id)) {
            LOGGER.warn("[DungeonTrain] Ignoring custom '{}' from {} — collides with built-in name", id, origin);
            return false;
        }
        if (!CarriageVariant.NAME_PATTERN.matcher(id).matches()) {
            LOGGER.warn("[DungeonTrain] Ignoring custom '{}' from {} — invalid name", id, origin);
            return false;
        }
        return true;
    }

    public static synchronized void clear() {
        CUSTOMS.clear();
    }

    public static synchronized List<String> customIds() {
        return Collections.unmodifiableList(new ArrayList<>(CUSTOMS));
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
