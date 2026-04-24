package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Three-tier template store for pillar sections, mirroring
 * {@link CarriageTemplateStore} but keyed on the fixed-footprint
 * {@link PillarSection} enum instead of a variant registry.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/pillars/pillar_<section>.nbt}.
 *       Per-install override; the editor writes here via {@link #save}.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/pillars/pillar_<section>.nbt}
 *       on the classpath. Shipped with the jar.</li>
 *   <li><b>Hardcoded fallback</b> — {@code TrackPalette.PILLAR} stone brick for every
 *       row. Handled by the caller ({@link games.brennan.dungeontrain.track.TrackGenerator})
 *       when {@link #get} returns empty.</li>
 * </ol>
 *
 * <p>A separate directory from carriage templates (at
 * {@code config/dungeontrain/templates/}) so
 * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry}'s
 * {@code *.nbt} scan doesn't accidentally register pillar sections
 * (1×4×7 or 1×1×7 footprint) as custom carriages (9×7×7 footprint),
 * which would fail placement and leave carriage-sized holes in the
 * train. Mirrors the pattern established by {@link TunnelTemplateStore}.</p>
 *
 * <p>The store rejects templates whose recorded size doesn't match
 * {@link PillarSection#height()} × 1 × 1 — prevents a stale saved template
 * from poisoning subsequent placements.</p>
 */
public final class PillarTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/pillars";
    private static final String LEGACY_SUBDIR = "dungeontrain/templates";
    private static final String FILE_PREFIX = "pillar_";
    private static final String ADJUNCT_FILE_PREFIX = "adjunct_";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/pillars/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/pillars";

    private static final Map<PillarSection, Optional<StructureTemplate>> CACHE = new EnumMap<>(PillarSection.class);
    private static final Map<PillarSection, Optional<BlockState[][]>> COLUMN_CACHE = new EnumMap<>(PillarSection.class);
    private static final Map<PillarAdjunct, Optional<StructureTemplate>> ADJUNCT_CACHE = new EnumMap<>(PillarAdjunct.class);

    /**
     * Reflected accessor for {@link StructureTemplate#palettes}, which is
     * private in vanilla 1.20.1 and not exposed via an Access Transformer on
     * this mod. Resolved lazily the first time {@link #getColumn} is called so
     * a single AT failure doesn't break class loading.
     */
    private static Field palettesField;

    private PillarTemplateStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(PillarSection section) {
        return directory().resolve(FILE_PREFIX + section.id() + EXT);
    }

    public static Path sourceFileFor(PillarSection section) {
        return sourceDirectory().resolve(FILE_PREFIX + section.id() + EXT);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    /** Release any cached templates. Wired to {@code ServerStoppedEvent}. */
    public static synchronized void clearCache() {
        CACHE.clear();
        COLUMN_CACHE.clear();
        ADJUNCT_CACHE.clear();
    }

    public static Path fileFor(PillarAdjunct adjunct) {
        return directory().resolve(ADJUNCT_FILE_PREFIX + adjunct.id() + EXT);
    }

    public static Path sourceFileFor(PillarAdjunct adjunct) {
        return sourceDirectory().resolve(ADJUNCT_FILE_PREFIX + adjunct.id() + EXT);
    }

    /**
     * One-time migration from the legacy carriage-templates directory to
     * {@link #SUBDIR}. Pre-0.30 builds stored pillar NBTs in
     * {@code config/dungeontrain/templates/}, which is the same directory
     * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry} scans
     * for custom carriage variants — causing pillar ids to be misregistered
     * as carriages and leaving holes in the train.
     *
     * <p>Call this before any carriage-side scan of
     * {@link CarriageTemplateStore#directory()}. Idempotent: safe to call
     * repeatedly. Both "legacy file exists, new location empty" (move) and
     * "both exist" (delete legacy, keep new) outcomes log at INFO so the
     * migration is visible in user logs.</p>
     */
    public static synchronized void migrateFromLegacyDirectory() {
        Path legacyDir = FMLPaths.CONFIGDIR.get().resolve(LEGACY_SUBDIR);
        if (!Files.isDirectory(legacyDir)) return;
        Path newDir = directory();
        for (PillarSection section : PillarSection.values()) {
            Path legacy = legacyDir.resolve(FILE_PREFIX + section.id() + EXT);
            if (!Files.isRegularFile(legacy)) continue;
            Path target = newDir.resolve(FILE_PREFIX + section.id() + EXT);
            try {
                Files.createDirectories(newDir);
                if (Files.isRegularFile(target)) {
                    Files.delete(legacy);
                    LOGGER.info("[DungeonTrain] Pillar template {} already present at {} — removed stale legacy copy at {}",
                        section.id(), target, legacy);
                } else {
                    Files.move(legacy, target);
                    LOGGER.info("[DungeonTrain] Migrated pillar template {} from {} to {}",
                        section.id(), legacy, target);
                }
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to migrate pillar template {} from {} to {}: {}",
                    section.id(), legacy, target, e.toString());
            }
        }
    }

    /**
     * Return this section's template unpacked as {@code BlockState[height][width]}.
     * First index is the template's Y row (0 = bottom); second is the Z offset
     * (0..width-1) within the track span. Entries that the saved template
     * recorded as air come back as {@code null} — the caller decides whether
     * that means "skip this position" or "use a fallback block".
     *
     * <p>Empty when no config-dir or bundled template exists for the section
     * at the current world's {@code dims.width()}; caller falls back to
     * hardcoded stone brick across the whole slice.</p>
     */
    public static synchronized Optional<BlockState[][]> getColumn(ServerLevel level, PillarSection section, CarriageDims dims) {
        Optional<BlockState[][]> cached = COLUMN_CACHE.get(section);
        if (cached != null) {
            if (cached.isEmpty()) return cached;
            if (cached.get().length == section.height() && cached.get()[0].length == dims.width()) {
                return cached;
            }
            // Dim mismatch against the cached array — fall through to re-extract.
        }
        Optional<StructureTemplate> tmpl = get(level, section, dims);
        Optional<BlockState[][]> column = tmpl.map(t -> extractColumn(t, section.height(), dims.width()));
        COLUMN_CACHE.put(section, column);
        return column;
    }

    public static synchronized Optional<StructureTemplate> get(ServerLevel level, PillarSection section, CarriageDims dims) {
        Optional<StructureTemplate> cached = CACHE.get(section);
        if (cached != null) return filterForDims(section, cached, dims);
        Optional<StructureTemplate> loaded = loadFromConfig(level, section, dims);
        if (loaded.isEmpty()) {
            loaded = loadFromResource(level, section, dims);
        }
        CACHE.put(section, loaded);
        return loaded;
    }

    /**
     * Re-check a cached template against {@code dims} — the cache is populated
     * the first time a given section is loaded, but the active world's
     * {@link CarriageDims} can diverge (e.g. a second world is loaded before
     * {@link #clearCache} runs). Returning empty on mismatch falls the caller
     * back to the hardcoded stone-brick stamp.
     */
    private static Optional<StructureTemplate> filterForDims(PillarSection section, Optional<StructureTemplate> cached, CarriageDims dims) {
        if (cached.isEmpty()) return cached;
        Vec3i size = cached.get().getSize();
        if (sizeMatches(size, section, dims)) return cached;
        LOGGER.warn(
            "[DungeonTrain] Cached pillar template {} no longer matches width {} — falling back.",
            section.id(), dims.width());
        return Optional.empty();
    }

    private static boolean sizeMatches(Vec3i size, PillarSection section, CarriageDims dims) {
        return size.getX() == 1 && size.getY() == section.height() && size.getZ() == dims.width();
    }

    public static synchronized void save(PillarSection section, StructureTemplate template) throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(section);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        CACHE.put(section, Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved pillar template {} to {}", section.id(), file);
    }

    public static synchronized void saveToSource(PillarSection section, StructureTemplate template) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileFor(section);
        Files.createDirectories(file.getParent());
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        LOGGER.info("[DungeonTrain] Wrote bundled pillar template {} to {}", section.id(), file);
    }

    public static synchronized void promote(PillarSection section) throws IOException {
        Path src = fileFor(section);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved pillar template for " + section.id() + " in " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFileFor(section);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted pillar template {} from {} to {}", section.id(), src, dst);
    }

    public static synchronized boolean delete(PillarSection section) throws IOException {
        Path file = fileFor(section);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(section, Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted pillar template {} ({})", section.id(), file);
        return existed;
    }

    public static boolean exists(PillarSection section) {
        return Files.isRegularFile(fileFor(section));
    }

    /**
     * Load {@code section}'s template from the bundled tier only — the
     * classpath {@code /data/dungeontrain/pillars/pillar_<section>.nbt}.
     * Skips the config-dir override and the hardcoded stone-brick fallback.
     * Used by {@code /dt reset default} to revert a plot to the shipped
     * template regardless of any user edits saved to the config dir.
     *
     * <p>Returns empty when the section has no bundled copy.</p>
     */
    public static Optional<StructureTemplate> getBundled(ServerLevel level, PillarSection section, CarriageDims dims) {
        return loadFromResource(level, section, dims);
    }

    public static boolean bundled(PillarSection section) {
        try (InputStream in = PillarTemplateStore.class.getResourceAsStream(
                RESOURCE_PREFIX + FILE_PREFIX + section.id() + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Return the loaded adjunct template, three-tier (config → bundled →
     * empty). No hardcoded fallback — callers treat an empty result as
     * "don't place this adjunct". Validates footprint against the adjunct's
     * declared {@code x×y×z} size.
     */
    public static synchronized Optional<StructureTemplate> getAdjunct(ServerLevel level, PillarAdjunct adjunct) {
        Optional<StructureTemplate> cached = ADJUNCT_CACHE.get(adjunct);
        if (cached != null) return filterAdjunctForSize(adjunct, cached);
        Optional<StructureTemplate> loaded = loadAdjunctFromConfig(level, adjunct);
        if (loaded.isEmpty()) {
            loaded = loadAdjunctFromResource(level, adjunct);
        }
        ADJUNCT_CACHE.put(adjunct, loaded);
        return loaded;
    }

    public static synchronized void saveAdjunct(PillarAdjunct adjunct, StructureTemplate template) throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(adjunct);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        ADJUNCT_CACHE.put(adjunct, Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved pillar adjunct template {} to {}", adjunct.id(), file);
    }

    public static synchronized void saveAdjunctToSource(PillarAdjunct adjunct, StructureTemplate template) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFileFor(adjunct);
        Files.createDirectories(file.getParent());
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        LOGGER.info("[DungeonTrain] Wrote bundled pillar adjunct template {} to {}", adjunct.id(), file);
    }

    public static synchronized void promoteAdjunct(PillarAdjunct adjunct) throws IOException {
        Path src = fileFor(adjunct);
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved pillar adjunct template for " + adjunct.id() + " in " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFileFor(adjunct);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted pillar adjunct template {} from {} to {}", adjunct.id(), src, dst);
    }

    public static synchronized boolean deleteAdjunct(PillarAdjunct adjunct) throws IOException {
        Path file = fileFor(adjunct);
        boolean existed = Files.deleteIfExists(file);
        ADJUNCT_CACHE.put(adjunct, Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted pillar adjunct template {} ({})", adjunct.id(), file);
        return existed;
    }

    public static boolean existsAdjunct(PillarAdjunct adjunct) {
        return Files.isRegularFile(fileFor(adjunct));
    }

    public static boolean bundledAdjunct(PillarAdjunct adjunct) {
        try (InputStream in = PillarTemplateStore.class.getResourceAsStream(
                RESOURCE_PREFIX + ADJUNCT_FILE_PREFIX + adjunct.id() + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<StructureTemplate> filterAdjunctForSize(PillarAdjunct adjunct, Optional<StructureTemplate> cached) {
        if (cached.isEmpty()) return cached;
        Vec3i size = cached.get().getSize();
        if (adjunctSizeMatches(size, adjunct)) return cached;
        LOGGER.warn(
            "[DungeonTrain] Cached pillar adjunct template {} has bounds {}x{}x{}, expected {}x{}x{} — falling back.",
            adjunct.id(), size.getX(), size.getY(), size.getZ(),
            adjunct.xSize(), adjunct.ySize(), adjunct.zSize());
        return Optional.empty();
    }

    private static boolean adjunctSizeMatches(Vec3i size, PillarAdjunct adjunct) {
        return size.getX() == adjunct.xSize()
            && size.getY() == adjunct.ySize()
            && size.getZ() == adjunct.zSize();
    }

    private static Optional<StructureTemplate> loadAdjunctFromConfig(ServerLevel level, PillarAdjunct adjunct) {
        Path file = fileFor(adjunct);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            return loadAndValidateAdjunct(level, adjunct, tag, "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read config pillar adjunct template {} at {}: {}",
                adjunct.id(), file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadAdjunctFromResource(ServerLevel level, PillarAdjunct adjunct) {
        String resource = RESOURCE_PREFIX + ADJUNCT_FILE_PREFIX + adjunct.id() + EXT;
        try (InputStream in = PillarTemplateStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            CompoundTag tag = NbtIo.readCompressed(in);
            return loadAndValidateAdjunct(level, adjunct, tag, "bundled " + resource);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled pillar adjunct template {} at {}: {}",
                adjunct.id(), resource, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadAndValidateAdjunct(
        ServerLevel level, PillarAdjunct adjunct, CompoundTag tag, String origin
    ) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, tag);

        Vec3i size = template.getSize();
        if (!adjunctSizeMatches(size, adjunct)) {
            LOGGER.warn(
                "[DungeonTrain] Pillar adjunct template {} ({}) has bounds {}x{}x{}, expected {}x{}x{} — ignoring.",
                adjunct.id(), origin, size.getX(), size.getY(), size.getZ(),
                adjunct.xSize(), adjunct.ySize(), adjunct.zSize()
            );
            return Optional.empty();
        }
        LOGGER.info("[DungeonTrain] Loaded pillar adjunct template {} from {}", adjunct.id(), origin);
        return Optional.of(template);
    }

    private static Optional<StructureTemplate> loadFromConfig(ServerLevel level, PillarSection section, CarriageDims dims) {
        Path file = fileFor(section);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            return loadAndValidate(level, section, dims, tag, "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read config pillar template {} at {}: {}",
                section.id(), file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadFromResource(ServerLevel level, PillarSection section, CarriageDims dims) {
        String resource = RESOURCE_PREFIX + FILE_PREFIX + section.id() + EXT;
        try (InputStream in = PillarTemplateStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            CompoundTag tag = NbtIo.readCompressed(in);
            return loadAndValidate(level, section, dims, tag, "bundled " + resource);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled pillar template {} at {}: {}",
                section.id(), resource, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadAndValidate(
        ServerLevel level, PillarSection section, CarriageDims dims, CompoundTag tag, String origin
    ) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, tag);

        Vec3i size = template.getSize();
        if (!sizeMatches(size, section, dims)) {
            LOGGER.warn(
                "[DungeonTrain] Pillar template {} ({}) has bounds {}x{}x{}, expected 1x{}x{} — ignoring.",
                section.id(), origin, size.getX(), size.getY(), size.getZ(),
                section.height(), dims.width()
            );
            return Optional.empty();
        }
        LOGGER.info("[DungeonTrain] Loaded pillar template {} from {}", section.id(), origin);
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

    /**
     * Unpack the first palette of {@code template} into a
     * {@code BlockState[height][width]} array. {@code column[0]} is the
     * bottom row; {@code column[y][0]} is the Z=0 position within the track.
     * Positions not present in the template stay {@code null}. Uses
     * reflection because {@link StructureTemplate#palettes} is private and
     * we don't ship an AT.
     */
    private static BlockState[][] extractColumn(StructureTemplate template, int height, int width) {
        BlockState[][] column = new BlockState[height][width];
        try {
            Field field = palettesField;
            if (field == null) {
                field = StructureTemplate.class.getDeclaredField("palettes");
                field.setAccessible(true);
                palettesField = field;
            }
            @SuppressWarnings("unchecked")
            List<StructureTemplate.Palette> palettes =
                (List<StructureTemplate.Palette>) field.get(template);
            if (palettes.isEmpty()) return column;
            for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
                int y = info.pos().getY();
                int z = info.pos().getZ();
                if (y >= 0 && y < height && z >= 0 && z < width) {
                    column[y][z] = info.state();
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.error(
                "[DungeonTrain] Unable to extract pillar template column (palettes field unreachable): {}",
                e.toString()
            );
        }
        return column;
    }
}
