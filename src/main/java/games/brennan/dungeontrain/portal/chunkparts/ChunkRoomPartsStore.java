package games.brennan.dungeontrain.portal.chunkparts;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartAssignment;
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
import java.util.Optional;

/**
 * Which chunk parts a dimensional carriage room is framed in — its {@code <room>.parts.json}, beside
 * the room in {@code portals/room/}.
 *
 * <p>The file is a {@link CarriagePartAssignment}, the same format a carriage's {@code .parts.json}
 * is, read under the carriage part keys ({@code floor}, {@code walls}, {@code roof}, {@code doors}):
 * weighted names, and for walls and doors whether both sides share one pick. The names are
 * {@link ChunkPartKind} parts. A room with no file has no frame and stands in its lock skin.</p>
 */
public final class ChunkRoomPartsStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String EXT = ".parts.json";

    private static final Map<String, Optional<CarriagePartAssignment>> CACHE = new HashMap<>();

    private ChunkRoomPartsStore() {}

    public static synchronized void clearCache() {
        CACHE.clear();
    }

    /** {@code roomName}'s assignment — config first, then bundled — or empty when it has none. */
    public static synchronized Optional<CarriagePartAssignment> get(String roomName) {
        if (roomName == null) return Optional.empty();
        Optional<CarriagePartAssignment> cached = CACHE.get(roomName);
        if (cached != null) return cached;
        Optional<CarriagePartAssignment> loaded = load(roomName);
        CACHE.put(roomName, loaded);
        return loaded;
    }

    /** Persist {@code assignment} for {@code roomName}, and to the source tree when asked. */
    public static synchronized void save(String roomName, CarriagePartAssignment assignment, boolean toSource)
            throws IOException {
        String text = new GsonBuilder().setPrettyPrinting().create().toJson(assignment.toJson());
        Path file = UserContentPaths.activeSubDir(TrackKind.PORTAL_ROOM.subdir()).resolve(roomName + EXT);
        write(file, text);
        CACHE.put(roomName, Optional.of(assignment));
        LOGGER.info("[DungeonTrain] Saved chunk room parts for {} to {}", roomName, file);
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

    private static Optional<CarriagePartAssignment> load(String roomName) {
        Path cfg = UserContentPaths.findFile(TrackKind.PORTAL_ROOM.subdir(), roomName + EXT);
        if (cfg != null) {
            try (Reader r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                return parse(r, "config " + cfg);
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] Failed to read chunk room parts {}: {}", cfg, e.toString());
            }
        }
        String resource = TrackKind.PORTAL_ROOM.bundledResourcePrefix() + roomName + EXT;
        try (InputStream in = ChunkRoomPartsStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r, "bundled " + resource);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to read bundled chunk room parts {}: {}", resource, e.toString());
            return Optional.empty();
        }
    }

    /** Total: a malformed file logs and reads as "no frame", which leaves the room in its skin. */
    private static Optional<CarriagePartAssignment> parse(Reader reader, String origin) {
        try {
            JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
            return Optional.of(CarriagePartAssignment.fromJson(o));
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Chunk room parts {} is malformed ({}); the room stays unframed",
                origin, e.toString());
            return Optional.empty();
        }
    }
}
