package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.tunnel.TunnelTemplate;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
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
 * Global on-disk store for tunnel templates. One NBT file per
 * {@link TunnelVariant} under {@code config/dungeontrain/tunnels/} —
 * a separate directory from carriage templates (at
 * {@code config/dungeontrain/templates/}) so
 * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry}'s
 * {@code *.nbt} scan doesn't accidentally register tunnel variants
 * (10×14×13 footprint) as custom carriages (9×7×7 footprint), which
 * would fail placement and leave carriage-sized holes in the train.
 *
 * <p>Mirrors {@link CarriageTemplateStore} — missing or malformed files cause
 * callers to fall back to {@link games.brennan.dungeontrain.tunnel.LegacyTunnelPaint}.</p>
 */
public final class TunnelTemplateStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUBDIR = "dungeontrain/tunnels";
    private static final String EXT = ".nbt";

    private static final Map<TunnelVariant, Optional<StructureTemplate>> CACHE =
        new EnumMap<>(TunnelVariant.class);

    private TunnelTemplateStore() {}

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve(SUBDIR);
    }

    public static Path fileFor(TunnelVariant variant) {
        return directory().resolve(variant.name().toLowerCase(Locale.ROOT) + EXT);
    }

    public static synchronized void reload() {
        CACHE.clear();
    }

    public static synchronized Optional<StructureTemplate> get(ServerLevel level, TunnelVariant variant) {
        Optional<StructureTemplate> cached = CACHE.get(variant);
        if (cached != null) return cached;
        Optional<StructureTemplate> loaded = loadFromDisk(level, variant);
        CACHE.put(variant, loaded);
        return loaded;
    }

    public static synchronized void save(TunnelVariant variant, StructureTemplate template) throws IOException {
        Path dir = directory();
        Files.createDirectories(dir);
        Path file = fileFor(variant);
        CompoundTag tag = template.save(new CompoundTag());
        NbtIo.writeCompressed(tag, file.toFile());
        CACHE.put(variant, Optional.of(template));
        LOGGER.info("[DungeonTrain] Saved template tunnel_{} to {}", variant.name().toLowerCase(Locale.ROOT), file);
    }

    public static synchronized boolean delete(TunnelVariant variant) throws IOException {
        Path file = fileFor(variant);
        boolean existed = Files.deleteIfExists(file);
        CACHE.put(variant, Optional.empty());
        if (existed) LOGGER.info("[DungeonTrain] Deleted template tunnel_{} ({})", variant.name().toLowerCase(Locale.ROOT), file);
        return existed;
    }

    public static boolean exists(TunnelVariant variant) {
        return Files.isRegularFile(fileFor(variant));
    }

    private static Optional<StructureTemplate> loadFromDisk(ServerLevel level, TunnelVariant variant) {
        Path file = fileFor(variant);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            StructureTemplate template = new StructureTemplate();
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            template.load(blocks, tag);

            Vec3i size = template.getSize();
            if (size.getX() != TunnelTemplate.LENGTH
                || size.getY() != TunnelTemplate.HEIGHT
                || size.getZ() != TunnelTemplate.WIDTH) {
                LOGGER.warn(
                    "[DungeonTrain] Template tunnel_{} has bounds {}x{}x{}, expected {}x{}x{} — ignoring and falling back.",
                    variant.name().toLowerCase(Locale.ROOT), size.getX(), size.getY(), size.getZ(),
                    TunnelTemplate.LENGTH, TunnelTemplate.HEIGHT, TunnelTemplate.WIDTH
                );
                return Optional.empty();
            }
            LOGGER.info("[DungeonTrain] Loaded template tunnel_{} from {}", variant.name().toLowerCase(Locale.ROOT), file);
            return Optional.of(template);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read template tunnel_{} at {}: {}",
                variant.name().toLowerCase(Locale.ROOT), file, e.toString());
            return Optional.empty();
        }
    }
}
