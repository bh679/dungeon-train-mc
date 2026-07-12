package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Per-run disk store for offloaded ride photos. Lives at
 * {@code <gameDirectory>/dungeontrain/ride-snapshots/} and holds the current run's flushed
 * photos as small (&le;640px) PNGs. The directory is wiped on world join (clearing any orphan
 * left by a crashed prior run) and on world leave by {@link RideSnapshotGallery} /
 * {@link RideSnapshotDirector}.
 *
 * <p>Pure I/O — it does <em>not</em> consult the config. Whether to offload at all is decided
 * upstream (the director gates flushing on {@code diskOffloadEnabled} + performance + an open
 * menu); a lazy {@link #read} at the death screen always loads whatever file exists.</p>
 *
 * <p>Every method swallows its own I/O errors (logs, returns {@code null} / no-ops) so a failed
 * write or read can never throw into the client tick / render loop — the worst case is a photo
 * that falls back to the death screen's solid overlay.</p>
 */
public final class RideSnapshotDisk {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong SEQ = new AtomicLong();

    private RideSnapshotDisk() {}

    /** {@code <gameDirectory>/dungeontrain/ride-snapshots}, or {@code null} if the client isn't ready. */
    private static Path runDir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return null;
        return mc.gameDirectory.toPath().resolve("dungeontrain").resolve("ride-snapshots");
    }

    /**
     * Write {@code image} to a uniquely-named PNG and return its path, or {@code null} on failure.
     * Written to a {@code .tmp} sibling first and then atomically moved into place, so a crash
     * mid-write can never leave a truncated PNG that a later {@link #read} would surface as a photo.
     */
    public static Path write(NativeImage image) {
        if (image == null) return null;
        Path dir = runDir();
        if (dir == null) return null;
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            long id = SEQ.incrementAndGet();
            Path target = dir.resolve("ride-" + id + ".png");
            tmp = dir.resolve("ride-" + id + ".png.tmp");
            image.writeToFile(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                // Some filesystems don't support ATOMIC_MOVE — fall back to a plain replace.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Ride snapshot disk write failed", e);
            deleteQuietly(tmp);
            return null;
        }
    }

    /** Read a previously-written PNG back into a {@link NativeImage}, or {@code null} on failure. */
    public static NativeImage read(Path path) {
        if (path == null) return null;
        try (InputStream in = Files.newInputStream(path)) {
            return NativeImage.read(in);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Ride snapshot disk read failed for {}", path, e);
            return null;
        }
    }

    /** Delete a single offloaded file (eviction beyond the total cap). Quiet on any error. */
    public static void delete(Path path) {
        deleteQuietly(path);
    }

    /** Remove the whole run directory and its contents. Safe to call when it was never created. */
    public static void deleteRunDir() {
        Path dir = runDir();
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(RideSnapshotDisk::deleteQuietly);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Ride snapshot disk cleanup failed for {}", dir, e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Best-effort: a leftover file is harmless (wiped on the next join/leave).
        }
    }
}
