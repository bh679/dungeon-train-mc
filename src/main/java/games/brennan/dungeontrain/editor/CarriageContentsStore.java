package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Three-tier contents template store, keyed on {@link CarriageContents}.
 *
 * <p>Parallel to {@link CarriageTemplateStore} but scoped to the carriage
 * <i>interior</i>. Files cover only the interior volume
 * {@code (length-2) × (height-2) × (width-2)} — the shell floor/walls/ceiling
 * are never captured here and come from the shell store.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/contents/<id>.nbt},
 *       per-install override. Custom contents only exist at this tier.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/contents/<id>.nbt}
 *       on the classpath. Ships inside the mod jar and represents the mod's
 *       defaults.</li>
 *   <li><b>Hardcoded fallback</b> — {@link games.brennan.dungeontrain.train.CarriageContentsTemplate#placeAt}
 *       drops to a legacy generator (single stone pressure plate at floor
 *       centre) when both above tiers miss. Only the {@code default} built-in
 *       falls back; custom contents with no config-dir file place nothing.</li>
 * </ol>
 *
 * Each tier is filtered against the caller's interior dims; templates whose
 * recorded footprint doesn't match the world's current interior dims are
 * skipped so the next tier (or the hardcoded generator) gets a turn.
 */
public final class CarriageContentsStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/contents";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/contents/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/contents";

    private static final Map<String, Optional<StructureTemplate>> CACHE = new HashMap<>();

    private CarriageContentsStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(CarriageContents contents) {
        return directory().resolve(contents.id() + EXT);
    }

    public static Path fileForId(String id) {
        return directory().resolve(id + EXT);
    }

    /**
     * On-disk path to the bundled-resource copy of {@code contents}'s template
     * inside the project source tree. Only meaningful in a dev checkout — see
     * {@link #sourceTreeAvailable()}.
     */
    public static Path sourceFileFor(CarriageContents contents) {
        return sourceFileForId(contents.id());
    }

    /**
     * Like {@link #sourceFileFor(CarriageContents)} but takes a raw id string
     * — used by saveAs / rename flows to delete the outgoing-name source file
     * without first having to materialise a {@link CarriageContents}.
     */
    public static Path sourceFileForId(String id) {
        return sourceDirectory().resolve(id + EXT);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    public static synchronized void reload() {
        CACHE.clear();
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    /**
     * Look up the template for {@code contents} constrained by the world's
     * interior dims. Returns empty if nothing on disk matches — caller falls
     * back to the hardcoded default-contents generator for the {@code default}
     * built-in, or places nothing for customs.
     *
     * <p>{@code interiorDims} is the carriage dims with the shell subtracted:
     * {@code length-2 × height-2 × width-2}. Wrapped in a {@link CarriageDims}
     * only for reuse of the record; the shell minimums ({@code length>=4},
     * {@code width>=3}, {@code height>=3}) guarantee interior dims stay above
     * the {@link CarriageDims} floors of 4×3×3… except when width=3 (interior
     * width=1) — see the helper in {@link games.brennan.dungeontrain.train.CarriageContentsTemplate}
     * which uses a raw {@link Vec3i} instead of {@link CarriageDims} to avoid
     * the invariant.</p>
     */
    public static synchronized Optional<StructureTemplate> get(
        ServerLevel level, CarriageContents contents, Vec3i interiorSize
    ) {
        String key = contents.id();
        Optional<StructureTemplate> cached = CACHE.get(key);
        if (cached != null) return filterForSize(contents, cached, interiorSize);
        Optional<StructureTemplate> loaded = loadFromConfig(level, contents, interiorSize);
        if (loaded.isEmpty()) {
            loaded = loadFromResource(level, contents, interiorSize);
        }
        CACHE.put(key, loaded);
        return loaded;
    }

    private static Optional<StructureTemplate> filterForSize(
        CarriageContents contents, Optional<StructureTemplate> cached, Vec3i interiorSize
    ) {
        if (cached.isEmpty()) return cached;
        if (sizeMatches(cached.get().getSize(), interiorSize)) return cached;
        LOGGER.warn(
            "[DungeonTrain] Cached contents template {} no longer matches interior size {}x{}x{} — falling back.",
            contents.id(), interiorSize.getX(), interiorSize.getY(), interiorSize.getZ());
        return Optional.empty();
    }

    /** Write {@code template} to the per-install config-dir copy. */
    public static synchronized void save(CarriageContents contents, StructureTemplate template) throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(contents);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file);
        CACHE.put(contents.id(), Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved contents template {} to {}", contents.id(), file);
    }

    /**
     * Write {@code template} to the source-tree copy that gets bundled into
     * the mod jar at build time. Only succeeds in a dev checkout where the
     * source tree is on disk and writable.
     */
    public static synchronized void saveToSource(CarriageContents contents, StructureTemplate template) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileFor(contents);
        Files.createDirectories(file.getParent());
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file);
        LOGGER.info("[DungeonTrain] Wrote bundled contents template {} to {}", contents.id(), file);
    }

    /**
     * Copy the per-install config-dir copy of {@code contents} into the source
     * tree so it ships with the next build. Works for both built-ins and
     * customs — unlike shell promotion which is built-in only — because
     * contents customs are first-class shippable assets.
     */
    public static synchronized void promote(CarriageContents contents) throws IOException {
        Path src = fileFor(contents);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved contents template for " + contents.id() + " in " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFileFor(contents);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted contents template {} from {} to {}", contents.id(), src, dst);
    }

    public static synchronized boolean delete(CarriageContents contents) throws IOException {
        Path file = fileFor(contents);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(contents.id(), Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted contents template {} ({})", contents.id(), file);
        return existed;
    }

    public static boolean exists(CarriageContents contents) {
        return Files.isRegularFile(fileFor(contents));
    }

    /** True iff the mod jar bundles a default template for {@code contents}. */
    public static boolean bundled(CarriageContents contents) {
        try (InputStream in = CarriageContentsStore.class.getResourceAsStream(RESOURCE_PREFIX + contents.id() + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static synchronized boolean rename(String sourceId, String targetId) throws IOException {
        Path src = fileForId(sourceId);
        Path dst = fileForId(targetId);
        if (!Files.isRegularFile(src)) return false;
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        Optional<StructureTemplate> cached = CACHE.remove(sourceId);
        if (cached != null) CACHE.put(targetId, cached);
        LOGGER.info("[DungeonTrain] Renamed contents template file {} -> {}", src, dst);
        return true;
    }

    private static Optional<StructureTemplate> loadFromConfig(ServerLevel level, CarriageContents contents, Vec3i interiorSize) {
        Path file = fileFor(contents);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return loadAndValidate(level, contents.id(), interiorSize, tag, "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read config contents template {} at {}: {}", contents.id(), file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadFromResource(ServerLevel level, CarriageContents contents, Vec3i interiorSize) {
        String resource = RESOURCE_PREFIX + contents.id() + EXT;
        try (InputStream in = CarriageContentsStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            CompoundTag tag = NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            return loadAndValidate(level, contents.id(), interiorSize, tag, "bundled " + resource);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled contents template {} at {}: {}", contents.id(), resource, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadAndValidate(
        ServerLevel level, String id, Vec3i interiorSize, CompoundTag tag, String origin
    ) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, tag);

        Vec3i size = template.getSize();
        if (!sizeMatches(size, interiorSize)) {
            LOGGER.warn(
                "[DungeonTrain] Contents template {} ({}) has bounds {}x{}x{}, expected interior {}x{}x{} — ignoring.",
                id, origin, size.getX(), size.getY(), size.getZ(),
                interiorSize.getX(), interiorSize.getY(), interiorSize.getZ()
            );
            return Optional.empty();
        }
        LOGGER.info("[DungeonTrain] Loaded contents template {} from {}", id, origin);
        return Optional.of(template);
    }

    private static boolean sizeMatches(Vec3i a, Vec3i b) {
        return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
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
