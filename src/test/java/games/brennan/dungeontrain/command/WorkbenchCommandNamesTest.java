package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.train.CarriageVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The names a Workbench commit falls back to when the one asked for is taken. */
final class WorkbenchCommandNamesTest {

    @Test
    @DisplayName("a taken name gets a numeric suffix, in order")
    void suffixed() {
        assertEquals("test1_2", WorkbenchCommand.nextFreeName("test1", 2));
        assertEquals("test1_13", WorkbenchCommand.nextFreeName("test1", 13));
        assertEquals("cabin_2", WorkbenchCommand.nextFreeName("Cabin", 2));
    }

    @Test
    @DisplayName("the suffixed name always fits the shared 32-character name rule")
    void fitsNameRule() {
        String longName = "a_very_long_template_name_that_is_32";
        for (int n = 2; n < WorkbenchCommand.MAX_RENAME_TRIES; n++) {
            String candidate = WorkbenchCommand.nextFreeName(longName, n);
            assertTrue(CarriageVariant.NAME_PATTERN.matcher(candidate).matches(), candidate);
            assertTrue(candidate.endsWith("_" + n), candidate);
        }
    }
}
