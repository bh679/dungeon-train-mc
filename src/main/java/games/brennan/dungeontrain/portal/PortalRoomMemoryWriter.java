package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Puts {@link PortalRoomMemory}'s files on disk, off the server thread.
 *
 * <p>Saving a room copy used to stop the server for as long as the disk took: a player's log showed
 * 1.7 s for a room of about a thousand blocks, 114 ms typically, with the read itself well under a
 * millisecond. Now a file operation is queued here and the tick moves on. What is queued stays readable
 * through {@link #pending} until the file has landed, so walking back into a copy never waits on the
 * disk and never reads the file it is replacing.</p>
 *
 * <p>Nothing on a server-thread path may wait for this class during play. {@link #flush} is for the
 * server stopping, when no player is left to feel it.</p>
 */
final class PortalRoomMemoryWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long FLUSH_TIMEOUT_SECONDS = 60;

    /** The newest operation queued for each file. */
    private static final Map<Path, Queued> PENDING = new ConcurrentHashMap<>();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DungeonTrain-PortalRoomMemory");
        thread.setDaemon(true);
        return thread;
    });

    private PortalRoomMemoryWriter() {}

    /** One queued file operation: a write of what {@code source} builds, or a delete when it is null. */
    static final class Queued {
        private final Supplier<PortalRoomMemory.Entry> source;
        private final String what;
        private PortalRoomMemory.Entry built;
        private boolean tried;

        private Queued(Supplier<PortalRoomMemory.Entry> source, String what) {
            this.source = source;
            this.what = what;
        }

        boolean isDelete() {
            return source == null;
        }

        /** What this write saves, built once by whichever thread asks first. Null if the build failed. */
        synchronized PortalRoomMemory.Entry entry() {
            if (!tried) {
                tried = true;
                try {
                    built = source.get();
                } catch (Throwable t) {
                    LOGGER.warn("[DungeonTrain] Could not build {} to remember it — it will be stamped from "
                        + "its template next time: {}", what, t.toString());
                }
            }
            return built;
        }
    }

    /**
     * Save what {@code source} builds to {@code file}, then trim its folder to the newest {@code keep}
     * files. {@code source} runs later, on another thread, or on the server thread if the entry is asked
     * for first, so it may only touch what it owns.
     */
    static void write(Path file, Supplier<PortalRoomMemory.Entry> source, int keep, String what) {
        Queued queued = new Queued(source, what);
        PENDING.put(file, queued);
        WRITER.execute(() -> runWrite(file, queued, keep));
    }

    /** Delete {@code file}. It reads as gone from this moment on. */
    static void delete(Path file, String what) {
        Queued queued = new Queued(null, what);
        PENDING.put(file, queued);
        WRITER.execute(() -> runDelete(file, queued));
    }

    /** The operation still queued for {@code file}, or null when the disk is up to date. */
    static Queued pending(Path file) {
        return PENDING.get(file);
    }

    /** Wait for everything queued so far to reach the disk. For the server stopping only. */
    static void flush() {
        try {
            WRITER.submit(() -> {}).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("[DungeonTrain] Remembered portal rooms: flush interrupted");
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("[DungeonTrain] Remembered portal rooms: flush did not finish: {}", e.toString());
        }
    }

    /** Tests only: queue {@code task} on the writer, e.g. to hold it back like a slow disk. */
    static void runOnWriterForTests(Runnable task) {
        WRITER.execute(task);
    }

    private static void runWrite(Path file, Queued queued, int keep) {
        // A newer operation for this file is queued behind us, and it decides what the file holds.
        if (PENDING.get(file) != queued) return;
        try {
            long started = System.nanoTime();
            PortalRoomMemory.Entry entry = queued.entry();
            long built = System.nanoTime();
            if (entry != null && writeFile(file, entry, queued.what)) {
                long written = System.nanoTime();
                // Before the prune, so a read during it is served by the file that just landed.
                PENDING.remove(file, queued);
                prune(file.getParent(), keep);
                LOGGER.debug("[DungeonTrain] {} saved: build {} ms, write {} ms, prune {} ms", queued.what,
                    ms(built - started), ms(written - built), ms(System.nanoTime() - written));
            }
        } catch (Throwable t) {
            // Never let one failure take the writer's thread, or everything queued behind it, down.
            LOGGER.warn("[DungeonTrain] Could not save {}: {}", queued.what, t.toString());
        } finally {
            PENDING.remove(file, queued);
        }
    }

    private static void runDelete(Path file, Queued queued) {
        if (PENDING.get(file) != queued) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not forget {}: {}", queued.what, e.toString());
        } finally {
            PENDING.remove(file, queued);
        }
    }

    private static boolean writeFile(Path file, PortalRoomMemory.Entry entry, String what) {
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(PortalRoomMemory.encode(entry), file);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not remember {} at {}: {}", what, file, e.toString());
            return false;
        }
    }

    /** Keeps the newest {@code keep} files directly in {@code dir}; subfolders are not counted. */
    private static void prune(Path dir, int keep) {
        Map<Path, Long> writtenAt = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(PortalRoomMemory.EXT)).forEach(p -> {
                try {
                    writtenAt.put(p, Files.getLastModifiedTime(p).toMillis());
                } catch (IOException ignored) {
                    // Vanished between the listing and the stat — nothing to prune.
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not list remembered portal rooms in {}: {}", dir, e.toString());
            return;
        }
        for (Path p : PortalRoomMemory.toPrune(writtenAt, keep)) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Could not prune remembered portal room {}: {}", p, e.toString());
            }
        }
    }

    private static String ms(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }
}
