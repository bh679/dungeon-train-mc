package games.brennan.dungeontrain.portal.chunkparts;

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
 * Where chunk part templates live, and the loaded copies of them.
 *
 * <p>The carriage parts' two tiers, in a subtree of their own so neither registry ever scans the
 * other's files — a chunk wall read as a carriage wall fails its size check, and the reverse too:</p>
 * <ol>
 *   <li><b>Config</b> — {@code config/dungeontrain/user/parts/chunk_<kind>/<name>.nbt}, and the same
 *       path under every imported package. What the editor writes.</li>
 *   <li><b>Bundled</b> — {@code /data/dungeontrain/parts/chunk_<kind>/<name>.nbt} in the jar.</li>
 * </ol>
 *
 * <p>A template of the wrong size is refused with a warning, not cropped or padded: a frame that does
 * not fit its hull leaves a gap into the basement or writes over a corridor.</p>
 */
public final class ChunkPartStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String SUBDIR_BASE = "parts";
    private static final String EXT = ".nbt";
    private static final String RESOURCE_PREFIX = "/data/dungeontrain/parts/";
    private static final String SOURCE_REL_PATH = "src/main/resources/data/dungeontrain/parts";

    private static final Map<String, Optional<ChunkPart>> CACHE = new HashMap<>();

    private ChunkPartStore() {}

    /** The config directory a kind's parts are saved to. */
    public static Path directory(ChunkPartKind kind) {
        return UserContentPaths.dir(SUBDIR_BASE).resolve(kind.id());
    }

    public static Path fileFor(ChunkPartKind kind, String name) {
        return directory(kind).resolve(name + EXT);
    }

    /** The source-tree path a dev-mode save writes, or null outside a writable checkout. */
    public static Path sourceFileFor(ChunkPartKind kind, String name) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve(SOURCE_REL_PATH).resolve(kind.id()).resolve(name + EXT);
    }

    /** The subdirectory slug a kind's config files sit under, for {@link UserContentPaths}. */
    static String subSlug(ChunkPartKind kind) {
        return SUBDIR_BASE + "/" + kind.id();
    }

    /** The bundled resource directory a kind's jar files sit under. */
    static String resourcePrefix(ChunkPartKind kind) {
        return RESOURCE_PREFIX + kind.id() + "/";
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static synchronized void invalidate(ChunkPartKind kind, String name) {
        CACHE.remove(key(kind, name));
    }

    /** {@code name}'s template for {@code kind} — config first, then bundled — or empty. */
    public static synchronized Optional<ChunkPart> get(ServerLevel level, ChunkPartKind kind, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String key = key(kind, name);
        Optional<ChunkPart> cached = CACHE.get(key);
        if (cached != null) return cached;
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        Optional<ChunkPart> loaded = readTag(kind, name)
            .map(tag -> ChunkPart.decode(DoubleBlockTemplateRepair.repair(tag, kind.id() + ":" + name), blocks))
            .filter(part -> fits(kind, name, part));
        CACHE.put(key, loaded);
        return loaded;
    }

    /** Write {@code tag} (a saved structure) as {@code name}, and to the source tree when asked. */
    public static synchronized void save(ChunkPartKind kind, String name, CompoundTag tag, boolean toSource)
            throws IOException {
        CompoundTag repaired = DoubleBlockTemplateRepair.repair(tag, "save");
        Path file = fileFor(kind, name);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(repaired, file);
        CACHE.remove(key(kind, name));
        LOGGER.info("[DungeonTrain] Saved chunk part {}:{} to {}", kind.id(), name, file);
        if (!toSource) return;
        Path source = sourceFileFor(kind, name);
        if (source == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        Files.createDirectories(source.getParent());
        NbtIo.writeCompressed(repaired, source);
        LOGGER.info("[DungeonTrain] Wrote bundled chunk part {}:{} to {}", kind.id(), name, source);
    }

    /** The raw NBT for {@code name}, config first then bundled. */
    public static Optional<CompoundTag> readTag(ChunkPartKind kind, String name) {
        Path file = UserContentPaths.findFile(subSlug(kind), name + EXT);
        if (file != null) {
            try {
                return Optional.of(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read chunk part {}:{} at {}: {}",
                    kind.id(), name, file, e.toString());
            }
        }
        String resource = resourcePrefix(kind) + name + EXT;
        try (InputStream in = ChunkPartStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            return Optional.of(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled chunk part {}: {}", resource, e.toString());
            return Optional.empty();
        }
    }

    private static boolean fits(ChunkPartKind kind, String name, ChunkPart part) {
        if (part.size().equals(kind.size())) return true;
        LOGGER.warn("[DungeonTrain] Chunk part {}:{} is {}x{}x{}, expected {}x{}x{} — ignoring.",
            kind.id(), name, part.size().getX(), part.size().getY(), part.size().getZ(),
            kind.size().getX(), kind.size().getY(), kind.size().getZ());
        return false;
    }

    private static String key(ChunkPartKind kind, String name) {
        return kind.id() + "/" + name;
    }
}
