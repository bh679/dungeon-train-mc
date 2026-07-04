package games.brennan.dungeontrain.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.junit.jupiter.api.Test;

/**
 * Verifies the log-spam filter (deliverable 1) denies ONLY Sable's "abnormally large AABB" abort line
 * and lets every other Sable message through — so the fix stops the render-thread I/O storm without
 * raising Sable's log level. Exercises each {@code filter(...)} hook a LoggerConfig filter can be
 * consulted through, using the exact format string Sable logs.
 */
class SableAabbLogFilterTest {

    /** Byte-for-byte the format Sable's {@code SubLevelInclusiveLevelEntityGetter.logError} logs. */
    private static final String ABORT_FORMAT = "Aborting entity get for abnormally large AABB: {}";

    private final SableAabbLogFilter filter = new SableAabbLogFilter();

    @Test
    void deniesAbortMessageViaLogEvent() {
        // The definitive path for a LoggerConfig-attached filter: LoggerConfig.log(event) -> filter(event).
        Message msg = new ParameterizedMessage(ABORT_FORMAT, "AABB[...] -> [...]");
        LogEvent event = Log4jLogEvent.newBuilder().setMessage(msg).setLevel(Level.ERROR).build();
        assertEquals(Filter.Result.DENY, filter.filter(event), "abort line must be denied");
    }

    @Test
    void deniesAbortMessageViaMessageHook() {
        Message msg = new ParameterizedMessage(ABORT_FORMAT, "AABB[...]");
        assertEquals(Filter.Result.DENY,
                filter.filter((Logger) null, Level.ERROR, (Marker) null, msg, (Throwable) null));
    }

    @Test
    void deniesAbortMessageViaStringHook() {
        assertEquals(Filter.Result.DENY,
                filter.filter((Logger) null, Level.ERROR, (Marker) null, ABORT_FORMAT, new Object[]{"x"}));
    }

    @Test
    void passesOtherSableMessages() {
        // A representative unrelated Sable log line (Sable's startup "{} loaded!") must NOT be filtered.
        Message ok = new ParameterizedMessage("{} loaded!", "Sable");
        LogEvent event = Log4jLogEvent.newBuilder().setMessage(ok).setLevel(Level.INFO).build();
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event), "unrelated Sable logs must pass");
        assertEquals(Filter.Result.NEUTRAL,
                filter.filter((Logger) null, Level.INFO, (Marker) null, ok, (Throwable) null));
    }

    @Test
    void passesNullAndUnrelatedFormats() {
        assertEquals(Filter.Result.NEUTRAL,
                filter.filter((Logger) null, Level.WARN, (Marker) null, (String) null, new Object[0]));
        assertEquals(Filter.Result.NEUTRAL,
                filter.filter((Logger) null, Level.WARN, (Marker) null, "some other warning {}", new Object[]{1}));
    }
}
