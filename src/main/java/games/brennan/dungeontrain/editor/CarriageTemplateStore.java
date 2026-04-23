package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageTemplate;
import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
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
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Three-tier carriage template store.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/templates/<type>.nbt}, the
 *       per-install override. Server admins / single-player customisations land here.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/templates/<type>.nbt} on the
 *       classpath. Ships inside the mod jar and represents the mod's defaults.</li>
 *   <li><b>Hardcoded fallback</b> — {@link CarriageTemplate#placeAt} drops to its
 *       legacy generator when both above tiers miss.</li>
 * </ol>
 *
 * Each tier is filtered against the caller's {@link CarriageDims}; templates
 * whose recorded footprint doesn't match the world's dims are skipped so the
 * next tier (or the hardcoded generator) gets a turn.
 *
 * Authors push edits from the editor to tier 2 either by toggling dev mode
 * ({@link EditorDevMode}) so {@link CarriageEditor#save} writes through to the
 * source tree, or by running {@code /dungeontrain editor promote}.
 */
public final class CarriageTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/templates";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    private static final Map<CarriageType, Optional<StructureTemplate>> CACHE =
        new EnumMap<>(CarriageType.class);

    private CarriageTemplateStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(CarriageType type) {
        return directory().resolve(name(type) + EXT);
    }

    /**
     * On-disk path to the bundled-resource copy of {@code type}'s template
     * inside the project source tree. Only meaningful in a dev checkout — see
     * {@link #sourceTreeAvailable()}.
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

    public static synchronized Optional<StructureTemplate> get(ServerLevel level, CarriageType type, CarriageDims dims) {
        Optional<StructureTemplate> cached = CACHE.get(type);
        if (cached != null) return filterForDims(type, cached, dims);
        Optional<StructureTemplate> loaded = loadFromConfig(level, type, dims);
        if (loaded.isEmpty()) loaded = loadFromResource(level, type, dims);
        CACHE.put(type, loaded);
        return loaded;
    }

    /**
     * Re-check a cached template against the caller's dims — the cache is
     * populated once per world but a hot-reloaded world could in principle
     * change dims mid-session (future editor feature). Returning empty if
     * the dims no longer match falls the caller back to {@code legacyPlaceAt}.
     */
    private static Optional<StructureTemplate> filterForDims(CarriageType type, Optional<StructureTemplate> cached, CarriageDims dims) {
        if (cached.isEmpty()) return cached;
        if (CarriageTemplate.sizeMatches(cached.get().getSize(), dims)) return cached;
        LOGGER.warn(
            "[DungeonTrain] Cached template {} no longer matches dims {}x{}x{} — falling back.",
            type, dims.length(), dims.width(), dims.height());
        return Optional.empty();
    }

    /** Write {@code template} to the per-install config-dir copy. */
    public static synchronized void save(CarriageType type, StructureTemplate template) throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(type);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        CACHE.put(type, Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved template {} to {}", type, file);
    }

    /**
     * Write {@code template} to the source-tree copy that gets bundled into the
     * mod jar at build time. Only succeeds in a dev checkout where the source
     * tree is on disk and writable.
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
     * so it ships with the next build.
     */
    public static synchronized void promote(CarriageType type) throws IOException {
        Path src = fileFor(type);
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

    public static synchronized boolean delete(CarriageType type) throws IOException {
        Path file = fileFor(type);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(type, Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted template {} ({})", type, file);
        return existed;
    }

    public static boolean exists(CarriageType type) {
        return Files.isRegularFile(fileFor(type));
    }

    /** True iff the mod jar bundles a default template for {@code type}. */
    public static boolean bundled(CarriageType type) {
        try (InputStream in = CarriageTemplateStore.class.getResourceAsStream(RESOURCE_PREFIX + name(type) + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<StructureTemplate> loadFromConfig(ServerLevel level, CarriageType type, CarriageDims dims) {
        Path file = fileFor(type);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            return loadAndValidate(level, type, dims, tag, "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read config template {} at {}: {}", type, file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadFromResource(ServerLevel level, CarriageType type, CarriageDims dims) {
        String resource = RESOURCE_PREFIX + name(type) + EXT;
        try (InputStream in = CarriageTemplateStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            CompoundTag tag = NbtIo.readCompressed(in);
            return loadAndValidate(level, type, dims, tag, "bundled " + resource);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled template {} at {}: {}", type, resource, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadAndValidate(
        ServerLevel level, CarriageType type, CarriageDims dims, CompoundTag tag, String origin
    ) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, tag);

        Vec3i size = template.getSize();
        if (!CarriageTemplate.sizeMatches(size, dims)) {
            LOGGER.warn(
                "[DungeonTrain] Template {} ({}) has bounds {}x{}x{}, expected {}x{}x{} — ignoring.",
                type, origin, size.getX(), size.getY(), size.getZ(),
                dims.length(), dims.height(), dims.width()
            );
            return Optional.empty();
        }
        LOGGER.info("[DungeonTrain] Loaded template {} from {}", type, origin);
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
