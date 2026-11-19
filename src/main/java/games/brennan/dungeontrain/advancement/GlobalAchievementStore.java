package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import games.brennan.dungeonbackup.api.Located;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.ProfileIntegrity;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.data.SealedJson;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
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
 * <p><b>Two lists, one profile.</b> {@code granted} holds what was earned live on this install.
 * {@code absorbed} holds what the store took on trust: ids banked from a world's own advancement
 * file at login ({@link AdvancementProfiles#absorb}), and everything in a file whose seal was
 * missing on an install that had sealed before (see {@link SealedJson}). Both replay the same, so the
 * player sees no difference; only the records care ({@link CompletionistAdvancement#isVerifiedEarn}),
 * because a world's advancement file is as editable as this one.</p>
 *
 * <p><b>Sealed.</b> Every write carries a {@link SealedJson} seal. A read whose seal does not match
 * keeps the contents but marks the player ({@link ProfileIntegrity}) and rewrites the file with the
 * taint under the seal, so it holds until the profile is reset.</p>
 *
 * <p>JSON shape:
 * <pre>{@code
 * { "granted": [
 *     "minecraft:story/mine_stone",
 *     "dungeontrain:dungeon_train/chests_100_unique"
 *   ],
 *   "absorbed": [ "dungeontrain:dungeon_train/carts_100" ],
 *   "seal": "…" }
 * }</pre>
 *
 * <p>Disk I/O stays off the server thread after a profile's first read. Each partition is
 * cached once loaded; earns and revokes update the cache and hand the file write to one
 * background thread. An earn used to re-read and rewrite the file on the server thread, and on
 * Windows opening that small file once took 8 s (an antivirus scan or a sleeping drive), freezing
 * the game. The cache lives for one server: logout drops the player without waiting for the
 * writer ({@link #evict}), server stop flushes and empties it ({@link #flushAndClearAll}).</p>
 *
 * <p>Concurrency: the public methods are {@code synchronized} on the class, so a read-modify-write
 * never interleaves. The writer never takes that lock, so a slow disk can't block a caller.</p>
 */
public final class GlobalAchievementStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Pre-relocation home, still read when a migration hasn't managed to move a file yet. */
    private static final String LEGACY_DIR_NAME = "dungeontrain-achievements";
    /** How long a flush waits for the writer before giving up (and logging). */
    private static final long FLUSH_TIMEOUT_SECONDS = 10;
    /** The store's name in every file's seal scope. */
    static final String SEAL_STORE = "achievements";

    /** One player's profile in one partition — the cache key, so lookups never touch the disk. */
    private record Key(UUID uuid, String partition) {}

    /**
     * One partition's contents: the ids earned live and the ids taken on trust (disjoint), plus
     * whether the file was caught edited. Immutable; every change makes a new one.
     */
    public record Profile(Set<ResourceLocation> granted, Set<ResourceLocation> absorbed, boolean tainted) {
        public static final Profile EMPTY = new Profile(Set.of(), Set.of(), false);

        public Profile {
            granted = Collections.unmodifiableSet(new LinkedHashSet<>(granted));
            absorbed = Collections.unmodifiableSet(new LinkedHashSet<>(absorbed));
        }

        /** Everything the profile holds, earned or absorbed, in file order. */
        public Set<ResourceLocation> all() {
            Set<ResourceLocation> all = new LinkedHashSet<>(granted);
            all.addAll(absorbed);
            return all;
        }

        Profile withGranted(Set<ResourceLocation> ids) { return new Profile(ids, absorbed, tainted); }
        Profile withAbsorbed(Set<ResourceLocation> ids) { return new Profile(granted, ids, tainted); }
        Profile asTainted() { return new Profile(granted, absorbed, true); }
    }

    /** Each loaded partition's own file contents. */
    private static final Map<Key, Profile> CACHE = new ConcurrentHashMap<>();
    /** The newest snapshot of each partition still waiting to be written. */
    private static final Map<Key, Profile> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DungeonTrain-AchievementStore");
        thread.setDaemon(true);
        return thread;
    });
    /** Where a partition's file lives; swapped for a temp directory in tests. */
    private static volatile BiFunction<UUID, String, Path> resolver = GlobalAchievementStore::file;

    /** Schema record. Lists, to preserve insertion order in the JSON. */
    public record Data(List<ResourceLocation> granted, List<ResourceLocation> absorbed) {
        public static final Codec<Data> CODEC = RecordCodecBuilder.create(in -> in.group(
            ResourceLocation.CODEC.listOf().optionalFieldOf("granted", List.of()).forGetter(Data::granted),
            ResourceLocation.CODEC.listOf().optionalFieldOf("absorbed", List.of()).forGetter(Data::absorbed)
        ).apply(in, Data::new));

        public static final Data EMPTY = new Data(List.of(), List.of());
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
     * Read {@code playerUuid}'s {@code partitionKey} sidecar: everything it holds, earned or
     * absorbed. Returns an empty set when the file does not exist or is malformed (warn-logged in
     * the malformed case).
     *
     * <p>Served from the cache once the partition has been loaded, so only the first read of a
     * session touches the disk.</p>
     *
     * <p>The {@link #SHARED} partition also reports the editor ids still sitting in the Normal file,
     * where every editor earn went before the split — read-time, so nothing has to be rewritten and
     * a Hard profile sees the editor tab it already earned. See {@link #sharedView}.</p>
     */
    public static synchronized Set<ResourceLocation> read(UUID playerUuid, String partitionKey) {
        Set<ResourceLocation> own = cached(new Key(playerUuid, partitionKey)).all();
        if (!SHARED.equals(partitionKey)) return own;
        return sharedView(own, cached(new Key(playerUuid, DifficultyPartition.LEGACY_KEY)).all());
    }

    /** The ids in {@code partitionKey} the store took on trust rather than saw earned. */
    public static synchronized Set<ResourceLocation> unverified(UUID playerUuid, String partitionKey) {
        return cached(new Key(playerUuid, partitionKey)).absorbed();
    }

    /** The shared partition's contents: its own file plus the legacy file's editor ids. */
    static Set<ResourceLocation> sharedView(Set<ResourceLocation> shared, Set<ResourceLocation> legacy) {
        Set<ResourceLocation> view = new LinkedHashSet<>(shared);
        for (ResourceLocation id : legacy) {
            if (isShared(id)) view.add(id);
        }
        return view;
    }

    /**
     * Read one file and judge its seal. Malformed JSON is an empty profile, as before: a crash can
     * leave that, and it is not evidence of anything. A seal that does not match marks the player
     * and taints the profile; a missing seal on an install that had sealed before moves every id to
     * {@code absorbed}.
     */
    static Profile readFile(Path path, UUID uuid, String partition) {
        if (!Files.isRegularFile(path)) return Profile.EMPTY;
        JsonElement element;
        try (Reader reader = Files.newBufferedReader(path)) {
            element = JsonParser.parseReader(reader);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] GlobalAchievementStore: I/O error reading {}: {}", path, e.getMessage());
            return Profile.EMPTY;
        }
        var result = Data.CODEC.parse(JsonOps.INSTANCE, element);
        if (result.error().isPresent() || !element.isJsonObject()) {
            LOGGER.warn("[DungeonTrain] GlobalAchievementStore: failed to parse {}: {}",
                path, result.error().map(e -> e.message()).orElse("not an object"));
            return Profile.EMPTY;
        }
        Data data = result.result().orElse(Data.EMPTY);
        SealedJson.Verdict verdict = SealedJson.verify(element.getAsJsonObject(),
            SealedJson.scope(SEAL_STORE, uuid, partition));
        return judged(new Profile(new LinkedHashSet<>(data.granted()), new LinkedHashSet<>(data.absorbed()), false),
            verdict, uuid, path);
    }

    /** Apply a read's verdict to the profile it read. Package-private for tests. */
    static Profile judged(Profile profile, SealedJson.Verdict verdict, UUID uuid, Path path) {
        switch (verdict.provenance()) {
            case TAMPERED -> {
                ProfileIntegrity.markTampered(uuid, "The advancement profile " + path.getFileName());
                return profile.asTainted();
            }
            case UNVERIFIED -> {
                if (profile.granted().isEmpty()) return profile;
                LOGGER.warn("[DungeonTrain] GlobalAchievementStore: {} has no seal on an install that has "
                    + "sealed before; its {} advancement(s) are kept but not vouched for.",
                    path.getFileName(), profile.granted().size());
                Set<ResourceLocation> absorbed = new LinkedHashSet<>(profile.absorbed());
                absorbed.addAll(profile.granted());
                return new Profile(Set.of(), absorbed, false);
            }
            default -> {
                if (verdict.tainted()) {
                    ProfileIntegrity.markTampered(uuid, "The advancement profile " + path.getFileName());
                    return profile.asTainted();
                }
                return profile;
            }
        }
    }

    /**
     * Append {@code advancement} to the partition's sidecar if not already present. The cache is
     * updated at once; the file is written by the background writer ({@link #store}).
     *
     * @return {@code true} when the profile actually changed.
     */
    public static synchronized boolean append(UUID playerUuid, String partitionKey, ResourceLocation advancement) {
        return appendAll(playerUuid, partitionKey, List.of(advancement)) > 0;
    }

    /**
     * Append every advancement in {@code advancements} not already present as a live earn, in a
     * single read-modify-write.
     *
     * @return the number of ids actually added (0 when all were already present).
     */
    public static synchronized int appendAll(UUID playerUuid, String partitionKey,
                                             Collection<ResourceLocation> advancements) {
        if (advancements.isEmpty()) return 0;
        Key key = new Key(playerUuid, partitionKey);
        Profile current = cached(key);
        Set<ResourceLocation> granted = new LinkedHashSet<>(current.granted());
        int before = granted.size();
        for (ResourceLocation id : advancements) {
            if (!current.absorbed().contains(id)) granted.add(id);
        }
        int added = granted.size() - before;
        if (added > 0) store(key, current.withGranted(granted));
        return added;
    }

    /**
     * Append every advancement in {@code advancements} the profile does not hold yet as
     * <em>absorbed</em>: present, replayed, but not vouched for. Used by the login back-fill that
     * takes a world's already-earned advancements into the cross-world store, since that world's
     * advancement file is as editable as this one.
     *
     * @return the number of ids actually added.
     */
    public static synchronized int appendAllAbsorbed(UUID playerUuid, String partitionKey,
                                                     Collection<ResourceLocation> advancements) {
        if (advancements.isEmpty()) return 0;
        Key key = new Key(playerUuid, partitionKey);
        Profile current = cached(key);
        Set<ResourceLocation> absorbed = new LinkedHashSet<>(current.absorbed());
        int before = absorbed.size();
        for (ResourceLocation id : advancements) {
            if (!current.granted().contains(id)) absorbed.add(id);
        }
        int added = absorbed.size() - before;
        if (added > 0) store(key, current.withAbsorbed(absorbed));
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
     * @return {@code true} when a profile actually changed.
     */
    public static synchronized boolean remove(UUID playerUuid, String partitionKey, ResourceLocation advancement) {
        boolean removed = removeFrom(new Key(playerUuid, partitionKey), advancement);
        if (SHARED.equals(partitionKey)) {
            removed |= removeFrom(new Key(playerUuid, DifficultyPartition.LEGACY_KEY), advancement);
        }
        return removed;
    }

    /**
     * Remove {@code advancement} from every partition's sidecar — a revocation
     * ({@link AdvancementRevocations}) covers the player, not one difficulty.
     *
     * @return {@code true} when any profile actually changed.
     */
    public static synchronized boolean removeEverywhere(UUID playerUuid, ResourceLocation advancement) {
        boolean removed = false;
        for (String partition : ALL_PARTITIONS) {
            removed |= removeFrom(new Key(playerUuid, partition), advancement);
        }
        return removed;
    }

    private static boolean removeFrom(Key key, ResourceLocation advancement) {
        Profile current = cached(key);
        Set<ResourceLocation> granted = new LinkedHashSet<>(current.granted());
        Set<ResourceLocation> absorbed = new LinkedHashSet<>(current.absorbed());
        boolean removed = granted.remove(advancement) | absorbed.remove(advancement);
        if (!removed) return false;
        store(key, new Profile(granted, absorbed, current.tainted()));
        return true;
    }

    /**
     * Delete every sidecar for {@code playerUuid}, in every partition. Unlike {@link #remove}, this
     * drops every grant at once — used by the Video Tools profile reset, where the point is that the
     * login replay in {@code AchievementEvents} has nothing left to replay.
     *
     * <p>Queued writes land first and the player's cache is dropped, so neither can bring a deleted
     * file back. A taint caught in one of these files goes with it.</p>
     *
     * @return {@code true} when a file was actually removed.
     */
    public static synchronized boolean deleteFor(UUID playerUuid) throws IOException {
        flush();
        evict(playerUuid);
        ProfileIntegrity.clear(playerUuid);
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

    // ---------------- Cache + background writer ----------------

    /** The partition's own file contents, loaded from disk on first use and cached after that. */
    private static Profile cached(Key key) {
        return CACHE.computeIfAbsent(key, k -> {
            Profile pending = PENDING.get(k);
            if (pending != null) return pending;
            Profile loaded = readFile(resolver.apply(k.uuid(), k.partition()), k.uuid(), k.partition());
            // A freshly caught edit is written straight back with the taint under the seal, so the
            // mark survives even if nothing else is ever earned on this profile.
            if (loaded.tainted()) {
                PENDING.put(k, loaded);
                WRITER.execute(() -> writePending(k));
            }
            return loaded;
        });
    }

    /** Make {@code profile} the partition's contents now, and queue it for disk. */
    private static void store(Key key, Profile profile) {
        CACHE.put(key, profile);
        // Only the newest snapshot is kept: a burst of earns queues several tasks, the first writes
        // the final state and the rest find nothing left to do.
        PENDING.put(key, profile);
        WRITER.execute(() -> writePending(key));
    }

    private static void writePending(Key key) {
        Profile snapshot = PENDING.get(key);
        if (snapshot == null) return;
        try {
            writeAtomic(resolver.apply(key.uuid(), key.partition()), key.uuid(), key.partition(), snapshot);
        } catch (RuntimeException e) {
            LOGGER.error("[DungeonTrain] GlobalAchievementStore: background write for {} failed", key, e);
        }
        // Only once it's on disk, and only if no newer snapshot arrived meanwhile — until then a
        // cache miss is served this snapshot rather than the file it is replacing.
        PENDING.remove(key, snapshot);
    }

    /** Wait for every write queued so far to reach the disk. */
    public static void flush() {
        try {
            WRITER.submit(() -> {}).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[DungeonTrain] GlobalAchievementStore: flush interrupted");
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[DungeonTrain] GlobalAchievementStore: flush did not finish: {}", e.toString());
        }
    }

    /**
     * Logout: forget the player's profiles until they're next read. Doesn't wait for the writer —
     * on a slow disk that would stall the server for everyone else — and needn't: a write still
     * queued keeps its snapshot in {@code PENDING}, which {@link #cached} prefers over the file.
     */
    public static void evict(UUID playerUuid) {
        CACHE.keySet().removeIf(key -> key.uuid().equals(playerUuid));
    }

    /**
     * Server stop: land every queued write and empty the cache. The title-screen tools
     * ({@link AdvancementPromotion}, the Video Tools reset) edit the files directly, so a cache
     * outliving its world could hand the next world a stale profile.
     */
    public static void flushAndClearAll() {
        flush();
        CACHE.clear();
    }

    /** Tests only: read and write profiles under {@code dir} instead of the game directory. */
    static void useDirectoryForTests(Path dir) {
        flushAndClearAll();
        resolver = (uuid, partition) -> dir.resolve(fileName(uuid, partition));
        games.brennan.dungeontrain.data.InstallKey.useDirectoryForTests(dir);
    }

    /** Tests only: queue {@code task} on the writer, e.g. to hold it back like a slow disk. */
    static void runOnWriterForTests(Runnable task) {
        WRITER.execute(task);
    }

    /** Tests only: back to the game directory, with nothing cached. */
    static void resetForTests() {
        flushAndClearAll();
        resolver = GlobalAchievementStore::file;
        games.brennan.dungeontrain.data.InstallKey.resetForTests();
    }

    /** Write {@code profile} to {@code path}, sealed for {@code uuid}'s {@code partition}. */
    static void writeAtomic(Path path, UUID uuid, String partition, Profile profile) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] GlobalAchievementStore: failed to create dir {}: {}",
                path.getParent(), e.getMessage());
            return;
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Data data = new Data(List.copyOf(profile.granted()), List.copyOf(profile.absorbed()));
        var result = Data.CODEC.encodeStart(JsonOps.INSTANCE, data);
        if (result.error().isPresent()) {
            LOGGER.error("[DungeonTrain] GlobalAchievementStore: encode failed: {}",
                result.error().get().message());
            return;
        }
        JsonObject element = SealedJson.seal(result.result().orElseThrow().getAsJsonObject(),
            SealedJson.scope(SEAL_STORE, uuid, partition), profile.tainted());
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
