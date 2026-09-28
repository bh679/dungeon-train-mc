package games.brennan.dungeontrain.portal.chunkframe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A frame's {@code <name>.frame.json} ({@link ChunkFrameMeta}) — config first, then bundled, then
 * {@link ChunkFrameMeta#DEFAULT}.
 */
public final class ChunkFrameMetaStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String EXT = ".frame.json";

    private static final Map<String, ChunkFrameMeta> CACHE = new HashMap<>();

    private ChunkFrameMetaStore() {}

    public static Path configPathFor(String name) {
        return UserContentPaths.activeSubDir(ChunkFrameStore.SUBDIR).resolve(name + EXT);
    }

    public static synchronized ChunkFrameMeta get(String name) {
        return CACHE.computeIfAbsent(name, ChunkFrameMetaStore::read);
    }

    public static synchronized void save(String name, ChunkFrameMeta meta, boolean toSource) throws IOException {
        String text = new GsonBuilder().setPrettyPrinting().create().toJson(meta.toJson());
        write(configPathFor(name), text);
        CACHE.put(name, meta);
        if (!toSource) return;
        Path source = sourcePathFor(name);
        if (source == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        write(source, text);
    }

    /** Delete {@code name}'s meta files; true when any existed. */
    public static synchronized boolean delete(String name, boolean fromSource) throws IOException {
        boolean deleted = Files.deleteIfExists(configPathFor(name));
        if (fromSource) {
            Path source = sourcePathFor(name);
            if (source != null) deleted |= Files.deleteIfExists(source);
        }
        CACHE.remove(name);
        return deleted;
    }

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Path sourcePathFor(String name) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve("src/main/resources/data/dungeontrain/" + ChunkFrameStore.SUBDIR).resolve(name + EXT);
    }

    private static ChunkFrameMeta read(String name) {
        try {
            String json = null;
            Path cfg = UserContentPaths.findFile(ChunkFrameStore.SUBDIR, name + EXT);
            if (cfg != null) {
                json = Files.readString(cfg, StandardCharsets.UTF_8);
            } else {
                try (InputStream in = ChunkFrameMetaStore.class.getResourceAsStream(
                        ChunkFrameStore.RESOURCE_PREFIX + name + EXT)) {
                    if (in != null) json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            if (json == null || json.isBlank()) return ChunkFrameMeta.DEFAULT;
            return ChunkFrameMeta.fromJson(JsonParser.parseString(json).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Chunk frame meta for {} is unreadable ({}); using the default", name, e.toString());
            return ChunkFrameMeta.DEFAULT;
        }
    }
}
