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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Global on-disk store for carriage templates. One NBT file per
 * {@link CarriageType} under {@code config/dungeontrain/templates/}.
 * Missing or invalid files cause callers to fall back to the hardcoded
 * generator in {@link CarriageTemplate}.
 */
public final class CarriageTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/templates";
    private static final String EXT = ".nbt";

    // Cached per-type result — empty optional means "tried disk and nothing found",
    // which short-circuits future spawns without re-reading.
    private static final Map<CarriageType, Optional<StructureTemplate>> CACHE =
        new EnumMap<>(CarriageType.class);

    private CarriageTemplateStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(CarriageType type) {
        return directory().resolve(type.name().toLowerCase(Locale.ROOT) + EXT);
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
        Optional<StructureTemplate> loaded = loadFromDisk(level, type, dims);
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

    public static synchronized void save(CarriageType type, StructureTemplate template) throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(type);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        CACHE.put(type, Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved template {} to {}", type, file);
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

    private static Optional<StructureTemplate> loadFromDisk(ServerLevel level, CarriageType type, CarriageDims dims) {
        Path file = fileFor(type);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            StructureTemplate template = new StructureTemplate();
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            template.load(blocks, tag);

            Vec3i size = template.getSize();
            if (!CarriageTemplate.sizeMatches(size, dims)) {
                LOGGER.warn(
                    "[DungeonTrain] Template {} has bounds {}x{}x{}, expected {}x{}x{} — ignoring and falling back.",
                    type, size.getX(), size.getY(), size.getZ(),
                    dims.length(), dims.height(), dims.width()
                );
                return Optional.empty();
            }
            LOGGER.info("[DungeonTrain] Loaded template {} from {}", type, file);
            return Optional.of(template);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read template {} at {}: {}", type, file, e.toString());
            return Optional.empty();
        }
    }
}
