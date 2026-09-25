package games.brennan.dungeontrain.portal.chunkframe;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.DoubleBlockTemplateRepair;
import games.brennan.dungeontrain.editor.UserContentPaths;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where frame templates live, and the loaded copies of them.
 *
 * <ol>
 *   <li><b>Config</b> — {@code config/dungeontrain/user/chunk_frames/<name>.nbt}, and the same path
 *       under every imported package. What the editor writes.</li>
 *   <li><b>Bundled</b> — {@code /data/dungeontrain/chunk_frames/<name>.nbt} in the jar.</li>
 * </ol>
 *
 * <p>A template of the wrong size is refused with a warning, not cropped or padded: a frame that does
 * not fit the room puts its walls somewhere they do not belong.</p>
 */
public final class ChunkFrameStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SUBDIR = "chunk_frames";
    private static final String EXT = ".nbt";
    static final String RESOURCE_PREFIX = "/data/dungeontrain/chunk_frames/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/chunk_frames";

    private static final Map<String, Optional<ChunkFrameTemplate>> CACHE = new HashMap<>();

    private ChunkFrameStore() {}

    /** The config file a frame is saved to. */
    public static Path fileFor(String name) {
        return UserContentPaths.dir(SUBDIR).resolve(name + EXT);
    }

    /** The source-tree path a dev-mode save writes, or null outside a writable checkout. */
    public static Path sourceFileFor(String name) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve(SOURCE_REL_PATH).resolve(name + EXT);
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    /** {@code name}'s template — config first, then bundled — or empty. */
    public static synchronized Optional<ChunkFrameTemplate> get(ServerLevel level, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        Optional<ChunkFrameTemplate> cached = CACHE.get(name);
        if (cached != null) return cached;
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        Optional<ChunkFrameTemplate> loaded = readTag(name)
            .map(tag -> ChunkFrameTemplate.decode(DoubleBlockTemplateRepair.repair(tag, "chunk_frame:" + name), blocks))
            .filter(template -> fits(name, template));
        CACHE.put(name, loaded);
        return loaded;
    }

    /** Write {@code tag} (a saved structure) as {@code name}, and to the source tree when asked. */
    public static synchronized void save(String name, CompoundTag tag, boolean toSource) throws IOException {
        CompoundTag repaired = DoubleBlockTemplateRepair.repair(tag, "save");
        Path file = fileFor(name);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(repaired, file);
        CACHE.remove(name);
        LOGGER.info("[DungeonTrain] Saved chunk frame {} to {}", name, file);
        if (!toSource) return;
        Path source = sourceFileFor(name);
        if (source == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Files.createDirectories(source.getParent());
        NbtIo.writeCompressed(repaired, source);
        LOGGER.info("[DungeonTrain] Wrote bundled chunk frame {} to {}", name, source);
    }

    /** The raw NBT for {@code name}, config first then bundled. */
    public static Optional<CompoundTag> readTag(String name) {
        Path file = UserContentPaths.findFile(SUBDIR, name + EXT);
        if (file != null) {
            try {
                return Optional.of(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read chunk frame {} at {}: {}", name, file, e.toString());
            }
        }
        String resource = RESOURCE_PREFIX + name + EXT;
        try (InputStream in = ChunkFrameStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            return Optional.of(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled chunk frame {}: {}", resource, e.toString());
            return Optional.empty();
        }
    }

    private static boolean fits(String name, ChunkFrameTemplate template) {
        if (template.size().equals(ChunkFrame.SIZE)) return true;
        LOGGER.warn("[DungeonTrain] Chunk frame {} is {}x{}x{}, expected {}x{}x{} — ignoring.", name,
            template.size().getX(), template.size().getY(), template.size().getZ(),
            ChunkFrame.SIZE.getX(), ChunkFrame.SIZE.getY(), ChunkFrame.SIZE.getZ());
        return false;
    }
}
