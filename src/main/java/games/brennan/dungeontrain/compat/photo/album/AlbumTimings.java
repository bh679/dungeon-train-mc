package games.brennan.dungeontrain.compat.photo.album;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * How long album work holds the server thread. Anything that takes {@link #NOTICE_MS} or more is
 * logged at INFO as {@code [DT-Album] <label> took N ms}, so a hitch players notice can be told
 * apart from the train's own load in the same log; the rest is DEBUG.
 */
final class AlbumTimings {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final long NOTICE_MS = 20;

    private AlbumTimings() {}

    static <T> T time(String label, Supplier<T> work) {
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            log(label, (System.nanoTime() - start) / 1_000_000L);
        }
    }

    static void time(String label, Runnable work) {
        time(label, () -> {
            work.run();
            return null;
        });
    }

    private static void log(String label, long ms) {
        if (ms >= NOTICE_MS) LOGGER.info("[DT-Album] {} took {} ms", label, ms);
        else LOGGER.debug("[DT-Album] {} took {} ms", label, ms);
    }
}
