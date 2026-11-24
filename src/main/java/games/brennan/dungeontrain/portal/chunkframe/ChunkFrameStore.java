package games.brennan.dungeontrain.portal.chunkframe;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.DoubleBlockTemplateRepair;
import games.brennan.dungeontrain.editor.UserContentPaths;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Vec3i;
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
    public static final String SUBDIR = "chunk_frames";
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
        SIZES.clear();
    }

    /** Each frame's size, read from its template's {@code size} field without decoding the blocks. */
    private static final Map<String, Vec3i> SIZES = new HashMap<>();

    /**
     * {@code name}'s size — config first, then bundled — or the one-chunk frame's for a name with no
     * template yet (a frame being made, which starts as a one-chunk frame unless it copies another).
     */
    public static synchronized Vec3i sizeOf(String name) {
        if (name == null) return ChunkFrame.SIZE;
        return SIZES.computeIfAbsent(name, n -> readTag(n).map(ChunkFrameStore::sizeIn).orElse(ChunkFrame.SIZE));
    }

    private static Vec3i sizeIn(CompoundTag tag) {
        net.minecraft.nbt.ListTag size = tag.getList("size", net.minecraft.nbt.Tag.TAG_INT);
        if (size.size() != 3) return ChunkFrame.SIZE;
        return new Vec3i(size.getInt(0), size.getInt(1), size.getInt(2));
    }

    /** Drop {@code name}'s loaded copy, so the next read comes from disk. */
    public static synchronized void invalidate(String name) {
        CACHE.remove(name);
        SIZES.remove(name);
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
        SIZES.remove(name);
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

    /**
     * Delete {@code name}'s user file — and its source-tree copy when {@code fromSource} — returning
     * whether anything was deleted. A frame only the jar ships has nothing here to delete.
     */
    public static synchronized boolean deleteFiles(String name, boolean fromSource) throws IOException {
        boolean deleted = false;
        Path user = UserContentPaths.findFile(SUBDIR, name + EXT);
        if (user != null) deleted = Files.deleteIfExists(user);
        deleted |= Files.deleteIfExists(fileFor(name));
        if (fromSource) {
            Path source = sourceFileFor(name);
            if (source != null) deleted |= Files.deleteIfExists(source);
        }
        CACHE.remove(name);
        SIZES.remove(name);
        if (deleted) LOGGER.info("[DungeonTrain] Deleted chunk frame {}", name);
        return deleted;
    }

    /** True when the jar ships a frame named {@code name}. */
    public static boolean isBundled(String name) {
        try (InputStream in = ChunkFrameStore.class.getResourceAsStream(RESOURCE_PREFIX + name + EXT)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The bundled frame as a vanilla structure — what a reset to the shipped version stamps. */
    public static Optional<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate>
            bundledStructure(ServerLevel level, String name) {
        String resource = RESOURCE_PREFIX + name + EXT;
        try (InputStream in = ChunkFrameStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            var template = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
            template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK),
                DoubleBlockTemplateRepair.repair(tag, "chunk_frame:" + name));
            return Optional.of(template);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled chunk frame {}: {}", resource, e.toString());
            return Optional.empty();
        }
    }

    private static boolean fits(String name, ChunkFrameTemplate template) {
        if (ChunkFrame.validSize(template.size())) return true;
        LOGGER.warn("[DungeonTrain] Chunk frame {} is {}x{}x{}, which fits no generated room — ignoring.", name,
            template.size().getX(), template.size().getY(), template.size().getZ());
        return false;
    }
}
