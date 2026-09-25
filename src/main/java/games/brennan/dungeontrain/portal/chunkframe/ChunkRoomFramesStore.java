package games.brennan.dungeontrain.portal.chunkframe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A room's {@code <room>.frames.json}, beside it in {@code portals/room/} — config first, then bundled.
 * A room with no file has no frames.
 */
public final class ChunkRoomFramesStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String EXT = ".frames.json";

    private static final Map<String, ChunkRoomFrames> CACHE = new HashMap<>();

    private ChunkRoomFramesStore() {}

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    /** {@code roomName}'s frames, or {@link ChunkRoomFrames#EMPTY}. */
    public static synchronized ChunkRoomFrames get(String roomName) {
        if (roomName == null) return ChunkRoomFrames.EMPTY;
        return CACHE.computeIfAbsent(roomName, ChunkRoomFramesStore::load);
    }

    /** Persist {@code frames} for {@code roomName}, and to the source tree when asked. */
    public static synchronized void save(String roomName, ChunkRoomFrames frames, boolean toSource)
            throws IOException {
        String text = new GsonBuilder().setPrettyPrinting().create().toJson(frames.toJson());
        Path file = UserContentPaths.activeSubDir(TrackKind.PORTAL_ROOM.subdir()).resolve(roomName + EXT);
        write(file, text);
        CACHE.put(roomName, frames);
        LOGGER.info("[DungeonTrain] Saved chunk frames for {} to {}", roomName, file);
        if (!toSource) return;
        Path source = sourcePathFor(roomName);
        if (source == null) {
            throw new IOException("Source tree not writable — are you running ./gradlew runClient from a checkout?");
        }
        write(source, text);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }

    private static Path sourcePathFor(String roomName) {
        Path projectRoot = FMLPaths.GAMEDIR.get().getParent();
        if (projectRoot == null) return null;
        Path resources = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resources) || !Files.isWritable(resources)) return null;
        return projectRoot.resolve(TrackKind.PORTAL_ROOM.sourceRelativePath()).resolve(roomName + EXT);
    }

    private static ChunkRoomFrames load(String roomName) {
        Path cfg = UserContentPaths.findFile(TrackKind.PORTAL_ROOM.subdir(), roomName + EXT);
        if (cfg != null) {
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                return parse(r, "config " + cfg);
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read chunk frames {}: {}", cfg, e.toString());
            }
        }
        String resource = TrackKind.PORTAL_ROOM.bundledResourcePrefix() + roomName + EXT;
        try (InputStream in = ChunkRoomFramesStore.class.getResourceAsStream(resource)) {
            if (in == null) return ChunkRoomFrames.EMPTY;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, "bundled " + resource);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled chunk frames {}: {}", resource, e.toString());
            return ChunkRoomFrames.EMPTY;
        }
    }

    /** Total: a malformed file logs and reads as no frames, which leaves the room in its skin. */
    private static ChunkRoomFrames parse(Reader reader, String origin) {
        try {
            return ChunkRoomFrames.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Chunk frames {} are malformed ({}); the room stays unframed", origin, e.toString());
            return ChunkRoomFrames.EMPTY;
        }
    }
}
