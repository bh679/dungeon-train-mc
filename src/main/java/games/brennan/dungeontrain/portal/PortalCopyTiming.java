package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.util.AsyncPerfLog;
import games.brennan.dungeontrain.util.DtLogging;
import games.brennan.dungeontrain.util.ThrottledLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * The {@code [portal.copy]} line: what one room copy cost the tick, split by phase.
 *
 * <p>Fresh stamps and erases used to leave no timing at all, so a player log with a 1.2 s tick in
 * the middle of copy work could not say which copy or which phase it was. The phases are marked from
 * {@link PortalRoomTiler} and {@link PortalCarriageBuilder}, whose stamp path is shared with base rooms
 * and the editor: {@link #mark} does nothing unless a copy is being timed, so those stay untimed.</p>
 *
 * <p>Server thread only, like the tiler that drives it, and one copy at a time.</p>
 */
final class PortalCopyTiming {

    enum Kind { FRESH, RESTORED, ERASED }

    enum Phase { READ, SAVE, SWEEP, CLEAR, TEMPLATE, PASSES, FACES }

    /** Below this a copy is ordinary: a median room takes a few milliseconds. */
    static final long LOG_FLOOR_NANOS = 50_000_000L;
    /** From here a line always gets through, because these are the ticks being looked for. */
    static final long ALWAYS_LOG_NANOS = 400_000_000L;

    private static final Logger PERF_LOGGER = LoggerFactory.getLogger(DtLogging.PERF);
    /** A big room passes the floor on every copy, which would crowd the 512 KB log tail. */
    private static final ThrottledLog ROUTINE_LOG = new ThrottledLog(2_000);

    private static final long[] PHASE_NANOS = new long[Phase.values().length];
    private static boolean timing;
    private static long startedAt;
    private static long lastMark;

    private PortalCopyTiming() {}

    static void begin() {
        Arrays.fill(PHASE_NANOS, 0L);
        startedAt = System.nanoTime();
        lastMark = startedAt;
        timing = true;
    }

    /** Charge the time since the last mark to {@code phase}. */
    static void mark(Phase phase) {
        if (!timing) return;
        long now = System.nanoTime();
        PHASE_NANOS[phase.ordinal()] += now - lastMark;
        lastMark = now;
    }

    static void finish(Kind kind, String roomName, int pairKey, PortalRoomTiling.Tile tile) {
        timing = false;
        long total = System.nanoTime() - startedAt;
        if (total < LOG_FLOOR_NANOS) return;
        OptionalLong routine = ROUTINE_LOG.record();
        if (!shouldLog(total, routine.isPresent())) return;
        AsyncPerfLog.debug(PERF_LOGGER,
            "[portal.copy] pair={} copy={},{} room={} kind={} totalMs={} readMs={} saveMs={} sweepMs={} clearMs={} templateMs={} passesMs={} facesMs={} slowCopies={}",
            pairKey, tile.x(), tile.z(), roomName, kind.name(), millis(total),
            millis(PHASE_NANOS[Phase.READ.ordinal()]), millis(PHASE_NANOS[Phase.SAVE.ordinal()]),
            millis(PHASE_NANOS[Phase.SWEEP.ordinal()]), millis(PHASE_NANOS[Phase.CLEAR.ordinal()]),
            millis(PHASE_NANOS[Phase.TEMPLATE.ordinal()]), millis(PHASE_NANOS[Phase.PASSES.ordinal()]),
            millis(PHASE_NANOS[Phase.FACES.ordinal()]), routine.orElse(1L));
    }

    /** Package-private and pure, unit-tested. */
    static boolean shouldLog(long totalNanos, boolean routineSlotFree) {
        if (totalNanos < LOG_FLOOR_NANOS) return false;
        return routineSlotFree || totalNanos >= ALWAYS_LOG_NANOS;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }
}
