package games.brennan.dungeontrain.portal.tribute;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import java.util.UUID;

/**
 * Where a player's tribute room lives between runs: one compressed NBT file per player under the
 * player-data root ({@code <gameDir>/dungeontrain/tribute-rooms/<uuid>.nbt}), beside the achievements and
 * the other things that are the player's rather than the world's.
 *
 * <p>Outside any world on purpose. In singleplayer every death ends in a <em>new</em> world, so a room
 * kept in the world would be gone with it; here it is the player's, whatever world they are in.</p>
 */
public final class TributeRoomStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SUB_DIR = "tribute-rooms";
    private static final long FLUSH_TIMEOUT_SECONDS = 60;

    /**
     * Every read and write of a room file, in the order asked: one thread, so a load queued after a
     * save always finds that save. The server thread never waits on it during play ({@link #flush} is
     * for the server stopping).
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DungeonTrain-TributeRoomIO");
        thread.setDaemon(true);
        return thread;
    });

    /** The newest room queued for each file and not on disk yet: what a read of that file returns meanwhile. */
    private static final Map<Path, TributeRoomBlob> PENDING = new ConcurrentHashMap<>();

    private TributeRoomStore() {}

    /**
     * {@code <gameDir>/dungeontrain/tribute-rooms/} — created on first save.
     *
     * <p>Beside the player-data folders, not under the user content root: anything under
     * {@code user/} is authored content, which the custom-content question asks about at every
     * reboard and which Free Play is gated on. A tribute room is the player's save, not their content.</p>
     */
    public static Path directory() {
        return PlayerDataPaths.dir(SUB_DIR);
    }

    /** {@code <uuid>.<key>.nbt}: one file per player per difficulty / Free Play ({@link TributeRoomKeys}). */
    static Path fileFor(String owner, String key) {
        return directory().resolve(owner + "." + key + ".nbt");
    }

    /** The file rooms were saved to before they were keyed: {@code <uuid>.nbt}, read when its difficulty is {@code key}. */
    static Path legacyFileFor(String owner) {
        return directory().resolve(owner + ".nbt");
    }

    /** A uuid as every relay-facing id writes it. */
    public static String dashless(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    /** The saved room of {@code owner} under {@code key}, when there is one that reads. */
    public static Optional<TributeRoomBlob> load(UUID owner, String key) {
        return loadByOwner(dashless(owner), key);
    }

    /**
     * Every owner (dashless uuid) with a room saved under {@code key}, sorted, so a seeded draw is
     * stable. A room from before rooms were keyed counts when it was last saved under that difficulty.
     */
    public static List<String> owners(String key) {
        Path dir = directory();
        if (!Files.isDirectory(dir)) return List.of();
        String suffix = "." + key + ".nbt";
        try (Stream<Path> files = Files.list(dir)) {
            java.util.TreeSet<String> owners = new java.util.TreeSet<>();
            for (String n : files.map(f -> f.getFileName().toString()).toList()) {
                if (n.endsWith(suffix) && n.length() == 32 + suffix.length()) {
                    owners.add(n.substring(0, 32));
                } else if (n.endsWith(".nbt") && n.length() == 36) {
                    String owner = n.substring(0, 32);
                    if (!owners.contains(owner) && read(legacyFileFor(owner)).filter(b -> key.equals(b.difficulty())).isPresent()) {
                        owners.add(owner);
                    }
                }
            }
            return List.copyOf(owners);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] tribute room: could not list {}: {}", dir, e.toString());
            return List.of();
        }
    }

    /**
     * The saved room of the owner with dashless uuid {@code owner} under {@code key}, when it reads: the
     * keyed file, else the un-keyed one from before when its difficulty is {@code key}.
     */
    public static Optional<TributeRoomBlob> loadByOwner(String owner, String key) {
        Optional<TributeRoomBlob> keyed = read(fileFor(owner, key));
        if (keyed.isPresent()) return keyed;
        return read(legacyFileFor(owner)).filter(b -> key.equals(b.difficulty()));
    }

    /** {@link #load}, read on the IO thread. Completes there; the caller hops back to the server itself. */
    public static CompletableFuture<Optional<TributeRoomBlob>> loadAsync(UUID owner, String key) {
        return CompletableFuture.supplyAsync(() -> load(owner, key), IO);
    }

    /** {@link #loadByOwner}, read on the IO thread. Completes there. */
    public static CompletableFuture<Optional<TributeRoomBlob>> loadByOwnerAsync(String owner, String key) {
        return CompletableFuture.supplyAsync(() -> loadByOwner(owner, key), IO);
    }

    private static Optional<TributeRoomBlob> read(Path file) {
        TributeRoomBlob pending = PENDING.get(file);
        if (pending != null) return Optional.of(pending);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            TributeRoomBlob blob = TributeRoomBlob.decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
            if (blob == null) LOGGER.warn("[DungeonTrain] tribute room: {} holds no room — ignoring it", file);
            return Optional.ofNullable(blob);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] tribute room: could not read {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Queue {@code blob} to be written as its owner's room under its key ({@code difficulty}), and return
     * at once. Reads see it from this moment on. Completes on the IO thread with whether it landed.
     */
    public static CompletableFuture<Boolean> saveAsync(TributeRoomBlob blob) {
        if (!TributeRoomKeys.isKey(blob.difficulty())) {
            LOGGER.warn("[DungeonTrain] tribute room: {}'s room has no key to save under ('{}')", blob.ownerName(), blob.difficulty());
            return CompletableFuture.completedFuture(false);
        }
        Path file = fileFor(blob.owner(), blob.difficulty());
        PENDING.put(file, blob);
        return CompletableFuture.supplyAsync(() -> {
            try {
                // A newer save of this file is queued behind this one; that one decides what it holds.
                if (PENDING.get(file) != blob) return true;
                long started = System.nanoTime();
                boolean ok = write(file, blob);
                LOGGER.debug("[DungeonTrain] tribute room: wrote {} in {} ms", file.getFileName(), (System.nanoTime() - started) / 1_000_000L);
                return ok;
            } catch (Throwable t) {
                // Never let one bad room take the IO thread, or everything queued behind it, down.
                LOGGER.warn("[DungeonTrain] tribute room: could not save {}'s room: {}", blob.ownerName(), t.toString());
                return false;
            } finally {
                PENDING.remove(file, blob);
            }
        }, IO);
    }

    /** Run {@code task} on the room files' IO thread, in order with every read and write queued so far. */
    static CompletableFuture<Void> runIo(Runnable task) {
        return CompletableFuture.runAsync(task, IO);
    }

    /** {@code task}'s answer, computed on the room files' IO thread. */
    static <T> CompletableFuture<T> supplyIo(java.util.function.Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, IO);
    }

    /** Wait for every queued read and write to finish. For the server stopping only. */
    public static void flush() {
        try {
            IO.submit(() -> {}).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[DungeonTrain] tribute room: flush interrupted");
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[DungeonTrain] tribute room: flush did not finish: {}", e.toString());
        }
    }

    /** Atomic: a crash mid-write leaves the old file. IO thread. */
    private static boolean write(Path file, TributeRoomBlob blob) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(blob.encode(), tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] tribute room: could not save {}'s room to {}: {}", blob.ownerName(), file, e.toString());
            return false;
        }
    }

    /**
     * The untouched default for {@code player}: the bundled template's blocks, read through the same
     * template store the stamp reads, so what this returns is exactly what would stand without a blob.
     * Empty when the template is missing, which is a build fault worth refusing the visit over.
     */
    public static Optional<TributeRoomBlob> defaultFor(ServerLevel level, ServerPlayer player, CarriageDims dims) {
        Optional<StructureTemplate> template = PortalRoomTemplateStore.get(level, TributeRooms.TEMPLATE, dims);
        if (template.isEmpty()) {
            LOGGER.error("[DungeonTrain] tribute room: bundled template '{}' is missing", TributeRooms.TEMPLATE);
            return Optional.empty();
        }
        CompoundTag templateTag = template.get().save(new CompoundTag());
        CompoundTag snapshot = TributeRoomLegacy.migrate(CarriageSnapshotTemplate.fromTemplateTag(templateTag));
        return Optional.of(TributeRoomBlob.initial(dashless(player.getUUID()), player.getGameProfile().getName(), snapshot)
            .withKey(TributeRoomKeys.keyFor(level, player)));
    }

    /**
     * {@code player}'s room, or the default when they have none — or when what is on disk is not the
     * one box a tribute room is laid into (a template resize since it was saved): that file is kept, but
     * the visit starts from the default rather than clipping it.
     */
    public static Optional<TributeRoomBlob> loadOrDefault(ServerLevel level, ServerPlayer player, CarriageDims dims) {
        return orDefault(level, player, dims, load(player.getUUID(), TributeRoomKeys.keyFor(level, player)));
    }

    /** {@link #loadOrDefault} once {@code saved} has been read: the size check and the default. Server thread. */
    public static Optional<TributeRoomBlob> orDefault(ServerLevel level, ServerPlayer player, CarriageDims dims,
                                                      Optional<TributeRoomBlob> saved) {
        if (saved.isPresent()) {
            if (saved.get().matches(TributeRooms.size(level, dims))) return saved;
            LOGGER.warn("[DungeonTrain] tribute room: {}'s saved room is not the template's size — starting from the default",
                player.getGameProfile().getName());
        }
        return defaultFor(level, player, dims);
    }
}
