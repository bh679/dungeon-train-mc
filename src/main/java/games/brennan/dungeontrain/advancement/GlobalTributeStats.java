package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeonbackup.api.Located;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The most emeralds a player has ever paid for one photo Tribute, across every world. A tributed
 * photo is shown in the passenger log only when its Tribute beats this — a personal best — so the
 * feed carries the photos people valued most rather than every Tribute paid. Also the running total
 * of emeralds paid in Tribute to other people's photos, which drives Big Spender.
 *
 * <p>Sibling to {@link GlobalBookBurnStats} and built the same way: {@code
 * <gameDir>/dungeontrain/stats/<uuid>-tribute.json}, cached, written atomically. Tributes are rare,
 * so a new record is written straight away rather than on logout.</p>
 */
public final class GlobalTributeStats {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Pre-relocation home of the stats folder. This store never lived there; kept for {@link Located}. */
    private static final String LEGACY_DIR_NAME = "dungeontrain-stats";
    private static final String FILE_SUFFIX = "-tribute.json";

    private record Data(int highestTributePaid, int totalPaidToOthers) {
        static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            Codec.INT.optionalFieldOf("highestTributePaid", 0).forGetter(Data::highestTributePaid),
            Codec.INT.optionalFieldOf("totalPaidToOthers", 0).forGetter(Data::totalPaidToOthers)
        ).apply(in, Data::new));

        static final Data EMPTY = new Data(0, 0);
    }

    private static final Map<UUID, Data> CACHE = new ConcurrentHashMap<>();

    private GlobalTributeStats() {}

    /** {@code <gameDir>/dungeontrain/stats/<uuid>-tribute.json}. See {@link PlayerDataPaths}. */
    public static Path file(UUID playerUuid) {
        return located(playerUuid).read();
    }

    static Located located(UUID playerUuid) {
        return PlayerDataPaths.locate(PlayerDataPaths.STATS, LEGACY_DIR_NAME, playerUuid + FILE_SUFFIX);
    }

    /** Whether paying {@code cost} beats a best of {@code best}: strictly more, and something actually paid. */
    static boolean beats(int best, int cost) {
        return cost > 0 && cost > best;
    }

    /** A running total of {@code total} after paying {@code cost}: nothing paid adds nothing, and it never overflows. */
    static int added(int total, int cost) {
        if (cost <= 0) return total;
        return (int) Math.min(Integer.MAX_VALUE, (long) total + cost);
    }

    private static Data data(UUID uuid) {
        return CACHE.computeIfAbsent(uuid, GlobalTributeStats::loadFromDisk);
    }

    /** The most this player has paid for one Tribute, 0 if they never have. */
    public static int highestTributePaid(UUID uuid) {
        return data(uuid).highestTributePaid();
    }

    /** Every emerald this player has paid in Tribute to other people's photos, across every world. */
    public static int totalPaidToOthers(UUID uuid) {
        return data(uuid).totalPaidToOthers();
    }

    /** Add a Tribute of {@code cost} to someone else's photo to the running total, saved at once. @return the new total */
    public static synchronized int addPaidToOthers(UUID uuid, int cost) {
        Data old = data(uuid);
        int total = added(old.totalPaidToOthers(), cost);
        if (total == old.totalPaidToOthers()) return total;
        Data data = new Data(old.highestTributePaid(), total);
        CACHE.put(uuid, data);
        saveToDisk(uuid, data);
        return total;
    }

    /**
     * Record a Tribute of {@code cost}. When it is a new personal best it is saved at once and this
     * returns true; otherwise nothing changes.
     */
    public static synchronized boolean recordIfHighest(UUID uuid, int cost) {
        Data old = data(uuid);
        if (!beats(old.highestTributePaid(), cost)) return false;
        Data data = new Data(cost, old.totalPaidToOthers());
        CACHE.put(uuid, data);
        saveToDisk(uuid, data);
        return true;
    }

    /**
     * Wipe the player's record, cache first. Mirrors {@code GlobalBookBurnStats.deleteFor}.
     *
     * @return true when a file was actually removed
     */
    public static synchronized boolean deleteFor(UUID uuid) throws IOException {
        CACHE.remove(uuid);
        return Files.deleteIfExists(file(uuid));
    }

    private static Data loadFromDisk(UUID uuid) {
        Path path = file(uuid);
        if (!Files.isRegularFile(path)) return Data.EMPTY;
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
            if (result.error().isPresent()) {
                LOGGER.warn("[DungeonTrain] GlobalTributeStats: parse failed for {}: {}",
                    path, result.error().get().message());
                return Data.EMPTY;
            }
            return result.result().orElse(Data.EMPTY);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] GlobalTributeStats: error reading {}: {}", path, e.getMessage());
            return Data.EMPTY;
        }
    }

    private static void saveToDisk(UUID uuid, Data data) {
        Path path = file(uuid);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalTributeStats: failed to create dir {}: {}",
                path.getParent(), e.getMessage());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, data);
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalTributeStats: encode failed: {}", result.error().get().message());
            return;
        }
        JsonElement element = result.result().orElseThrow();
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            writer.write(element.toString());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalTributeStats: write tmp {} failed: {}", tmp, e.getMessage());
            return;
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                LOGGER.error("[DungeonTrain] GlobalTributeStats: rename {} -> {} failed: {}", tmp, path, e2.getMessage());
            }
        }
    }
}
