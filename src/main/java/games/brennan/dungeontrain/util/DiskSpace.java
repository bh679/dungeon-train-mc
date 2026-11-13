package games.brennan.dungeontrain.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Free-space checks behind the low-storage warning (client: {@code client.LowDiskSpaceWarning},
 * dedicated server: {@code event.ServerLowDiskSpaceWarning}).
 *
 * <p>A full drive kills the game mid-save with no warning, and Dungeon Train worlds keep growing
 * as the train rides into new terrain. A player whose C: drive had 3.2 GB free lost two runs that
 * way (2026-11), so the game warns well before the drive is full: LOW under 5 GB, CRITICAL under
 * 1 GB. Each level is left only once space climbs a little past its threshold, so a drive
 * hovering at the line doesn't flap.</p>
 */
public final class DiskSpace {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final long MB = 1024L * 1024L;
    public static final long GB = 1024L * MB;

    static final long LOW_BELOW = 5 * GB;
    static final long LOW_CLEARS_ABOVE = 6 * GB;
    static final long CRITICAL_BELOW = 1 * GB;
    static final long CRITICAL_CLEARS_ABOVE = 3 * GB / 2;

    /** How often a CRITICAL warning repeats while it lasts. */
    static final long CRITICAL_REPEAT_MS = 2 * 60 * 1000L;

    /** Dev builds only: pretend the drive has this many MB free, to see each warning (Gate 2). */
    public static final String FAKE_FREE_MB_PROPERTY = "dungeontrain.diskSpace.fakeFreeMB";

    private static final AtomicBoolean LOGGED_FAILURE = new AtomicBoolean(false);

    public enum Level { OK, LOW, CRITICAL }

    private DiskSpace() {}

    /** The level {@code usableBytes} puts the drive at, given the level it was at before. */
    public static Level level(Level previous, long usableBytes) {
        if (usableBytes < CRITICAL_BELOW) return Level.CRITICAL;
        if (previous == Level.CRITICAL && usableBytes <= CRITICAL_CLEARS_ABOVE) return Level.CRITICAL;
        if (usableBytes < LOW_BELOW) return Level.LOW;
        if (previous != Level.OK && usableBytes <= LOW_CLEARS_ABOVE) return Level.LOW;
        return Level.OK;
    }

    /** "3.2 GB" at a gigabyte or more, else "412 MB". */
    public static String format(long bytes) {
        if (bytes >= GB) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) GB);
        return Math.max(0, bytes / MB) + " MB";
    }

    /**
     * Usable bytes on the drive holding {@code path}, or empty if it can't be read. Never throws;
     * the first failure is logged. Can be slow on a network drive, so call it off the game threads.
     */
    public static OptionalLong usableBytes(Path path, boolean devBuild) {
        if (devBuild) {
            OptionalLong fake = fakeFreeBytes();
            if (fake.isPresent()) return fake;
        }
        try {
            return OptionalLong.of(Files.getFileStore(path).getUsableSpace());
        } catch (IOException | RuntimeException e) {
            if (LOGGED_FAILURE.compareAndSet(false, true)) {
                LOGGER.warn("[DungeonTrain] Couldn't read free disk space for {}: {}", path, e.toString());
            }
            return OptionalLong.empty();
        }
    }

    private static OptionalLong fakeFreeBytes() {
        String s = System.getProperty(FAKE_FREE_MB_PROPERTY);
        if (s == null) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(s.trim()) * MB);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * When to warn, as an immutable state: feed each reading to {@link #next}, and announce the
     * returned {@link Step#announce()} if it isn't {@link Level#OK}. LOW is announced once each
     * time the drive drops into it; CRITICAL on entry and every {@link #CRITICAL_REPEAT_MS} after.
     */
    public record Alarm(Level level, long lastCriticalMs) {

        public static final Alarm START = new Alarm(Level.OK, 0L);

        public record Step(Alarm alarm, Level announce) {}

        public Step next(long usableBytes, long nowMs) {
            Level now = DiskSpace.level(level, usableBytes);
            if (now == Level.CRITICAL) {
                boolean due = level != Level.CRITICAL || nowMs - lastCriticalMs >= CRITICAL_REPEAT_MS;
                return due
                    ? new Step(new Alarm(now, nowMs), Level.CRITICAL)
                    : new Step(new Alarm(now, lastCriticalMs), Level.OK);
            }
            Level announce = now == Level.LOW && level == Level.OK ? Level.LOW : Level.OK;
            return new Step(new Alarm(now, lastCriticalMs), announce);
        }
    }
}
