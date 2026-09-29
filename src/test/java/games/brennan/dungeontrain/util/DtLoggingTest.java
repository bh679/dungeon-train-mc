package games.brennan.dungeontrain.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the jitter-DEBUG switch: explicit property wins, else dev-on / production-off. */
class DtLoggingTest {

    @Test
    void defaultsOffInProductionAndOnInDev() {
        assertFalse(DtLogging.jitterDebugEnabled(null, true));
        assertTrue(DtLogging.jitterDebugEnabled(null, false));
        assertFalse(DtLogging.jitterDebugEnabled("  ", true));
    }

    @Test
    void explicitPropertyOverridesEnvironment() {
        assertTrue(DtLogging.jitterDebugEnabled("true", true));
        assertTrue(DtLogging.jitterDebugEnabled(" TRUE ", true));
        assertFalse(DtLogging.jitterDebugEnabled("false", false));
        assertFalse(DtLogging.jitterDebugEnabled("nope", false));
    }
}
