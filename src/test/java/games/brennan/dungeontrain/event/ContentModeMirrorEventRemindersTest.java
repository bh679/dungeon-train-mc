package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player the server has no Event reminders answer for (an older client, or a sync not landed yet)
 * still gets the countdown: only an explicit off from the Options row stops it.
 */
final class ContentModeMirrorEventRemindersTest {

    @Test
    @DisplayName("an unknown player gets event reminders")
    void unknownPlayerIsOn() {
        assertTrue(ContentModeMirror.isEventRemindersOn(UUID.randomUUID()));
        assertTrue(ContentModeMirror.isEventRemindersOn(null));
    }
}
