package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sidecar JSON store that mirrors granted Dungeon-Train advancements per
 * player UUID, OUTSIDE any individual world save. Lives at
 * {@code <minecraft>/config/dungeontrain-achievements/<uuid>.json}.
 *
 * <p>Layered on top of the vanilla advancement system: vanilla persists
 * progress per-world; on player login we replay this sidecar to
 * re-grant every advancement the player ever earned on this instance,
 * regardless of which world it happened in. On advancement-earn we
 * append to the sidecar.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "granted": [
 *     "dungeontrain:dungeon_train/chests_100_unique",
 *     "dungeontrain:dungeon_train/carts_100"
 *   ] }
 * }</pre>
 *
 * <p>Concurrency: methods are {@code synchronized} on the class. In
 * single-player there is never contention (single thread). On dedicated
 * server, simultaneous logins for different players still serialize
 * through this lock — fine, the I/O is microseconds.</p>
 */
public final class GlobalAchievementStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR_NAME = "dungeontrain-achievements";

    /** Schema record. {@code granted} stored as a list to preserve insertion order in the JSON. */
    public record Data(List<ResourceLocation> granted) {
        public static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            ResourceLocation.CODEC.listOf().optionalFieldOf("granted", List.of()).forGetter(Data::granted)
        ).apply(in, Data::new));

        public static final Data EMPTY = new Data(List.of());
    }

    private GlobalAchievementStore() {}

    /** Resolve the sidecar file path for {@code playerUuid}. */
    public static Path file(UUID playerUuid) {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME).resolve(playerUuid + ".json");
    }

    /**
     * Read the sidecar for {@code playerUuid}. Returns an empty set when
     * the file does not exist or is malformed (warn-logged in the
     * malformed case).
     */
    public static synchronized Set<ResourceLocation> read(UUID playerUuid) {
        Path path = file(playerUuid);
        if (!Files.isRegularFile(path)) return Set.of();
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.warn("[DungeonTrain] GlobalAchievementStore: failed to parse {}: {}",
                    path, result.error().get().message());
                return Set.of();
            }
            return new LinkedHashSet<>(result.result().orElse(Data.EMPTY).granted());
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] GlobalAchievementStore: I/O error reading {}: {}",
                path, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Append {@code advancement} to the sidecar if not already present.
     * Atomic-replace write so a crash mid-write can't corrupt the file.
     *
     * @return {@code true} when the file was actually mutated.
     */
    public static synchronized boolean append(UUID playerUuid, ResourceLocation advancement) {
        Set<ResourceLocation> current = new LinkedHashSet<>(read(playerUuid));
        if (!current.add(advancement)) return false;
        writeAtomic(playerUuid, current);
        return true;
    }

    /**
     * Remove {@code advancement} from the sidecar if present. Lets
     * {@code /advancement revoke} stick across logout — without this, the
     * login-replay would immediately re-grant a revoked advancement.
     *
     * @return {@code true} when the file was actually mutated.
     */
    public static synchronized boolean remove(UUID playerUuid, ResourceLocation advancement) {
        Set<ResourceLocation> current = new LinkedHashSet<>(read(playerUuid));
        if (!current.remove(advancement)) return false;
        writeAtomic(playerUuid, current);
        return true;
    }

    private static void writeAtomic(UUID playerUuid, Set<ResourceLocation> granted) {
        Path path = file(playerUuid);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalAchievementStore: failed to create dir {}: {}",
                path.getParent(), e.getMessage());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Data data = new Data(List.copyOf(granted));
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, data);
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalAchievementStore: encode failed: {}",
                result.error().get().message());
            return;
        }
        JsonElement element = result.result().orElseThrow();
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            writer.write(element.toString());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalAchievementStore: write tmp {} failed: {}",
                tmp, e.getMessage());
            return;
        }
        try {
            Files.move(tmp, path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Fall back to non-atomic on filesystems that don't support ATOMIC_MOVE.
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.error("[DungeonTrain] GlobalAchievementStore: rename {} -> {} failed: {}",
                    tmp, path, e2.getMessage());
            }
        }
    }
}
