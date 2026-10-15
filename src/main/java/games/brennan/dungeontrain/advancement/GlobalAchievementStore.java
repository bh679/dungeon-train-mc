package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import games.brennan.dungeonbackup.api.Located;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.player.DifficultyPartition;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Sidecar JSON store that mirrors granted advancements per player UUID,
 * OUTSIDE any individual world save. Lives at
 * {@code <gameDir>/dungeontrain/achievements/<uuid>[.<partition>].json}.
 *
 * <p><b>One profile per difficulty.</b> Like the Ender Chest and the carried loadout, each vanilla
 * difficulty keeps its own advancements — see {@link DifficultyPartition}. Normal is the legacy
 * partition and keeps the original {@code <uuid>.json}, so everything earned before the split
 * stays on Normal. The Train Editor tab is shared by all of them ({@link #SHARED}). Which partition a
 * call reads or writes is chosen by {@link AdvancementProfiles}; this class only stores.</p>
 *
 * <p>Layered on top of the vanilla advancement system: vanilla persists
 * progress per-world; on player login we replay this sidecar to
 * re-grant every advancement the player ever earned on this instance,
 * regardless of which world it happened in. On advancement-earn we
 * append to the sidecar.</p>
 *
 * <p>Stores every advancement that appears in the advancements screen —
 * vanilla, Dungeon Train, and other mods alike (the hidden display-less
 * {@code recipes/*} tree is excluded). This store is a dumb id container;
 * the persist policy lives at the call sites in
 * {@link AdvancementProfiles#shouldPersist}.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "granted": [
 *     "minecraft:story/mine_stone",
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
    /** Pre-relocation home, still read when a migration hasn't managed to move a file yet. */
    private static final String LEGACY_DIR_NAME = "dungeontrain-achievements";

    /** Schema record. {@code granted} stored as a list to preserve insertion order in the JSON. */
    public record Data(List<ResourceLocation> granted) {
        public static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            ResourceLocation.CODEC.listOf().optionalFieldOf("granted", List.of()).forGetter(Data::granted)
        ).apply(in, Data::new));

        public static final Data EMPTY = new Data(List.of());
    }

    /**
     * The partition that holds advancements shared by every difficulty — the creative Train Editor
     * tab ({@link #isShared}). Not a vanilla difficulty name, so it can never collide with one.
     */
    public static final String SHARED = "shared";

    /** Every partition a player can have a file for — the four difficulties plus {@link #SHARED}. */
    private static final List<String> ALL_PARTITIONS = Stream.concat(
        Arrays.stream(Difficulty.values()).map(DifficultyPartition::keyFor), Stream.of(SHARED)).toList();

    private GlobalAchievementStore() {}

    /**
     * Is {@code id} one of the advancements every difficulty shares? The Train Editor tab is
     * authoring, not gameplay, so it isn't split by difficulty the way the rest of the profile is.
     */
    public static boolean isShared(ResourceLocation id) {
        return id.getNamespace().equals(DungeonTrain.MOD_ID) && id.getPath().startsWith("editor/");
    }

    /**
     * The sidecar's file name for a partition. The legacy partition (Normal) keeps the pre-partition
     * name {@code <uuid>.json}, so every existing profile simply becomes the Normal one; the others
     * get {@code <uuid>.<partition>.json}. A {@code .} rather than {@link DifficultyPartition}'s
     * {@code |}, which Windows won't accept in a file name.
     */
    public static String fileName(UUID playerUuid, String partitionKey) {
        return DifficultyPartition.suffixFor(partitionKey).isEmpty()
            ? playerUuid + ".json"
            : playerUuid + "." + partitionKey + ".json";
    }

    /**
     * Resolve the sidecar file for {@code playerUuid}'s {@code partitionKey}:
     * {@code <gameDir>/dungeontrain/achievements/<fileName>}.
     *
     * <p>Falls back to the pre-relocation {@code config/dungeontrain-achievements/} copy while one
     * still exists — see {@link PlayerDataPaths} for why these files left {@code config/} (a
     * modpack update replaces that folder, which is how a player lost every advancement).</p>
     */
    public static Path file(UUID playerUuid, String partitionKey) {
        return located(playerUuid, partitionKey).read();
    }

    private static Located located(UUID playerUuid, String partitionKey) {
        return PlayerDataPaths.locate(
            PlayerDataPaths.ACHIEVEMENTS, LEGACY_DIR_NAME, fileName(playerUuid, partitionKey));
    }

    /** Does {@code playerUuid} have a sidecar in any partition? Drives the Video Tools reset list. */
    public static boolean hasAny(UUID playerUuid) {
        for (String partition : ALL_PARTITIONS) {
            if (Files.isRegularFile(file(playerUuid, partition))) return true;
        }
        return false;
    }

    /**
     * Read {@code playerUuid}'s {@code partitionKey} sidecar. Returns an empty set when the file
     * does not exist or is malformed (warn-logged in the malformed case).
     *
     * <p>The {@link #SHARED} partition also reports the editor ids still sitting in the Normal file,
     * where every editor earn went before the split — read-time, so nothing has to be rewritten and
     * a Hard profile sees the editor tab it already earned. See {@link #sharedView}.</p>
     */
    public static synchronized Set<ResourceLocation> read(UUID playerUuid, String partitionKey) {
        Set<ResourceLocation> own = readFile(file(playerUuid, partitionKey));
        if (!SHARED.equals(partitionKey)) return own;
        return sharedView(own, readFile(file(playerUuid, DifficultyPartition.LEGACY_KEY)));
    }

    /** The shared partition's contents: its own file plus the legacy file's editor ids. */
    static Set<ResourceLocation> sharedView(Set<ResourceLocation> shared, Set<ResourceLocation> legacy) {
        Set<ResourceLocation> view = new LinkedHashSet<>(shared);
        for (ResourceLocation id : legacy) {
            if (isShared(id)) view.add(id);
        }
        return view;
    }

    static Set<ResourceLocation> readFile(Path path) {
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
     * Append {@code advancement} to the partition's sidecar if not already present.
     * Atomic-replace write so a crash mid-write can't corrupt the file.
     *
     * @return {@code true} when the file was actually mutated.
     */
    public static synchronized boolean append(UUID playerUuid, String partitionKey, ResourceLocation advancement) {
        Set<ResourceLocation> current = new LinkedHashSet<>(read(playerUuid, partitionKey));
        if (!current.add(advancement)) return false;
        writeAtomic(file(playerUuid, partitionKey), current);
        return true;
    }

    /**
     * Append every advancement in {@code advancements} not already present, in a
     * single atomic read-modify-write. Used by the login back-fill that absorbs a
     * world's already-earned advancements into the cross-world store — far cheaper
     * than calling {@link #append} once per id (one file write, not N), and never
     * writes when nothing new is added.
     *
     * @return the number of ids actually added (0 when all were already present).
     */
    public static synchronized int appendAll(UUID playerUuid, String partitionKey,
                                             Collection<ResourceLocation> advancements) {
        if (advancements.isEmpty()) return 0;
        Set<ResourceLocation> current = new LinkedHashSet<>(read(playerUuid, partitionKey));
        int before = current.size();
        current.addAll(advancements);
        int added = current.size() - before;
        if (added > 0) writeAtomic(file(playerUuid, partitionKey), current);
        return added;
    }

    /**
     * Remove {@code advancement} from the partition's sidecar if present. Lets
     * {@code /advancement revoke} stick across logout — without this, the
     * login-replay would immediately re-grant a revoked advancement.
     *
     * <p>Removing from {@link #SHARED} also strikes the id from the Normal file, or
     * {@link #sharedView} would hand it straight back.</p>
     *
     * @return {@code true} when a file was actually mutated.
     */
    public static synchronized boolean remove(UUID playerUuid, String partitionKey, ResourceLocation advancement) {
        boolean removed = removeFrom(file(playerUuid, partitionKey), advancement);
        if (SHARED.equals(partitionKey)) {
            removed |= removeFrom(file(playerUuid, DifficultyPartition.LEGACY_KEY), advancement);
        }
        return removed;
    }

    private static boolean removeFrom(Path path, ResourceLocation advancement) {
        Set<ResourceLocation> current = new LinkedHashSet<>(readFile(path));
        if (!current.remove(advancement)) return false;
        writeAtomic(path, current);
        return true;
    }

    /**
     * Delete every sidecar for {@code playerUuid}, in every partition. Unlike {@link #remove}, this
     * drops every grant at once — used by the Video Tools profile reset, where the point is that the
     * login replay in {@code AchievementEvents} has nothing left to replay.
     *
     * <p>Reads are file-backed with no cache, so unlinking the files is the entire operation.</p>
     *
     * @return {@code true} when a file was actually removed.
     */
    public static synchronized boolean deleteFor(UUID playerUuid) throws IOException {
        // Both addresses: a legacy copy the migration had to leave behind would otherwise survive
        // the reset and reappear as soon as the new file was gone.
        boolean removed = false;
        for (String partition : ALL_PARTITIONS) {
            for (Path path : located(playerUuid, partition).all()) {
                removed |= Files.deleteIfExists(path);
            }
        }
        return removed;
    }

    static void writeAtomic(Path path, Set<ResourceLocation> granted) {
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
