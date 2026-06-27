package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pure-logic tests for the bug-report archive's path-segment sanitization, which guards against a
 * hostile player name or filename escaping the archive root. No Minecraft runtime needed.
 */
class BugReportLogStoreTest {

    @Test
    void sanitizeKeepsSafeFilenameAndVersionChars() {
        assertEquals("latest.log.gz", BugReportLogStore.sanitize("latest.log.gz"));
        assertEquals("crash-2024-06-27_12.00.00-client.txt",
                BugReportLogStore.sanitize("crash-2024-06-27_12.00.00-client.txt"));
        assertEquals("0.373.0", BugReportLogStore.sanitize("0.373.0"));
    }

    @Test
    void sanitizeStripsPathSeparatorsAndTraversal() {
        assertFalse(BugReportLogStore.sanitize("../../etc/passwd").contains("/"), "no forward slashes");
        assertFalse(BugReportLogStore.sanitize("..\\..\\windows").contains("\\"), "no backslashes");
        assertEquals("_", BugReportLogStore.sanitize(".."), "parent-dir segment neutralised");
        assertEquals("_", BugReportLogStore.sanitize("."), "current-dir segment neutralised");
    }

    @Test
    void sanitizeFallsBackForNullOrBlank() {
        assertEquals("unknown", BugReportLogStore.sanitize(null));
        assertEquals("unknown", BugReportLogStore.sanitize("   "));
    }

    @Test
    void sanitizeReplacesSpacesAndUnsafeCharsWithUnderscore() {
        assertEquals("Some_Name_", BugReportLogStore.sanitize("Some Name!"));
    }
}
