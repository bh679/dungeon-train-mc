package games.brennan.dungeontrain.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backup mode's parsing and the operator override.
 *
 * <p>The theme of every case here is the same: an unreadable, missing or mistyped value must
 * resolve to the DEFAULT and never to {@link BackupMode#OFF}. This is read on the server thread
 * where nobody is watching, so the failure mode of a typo has to be "still backing up".</p>
 */
class BackupModeTest {

    @Test
    void unknownAndMissingValuesFallBackToTheDefault() {
        assertEquals(BackupMode.EXTERNAL, BackupMode.DEFAULT);
        assertEquals(BackupMode.DEFAULT, BackupMode.parse(null));
        assertEquals(BackupMode.DEFAULT, BackupMode.parse(""));
        assertEquals(BackupMode.DEFAULT, BackupMode.parse("   "));
        assertEquals(BackupMode.DEFAULT, BackupMode.parse("offf"),
            "a typo must not silently disable backups");
        assertEquals(BackupMode.DEFAULT, BackupMode.parse("disabled"));
    }

    @Test
    void acceptsBothTheEnumNamesAndTheWordsTheOptionsScreenShows() {
        // An operator copies what they saw in the UI, not the constant name.
        assertEquals(BackupMode.EXTERNAL, BackupMode.parse("on"));
        assertEquals(BackupMode.EXTERNAL, BackupMode.parse("EXTERNAL"));
        assertEquals(BackupMode.INSTANCE, BackupMode.parse("instanced"));
        assertEquals(BackupMode.INSTANCE, BackupMode.parse("instance"));
        assertEquals(BackupMode.OFF, BackupMode.parse("off"));
        assertEquals(BackupMode.OFF, BackupMode.parse("  Off  "), "trimmed and case-insensitive");
    }

    @Test
    void overrideIsAbsentWhenNeitherSourceIsSet() {
        assertNull(BackupMode.overrideFrom(null, null));
        assertNull(BackupMode.overrideFrom("", null));
        assertNull(BackupMode.overrideFrom(null, "   "));
    }

    @Test
    void thePropertyWinsOverTheEnvironment() {
        assertEquals(BackupMode.OFF, BackupMode.overrideFrom("off", "on"));
        assertEquals(BackupMode.EXTERNAL, BackupMode.overrideFrom(null, "on"));
        assertEquals(BackupMode.INSTANCE, BackupMode.overrideFrom("  ", "instanced"),
            "a blank property is not a value; fall through to the environment");
    }

    @Test
    void whatEachModeActuallyDoes() {
        assertTrue(BackupMode.EXTERNAL.writesAnything());
        assertTrue(BackupMode.EXTERNAL.writesOutsideTheInstance());
        assertTrue(BackupMode.INSTANCE.writesAnything());
        assertFalse(BackupMode.INSTANCE.writesOutsideTheInstance());
        assertFalse(BackupMode.OFF.writesAnything());
        assertFalse(BackupMode.OFF.writesOutsideTheInstance());
    }
}
