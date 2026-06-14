package games.brennan.dungeontrain.discord;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Sidecar JSON store — per player UUID and OUTSIDE any world save — holding the cross-world
 * state needed to fire the developer "first new world after first death" ping exactly once per
 * player. Lives at {@code <minecraft>/config/dungeontrain-devping/<uuid>.json}.
 *
 * <p>{@code firstDeathAtMillis} is the wall-clock time of the player's first-ever death on this
 * instance ({@code 0} = has not died); {@code devPingSent} flips true the one time the ping
 * marker is actually emitted. Cross-world by design — a death in one world gates the marker on
 * the first world created afterwards (see {@link DevPingService}).</p>
 *
 * <p>JSON shape: {@code { "firstDeathAtMillis": 1718000000000, "devPingSent": false }}</p>
 *
 * <p>Concurrency: methods are {@code synchronized} on the class, mirroring
 * {@code GlobalAchievementStore}. Writes are rare (one death stamp, one ping per player) and use
 * an atomic-replace so a crash mid-write can't corrupt the file.</p>
 */
public final class GlobalDeathPingStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR_NAME = "dungeontrain-devping";

    /** Schema record; both fields optional so older/partial files load with sensible defaults. */
    public record Data(long firstDeathAtMillis, boolean devPingSent) {
        public static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.LONG.optionalFieldOf("firstDeathAtMillis", 0L).forGetter(Data::firstDeathAtMillis),
            Codec.BOOL.optionalFieldOf("devPingSent", false).forGetter(Data::devPingSent)
        ).apply(in, Data::new));

        public static final Data EMPTY = new Data(0L, false);
    }

    private GlobalDeathPingStore() {}

    /** Resolve the sidecar file path for {@code playerUuid}. */
    public static Path file(UUID playerUuid) {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME).resolve(playerUuid + ".json");
    }

    /** Read the sidecar, returning {@link Data#EMPTY} when the file is missing or malformed. */
    public static synchronized Data read(UUID playerUuid) {
        Path path = file(playerUuid);
        if (!Files.isRegularFile(path)) return Data.EMPTY;
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.warn("[DungeonTrain] GlobalDeathPingStore: failed to parse {}: {}",
                    path, result.error().get().message());
                return Data.EMPTY;
            }
            return result.result().orElse(Data.EMPTY);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] GlobalDeathPingStore: I/O error reading {}: {}",
                path, e.getMessage());
            return Data.EMPTY;
        }
    }

    /** Wall-clock millis of the player's first-ever death, or {@code 0} when they have not died. */
    public static synchronized long firstDeathAt(UUID playerUuid) {
        return read(playerUuid).firstDeathAtMillis();
    }

    /** Whether the developer ping has already been emitted once for this player. */
    public static synchronized boolean devPingSent(UUID playerUuid) {
        return read(playerUuid).devPingSent();
    }

    /**
     * Stamp the first-death time the first time only; later deaths leave it unchanged.
     *
     * @return {@code true} when the file was actually mutated.
     */
    public static synchronized boolean recordFirstDeathIfUnset(UUID playerUuid, long nowMillis) {
        Data current = read(playerUuid);
        if (current.firstDeathAtMillis() != 0L) return false;
        writeAtomic(playerUuid, new Data(nowMillis, current.devPingSent()));
        return true;
    }

    /**
     * Atomically mark the developer ping consumed; returns {@code true} only for the first caller
     * so the marker is emitted at most once, ever, per player.
     */
    public static synchronized boolean markDevPingSentIfUnset(UUID playerUuid) {
        Data current = read(playerUuid);
        if (current.devPingSent()) return false;
        writeAtomic(playerUuid, new Data(current.firstDeathAtMillis(), true));
        return true;
    }

    private static void writeAtomic(UUID playerUuid, Data data) {
        Path path = file(playerUuid);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalDeathPingStore: failed to create dir {}: {}",
                path.getParent(), e.getMessage());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, data);
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalDeathPingStore: encode failed: {}",
                result.error().get().message());
            return;
        }
        JsonElement element = result.result().orElseThrow();
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            writer.write(element.toString());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalDeathPingStore: write tmp {} failed: {}",
                tmp, e.getMessage());
            return;
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Fall back to non-atomic on filesystems that don't support ATOMIC_MOVE.
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.error("[DungeonTrain] GlobalDeathPingStore: rename {} -> {} failed: {}",
                    tmp, path, e2.getMessage());
            }
        }
    }
}
