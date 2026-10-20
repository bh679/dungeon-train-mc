package games.brennan.dungeontrain.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage of {@link AsyncPerfLog}'s queue: order, overflow policy and the dropped-count notice. */
final class AsyncPerfLogTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("test.perf");

    private static AsyncPerfLog.Entry entry(String format) {
        return new AsyncPerfLog.Entry(LOGGER, format, new Object[0]);
    }

    private static List<String> drainAll(AsyncPerfLog log, List<String> out) throws InterruptedException {
        while (!log.isEmpty()) log.drainOnce();
        return out;
    }

    @Test
    @DisplayName("lines come out in the order they went in")
    void keepsFifoOrder() throws InterruptedException {
        List<String> out = new ArrayList<>();
        AsyncPerfLog log = new AsyncPerfLog(8, e -> out.add(e.format()));
        log.submit(entry("[mspt] a"));
        log.submit(entry("[sweep.perf] b"));
        log.submit(entry("[freeze] c"));
        assertEquals(List.of("[mspt] a", "[sweep.perf] b", "[freeze] c"), drainAll(log, out));
    }

    @Test
    @DisplayName("a full queue drops the oldest line and reports how many were lost before the next one")
    void overflowDropsOldestAndReportsCount() throws InterruptedException {
        List<String> out = new ArrayList<>();
        AsyncPerfLog log = new AsyncPerfLog(2, e -> out.add(e.format()));
        log.submit(entry("1"));
        log.submit(entry("2"));
        log.submit(entry("3"));
        log.submit(entry("4"));
        drainAll(log, out);
        assertEquals(3, out.size());
        assertTrue(out.get(0).startsWith("[perf.log] dropped"), out.get(0));
        assertEquals(List.of("3", "4"), out.subList(1, 3));
    }

    @Test
    @DisplayName("the dropped notice is sent once, then the count starts again")
    void droppedNoticeResets() throws InterruptedException {
        List<String> out = new ArrayList<>();
        AsyncPerfLog log = new AsyncPerfLog(1, e -> out.add(e.format()));
        log.submit(entry("1"));
        log.submit(entry("2"));
        log.drainOnce();
        log.submit(entry("3"));
        log.drainOnce();
        assertEquals(List.of("[perf.log] dropped {} queued line(s) while the log file was slow", "2", "3"), out);
    }
}
