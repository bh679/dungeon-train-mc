package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackTemplate;
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
import java.util.List;
import java.util.Optional;

/**
 * Three-tier store for the single open-air track template, mirroring
 * {@link PillarTemplateStore} but unkeyed — only one track tile exists per
 * world.
 *
 * <ol>
 *   <li><b>Config dir</b> — {@code config/dungeontrain/tracks/track.nbt}.
 *       Per-install override written by {@link TrackEditor#save}.</li>
 *   <li><b>Bundled resource</b> — {@code /data/dungeontrain/tracks/track.nbt}
 *       on the classpath. Shipped with the jar.</li>
 *   <li><b>Hardcoded fallback</b> — handled by
 *       {@link games.brennan.dungeontrain.track.TrackGenerator} when
 *       {@link #getCells} returns empty.</li>
 * </ol>
 *
 * <p>The directory is separate from both the carriage templates
 * ({@code config/dungeontrain/templates/}) and the pillar templates
 * ({@code config/dungeontrain/pillars/}) so
 * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry}'s {@code *.nbt}
 * scan never accidentally registers the track tile (4×2×W footprint) as a
 * custom carriage.</p>
 */
public final class TrackTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/tracks";
    private static final String FILENAME = "track.nbt";
    private static final String RESOURCE_PATH = "/data/dungeontrain/tracks/track.nbt";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/tracks";

    private static Optional<StructureTemplate> cache;
    private static Optional<BlockState[][][]> cellsCache;

    /**
     * Reflected accessor for {@link StructureTemplate#palettes}. Private in
     * vanilla 1.20.1; we don't ship an AT. Mirrors {@link PillarTemplateStore}.
     */
    private static Field palettesField;

    private TrackTemplateStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path file() {
        return directory().resolve(FILENAME);
    }

    public static Path sourceFile() {
        return sourceDirectory().resolve(FILENAME);
    }

    public static boolean sourceTreeAvailable() {
        Path resources = resourcesRootOrNull();
        return resources != null && Files.isDirectory(resources) && Files.isWritable(resources);
    }

    /** Release cached template + unpacked cells. Wired to {@code ServerStoppedEvent}. */
    public static synchronized void clearCache() {
        cache = null;
        cellsCache = null;
    }

    /**
     * Return the template unpacked as {@code BlockState[TILE_LENGTH][HEIGHT][width]}.
     * First index is the X offset within the tile (0..3), second is the Y row
     * (0 = bed, 1 = rail), third is the Z offset (0..width-1) within the track
     * span. Cells recorded as air are {@code null} — the caller decides whether
     * that means "skip this position" or "use a fallback block".
     *
     * <p>Empty when no config-dir or bundled template exists at the current
     * world's {@code dims.width()}; caller falls back to the hardcoded
     * stone-brick bed + vanilla rail behavior.</p>
     */
    public static synchronized Optional<BlockState[][][]> getCells(ServerLevel level, CarriageDims dims) {
        Optional<BlockState[][][]> cached = cellsCache;
        if (cached != null) {
            if (cached.isEmpty()) return cached;
            BlockState[][][] c = cached.get();
            if (c.length == TrackTemplate.TILE_LENGTH
                && c[0].length == TrackTemplate.HEIGHT
                && c[0][0].length == dims.width()) {
                return cached;
            }
            // Dim mismatch — fall through and rebuild.
        }
        Optional<StructureTemplate> tmpl = get(level, dims);
        Optional<BlockState[][][]> cells = tmpl.map(t -> extractCells(t, dims.width()));
        cellsCache = cells;
        return cells;
    }

    public static synchronized Optional<StructureTemplate> get(ServerLevel level, CarriageDims dims) {
        Optional<StructureTemplate> cached = cache;
        if (cached != null) return filterForDims(cached, dims);
        Optional<StructureTemplate> loaded = loadFromConfig(level, dims);
        if (loaded.isEmpty()) {
            loaded = loadFromResource(level, dims);
        }
        cache = loaded;
        return loaded;
    }

    private static Optional<StructureTemplate> filterForDims(Optional<StructureTemplate> cached, CarriageDims dims) {
        if (cached.isEmpty()) return cached;
        Vec3i size = cached.get().getSize();
        if (sizeMatches(size, dims)) return cached;
        LOGGER.warn(
            "[DungeonTrain] Cached track template no longer matches width {} — falling back.",
            dims.width());
        return Optional.empty();
    }

    private static boolean sizeMatches(Vec3i size, CarriageDims dims) {
        return size.getX() == TrackTemplate.TILE_LENGTH
            && size.getY() == TrackTemplate.HEIGHT
            && size.getZ() == dims.width();
    }

    public static synchronized void save(StructureTemplate template) throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = file();
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        cache = Optional.of(template);
        cellsCache = null;
        LOGGER.info("[DungeonTrain] Saved track template to {}", file);
    }

    public static synchronized void saveToSource(StructureTemplate template) throws IOException {
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path file = sourceFile();
        Files.createDirectories(file.getParent());
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        LOGGER.info("[DungeonTrain] Wrote bundled track template to {}", file);
    }

    public static synchronized void promote() throws IOException {
        Path src = file();
        if (!Files.isRegularFile(src)) {
            throw new IOException("No saved track template in " + src);
        }
        if (!sourceTreeAvailable()) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Path dst = sourceFile();
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("[DungeonTrain] Promoted track template from {} to {}", src, dst);
    }

    public static synchronized boolean delete() throws IOException {
        Path file = file();
        boolean existed = Files.deleteIfExists(file);
        cache = Optional.empty();
        cellsCache = Optional.empty();
        if (existed) LOGGER.info("[DungeonTrain] Deleted track template ({})", file);
        return existed;
    }

    public static boolean exists() {
        return Files.isRegularFile(file());
    }

    public static boolean bundled() {
        try (InputStream in = TrackTemplateStore.class.getResourceAsStream(RESOURCE_PATH)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<StructureTemplate> loadFromConfig(ServerLevel level, CarriageDims dims) {
        Path file = file();
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            return loadAndValidate(level, dims, tag, "config " + file);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read config track template at {}: {}",
                file, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadFromResource(ServerLevel level, CarriageDims dims) {
        try (InputStream in = TrackTemplateStore.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) return Optional.empty();
            CompoundTag tag = NbtIo.readCompressed(in);
            return loadAndValidate(level, dims, tag, "bundled " + RESOURCE_PATH);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled track template at {}: {}",
                RESOURCE_PATH, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<StructureTemplate> loadAndValidate(
        ServerLevel level, CarriageDims dims, CompoundTag tag, String origin
    ) {
        StructureTemplate template = new StructureTemplate();
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        template.load(blocks, tag);

        Vec3i size = template.getSize();
        if (!sizeMatches(size, dims)) {
            LOGGER.warn(
                "[DungeonTrain] Track template ({}) has bounds {}x{}x{}, expected {}x{}x{} — ignoring.",
                origin, size.getX(), size.getY(), size.getZ(),
                TrackTemplate.TILE_LENGTH, TrackTemplate.HEIGHT, dims.width()
            );
            return Optional.empty();
        }
        LOGGER.info("[DungeonTrain] Loaded track template from {}", origin);
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
     * {@code BlockState[TILE_LENGTH][HEIGHT][width]} array. {@code cells[0][0][0]}
     * is the tile origin (bed row, Z=0). Positions not present in the template
     * (recorded as air at save time) stay {@code null}.
     *
     * <p>Uses reflection on {@link StructureTemplate#palettes} because the
     * field is private and we don't ship an access transformer.</p>
     */
    private static BlockState[][][] extractCells(StructureTemplate template, int width) {
        BlockState[][][] cells = new BlockState[TrackTemplate.TILE_LENGTH][TrackTemplate.HEIGHT][width];
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
            if (palettes.isEmpty()) return cells;
            for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
                int x = info.pos().getX();
                int y = info.pos().getY();
                int z = info.pos().getZ();
                if (x >= 0 && x < TrackTemplate.TILE_LENGTH
                    && y >= 0 && y < TrackTemplate.HEIGHT
                    && z >= 0 && z < width) {
                    cells[x][y][z] = info.state();
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.error(
                "[DungeonTrain] Unable to extract track template cells (palettes field unreachable): {}",
                e.toString()
            );
        }
        return cells;
    }
}
