package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTemplate;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Three-tier carriage template store, keyed on {@link CarriageVariant}.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/templates/<id>.nbt}, the
 *       per-install override. Server admins / single-player customisations land here.
 *       Custom variants only exist at this tier — they have no bundled resource.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/templates/<id>.nbt} on the
 *       classpath. Ships inside the mod jar and represents the mod's defaults.
 *       Only built-ins have bundled copies.</li>
 *   <li><b>Hardcoded fallback</b> — {@link CarriageTemplate#placeAt} drops to its
 *       legacy generator when both above tiers miss. Only built-ins fall back;
 *       custom variants with no config-dir file place nothing.</li>
 * </ol>
 *
 * Each tier is filtered against the caller's {@link CarriageDims}; templates
 * whose recorded footprint doesn't match the world's dims are skipped so the
 * next tier (or the hardcoded generator) gets a turn.
 *
 * Authors push edits from the editor to tier 2 either by toggling dev mode
 * ({@link EditorDevMode}) so {@link CarriageEditor#save} writes through to the
 * source tree, or by running {@code /dungeontrain editor promote}. Both paths
 * only apply to built-ins.
 */
public final class CarriageTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/templates";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    // Cached per-id result — empty optional means "tried all tiers and nothing
    // found", which short-circuits future spawns without re-reading.
    private static final Map<String, Optional<StructureTemplate>> CACHE = new HashMap<>();

    private CarriageTemplateStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(CarriageVariant variant) {
        return directory().resolve(variant.id() + EXT);
    }

    public static Path fileForId(String id) {
        return directory().resolve(id + EXT);
    }

    /**
     * On-disk path to the bundled-resource copy of {@code type}'s template
     * inside the project source tree. Only meaningful in a dev checkout — see
     * {@link #sourceTreeAvailable()}. Only built-ins ship bundled copies.
     */
    public static Path sourceFileFor(CarriageType type) {
        return sourceDirectory().resolve(name(type) + EXT);
    }

    /**
     * True iff the project source tree is on disk and writable. Distinguishes
     * a {@code ./gradlew runClient} dev session (where {@link FMLPaths#GAMEDIR}
     * resolves to {@code <project>/run}) from a packaged jar install (where
     * the source tree does not exist alongside the game directory).
     */
    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    public static synchronized void reload() {
        CACHE.clear();
    }

    /**
     * Invalidate the whole cache. Wired to {@code ServerStoppedEvent} so a
     * switch between two single-player worlds with different
     * {@link CarriageDims} cannot accidentally return a stale template
     * saved against the previous world's dims.
     */
    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static synchronized Optional<StructureTemplate> get(ServerLevel level, CarriageVariant variant, CarriageDims dims) {
        String key = variant.id();
        Optional<StructureTemplate> cached = CACHE.get(key);
        if (cached != null) return filterForDims(variant, cached, dims);
        Optional<StructureTemplate> loaded = loadFromConfig(level, variant, dims);
        if (loaded.isEmpty()) {
            // Bundled fallback applies to both built-ins and any custom ids
            // declared in the shipped customs manifest. For built-ins the jar
            // always ships a default; for customs the jar may ship a default.
            // If neither tier has the template, the caller falls back to the
            // hardcoded generator (built-ins only) or no-ops (customs).
            loaded = loadFromResource(level, variant, dims);
        }
        if (loaded.isEmpty()) {
            LOGGER.warn("[DungeonTrain] No template found for variant={} in config or bundled — caller will fall back (legacy for built-ins, empty for customs).",
                variant.id());
        }
        CACHE.put(key, loaded);
        return loaded;
    }

    /**
     * Re-check a cached template against the caller's dims — the cache is
     * populated once per world but a hot-reloaded world could in principle
     * change dims mid-session (future editor feature). Returning empty if
     * the dims no longer match falls the caller back to {@code legacyPlaceAt}
     * for built-ins, or to a no-op for customs.
     */
    private static Optional<StructureTemplate> filterForDims(CarriageVariant variant, Optional<StructureTemplate> cached, CarriageDims dims) {
        if (cached.isEmpty()) return cached;
        if (CarriageTemplate.sizeMatches(cached.get().getSize(), dims)) return cached;
        LOGGER.warn(
            "[DungeonTrain] Cached template {} no longer matches dims {}x{}x{} — falling back.",
            variant.id(), dims.length(), dims.width(), dims.height());
        return Optional.empty();
    }

    /** Write {@code template} to the per-install config-dir copy. */
    public static synchronized void save(CarriageVariant variant, StructureTemplate template) throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(variant);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        CACHE.put(variant.id(), Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved template {} to {}", variant.id(), file);
    }

    /**
     * Write {@code template} to the source-tree copy that gets bundled into the
     * mod jar at build time. Only succeeds in a dev checkout where the source
     * tree is on disk and writable. Only built-in variants have bundled slots.
     */
    public static synchronized void saveToSource(CarriageType type, StructureTemplate template) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileFor(type);
        Files.createDirectories(file.getParent());
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        LOGGER.info("[DungeonTrain] Wrote bundled template {} to {}", type, file);
    }

    /**
     * Copy the per-install config-dir copy of {@code type} into the source tree
     * so it ships with the next build. Only built-ins can be promoted.
     */
    public static synchronized void promote(CarriageType type) throws IOException {
        CarriageVariant variant = CarriageVariant.of(type);
        Path src = fileFor(variant);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved template for " + name(type) + " in " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFileFor(type);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted template {} from {} to {}", type, src, dst);
    }

    public static synchronized boolean delete(CarriageVariant variant) throws IOException {
        Path file = fileFor(variant);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(variant.id(), Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted template {} ({})", variant.id(), file);
        return existed;
    }

    public static boolean exists(CarriageVariant variant) {
        return Files.isRegularFile(fileFor(variant));
    }

    /**
     * True iff the mod jar bundles a default template for {@code variant}.
     * Custom variants never have bundled copies.
     */
    public static boolean bundled(CarriageVariant variant) {
        if (!(variant instanceof CarriageVariant.Builtin)) return false;
        try (InputStream in = CarriageTemplateStore.class.getResourceAsStream(RESOURCE_PREFIX + variant.id() + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Move a custom template file from {@code sourceId} to {@code targetId}.
     * Used by the editor's save-with-rename path for custom-to-custom
     * renames. Returns false if the source file does not exist.
     */
    public static synchronized boolean rename(String sourceId, String targetId) throws IOException {
        Path src = fileForId(sourceId);
        Path dst = fileForId(targetId);
        if (!Files.isRegularFile(src)) return false;
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Optional<StructureTemplate> cached = CACHE.remove(sourceId);
        if (cached != null) CACHE.put(targetId, cached);
        LOGGER.info("[DungeonTrain] Renamed template file {} -> {}", src, dst);
        return true;
    }

    private static Optional<StructureTemplate> loadFromConfig(ServerLevel level, CarriageVariant variant, CarriageDims dims) {
        Path file = fileFor(variant);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            return loadAndValidate(level, variant.id(), dims, tag, "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read config template {} at {}: {}", variant.id(), file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadFromResource(ServerLevel level, CarriageVariant variant, CarriageDims dims) {
        String resource = RESOURCE_PREFIX + variant.id() + EXT;
        try (InputStream in = CarriageTemplateStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            CompoundTag tag = NbtIo.readCompressed(in);
            return loadAndValidate(level, variant.id(), dims, tag, "bundled " + resource);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled template {} at {}: {}", variant.id(), resource, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadAndValidate(
        ServerLevel level, String id, CarriageDims dims, CompoundTag tag, String origin
    ) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, tag);

        Vec3i size = template.getSize();
        if (!CarriageTemplate.sizeMatches(size, dims)) {
            LOGGER.warn(
                "[DungeonTrain] Template {} ({}) has bounds {}x{}x{}, expected {}x{}x{} — ignoring.",
                id, origin, size.getX(), size.getY(), size.getZ(),
                dims.length(), dims.height(), dims.width()
            );
            return Optional.empty();
        }
        LOGGER.info("[DungeonTrain] Loaded template {} from {}", id, origin);
        return Optional.of(template);
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

    private static String name(CarriageType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
