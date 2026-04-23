package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
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
 *   <li><b>Config dir</b> — {@code config/dungeontrain/templates/pillar_<section>.nbt}.
 *       Per-install override; the editor writes here via {@link #save}.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/templates/pillar_<section>.nbt}
 *       on the classpath. Shipped with the jar.</li>
 *   <li><b>Hardcoded fallback</b> — {@code TrackPalette.PILLAR} stone brick for every
 *       row. Handled by the caller ({@link games.brennan.dungeontrain.track.TrackGenerator})
 *       when {@link #get} returns empty.</li>
 * </ol>
 *
 * <p>The store rejects templates whose recorded size doesn't match
 * {@link PillarSection#height()} × 1 × 1 — prevents a stale saved template
 * from poisoning subsequent placements.</p>
 */
public final class PillarTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/templates";
    private static final String FILE_PREFIX = "pillar_";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/templates/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/templates";

    private static final Map<PillarSection, Optional<StructureTemplate>> CACHE = new EnumMap<>(PillarSection.class);
    private static final Map<PillarSection, Optional<BlockState[][]>> COLUMN_CACHE = new EnumMap<>(PillarSection.class);

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

    public static boolean bundled(PillarSection section) {
        try (InputStream in = PillarTemplateStore.class.getResourceAsStream(
                RESOURCE_PREFIX + FILE_PREFIX + section.id() + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
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
