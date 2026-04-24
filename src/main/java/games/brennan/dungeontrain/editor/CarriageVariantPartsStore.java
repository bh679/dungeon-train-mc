package games.brennan.dungeontrain.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Three-tier store for {@code <variant-id>.parts.json} sidecars — the
 * per-carriage-variant mapping of {@link games.brennan.dungeontrain.train.CarriagePartKind}
 * → part template name that tells
 * {@link games.brennan.dungeontrain.train.CarriageTemplate#placeAt(net.minecraft.server.level.ServerLevel, net.minecraft.core.BlockPos, CarriageVariant, games.brennan.dungeontrain.train.CarriageDims)}
 * to compose the carriage from parts instead of stamping the monolithic NBT.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/templates/<id>.parts.json}.
 *       Per-install override; the editor's {@code part set/clear} commands
 *       write here.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/templates/<id>.parts.json}
 *       on the classpath. Shipped defaults for built-in variants that the mod
 *       wants to render via parts out-of-the-box. Optional — absent means the
 *       variant falls through to the monolithic path.</li>
 *   <li><b>Absent</b> — no sidecar in either tier. Caller renders the
 *       monolithic NBT (existing behaviour).</li>
 * </ol>
 *
 * <p>Lives in the same directory as carriage NBTs because a sidecar always
 * accompanies a specific carriage variant; keeping them together makes the
 * config dir self-explanatory and the {@code .parts.json} suffix keeps
 * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry}'s
 * {@code *.nbt}-only scan from treating sidecars as custom carriages.</p>
 */
public final class CarriageVariantPartsStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/templates";
    private static final String EXT = ".parts.json";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    // Per-id cache; Optional.empty() means "both tiers missing", which short-
    // circuits future lookups. Cleared on ServerStopped via CarriagePartRegistry.
    private static final Map<String, Optional<CarriagePartAssignment>> CACHE = new HashMap<>();

    private CarriageVariantPartsStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(CarriageVariant variant) {
        return directory().resolve(variant.id() + EXT);
    }

    public static Path sourceFileFor(CarriageType type) {
        return sourceDirectory().resolve(type.name().toLowerCase(Locale.ROOT) + EXT);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static synchronized void invalidate(String id) {
        CACHE.remove(id.toLowerCase(Locale.ROOT));
    }

    public static synchronized Optional<CarriagePartAssignment> get(CarriageVariant variant) {
        String key = variant.id();
        Optional<CarriagePartAssignment> cached = CACHE.get(key);
        if (cached != null) return cached;
        Optional<CarriagePartAssignment> loaded = loadFromConfig(variant);
        if (loaded.isEmpty()) loaded = loadFromResource(variant);
        CACHE.put(key, loaded);
        return loaded;
    }

    public static synchronized void save(CarriageVariant variant, CarriagePartAssignment assignment) throws IOException {
        Files.createDirectories(directory());
        Path file = fileFor(variant);
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(assignment.toJson());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }
        CACHE.put(variant.id(), Optional.of(assignment));
        LOGGER.info("[DungeonTrain] Saved parts assignment for {} to {}", variant.id(), file);
    }

    public static synchronized void saveToSource(CarriageType type, CarriagePartAssignment assignment) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileFor(type);
        Files.createDirectories(file.getParent());
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(assignment.toJson());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }
        LOGGER.info("[DungeonTrain] Wrote bundled parts assignment {} to {}", type, file);
    }

    public static synchronized boolean delete(CarriageVariant variant) throws IOException {
        Path file = fileFor(variant);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(variant.id(), Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted parts assignment for {} ({})", variant.id(), file);
        return existed;
    }

    public static boolean exists(CarriageVariant variant) {
        return Files.isRegularFile(fileFor(variant));
    }

    public static boolean bundled(CarriageVariant variant) {
        try (InputStream in = CarriageVariantPartsStore.class.getResourceAsStream(
                RESOURCE_PREFIX + variant.id() + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<CarriagePartAssignment> loadFromConfig(CarriageVariant variant) {
        Path file = fileFor(variant);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Parts assignment {} is not a JSON object — ignoring", file);
                return Optional.empty();
            }
            CarriagePartAssignment a = CarriagePartAssignment.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded parts assignment {} from config {}", variant.id(), file);
            return Optional.of(a);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read parts assignment {} at {}: {}",
                variant.id(), file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<CarriagePartAssignment> loadFromResource(CarriageVariant variant) {
        String resource = RESOURCE_PREFIX + variant.id() + EXT;
        try (InputStream in = CarriageVariantPartsStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Bundled parts assignment {} is not a JSON object — ignoring", resource);
                return Optional.empty();
            }
            CarriagePartAssignment a = CarriagePartAssignment.fromJson(root.getAsJsonObject());
            LOGGER.info("[DungeonTrain] Loaded parts assignment {} from bundled {}", variant.id(), resource);
            return Optional.of(a);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled parts assignment {}: {}",
                resource, e.toString());
            return Optional.empty();
        }
    }

    private static Path sourceDirectory() {
        Path dir = sourceDirectoryOrNull();
        if (dir == null) {
            throw new IllegalStateException(
                "Cannot resolve source directory — FMLPaths.GAMEDIR has no parent."
            );
        }
        return dir;
    }

    private static Path sourceDirectoryOrNull() {
        Path projectRoot = projectRootOrNull();
        if (projectRoot == null) return null;
        return projectRoot.resolve(SOURCE_REL_PATH);
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
}
