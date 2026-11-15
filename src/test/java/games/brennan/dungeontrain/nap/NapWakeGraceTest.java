package games.brennan.dungeontrain.nap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NapWakeGrace}: held through the placement, then 10 seconds more. */
final class NapWakeGraceTest {

    @AfterEach
    void reset() {
        NapWakeGrace.clear();
    }

    @Test
    @DisplayName("the grace lasts while placement is pending and 200 ticks after")
    void closesTenSecondsAfterPlacement() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        NapWakeGrace.open(player);
        assertTrue(NapWakeGrace.isActive(player));
        assertFalse(NapWakeGrace.isActive(other));

        for (int i = 0; i < 500; i++) NapWakeGrace.tick(p -> true);
        assertTrue(NapWakeGrace.isActive(player), "still being placed");

        for (int i = 0; i < NapWakeGrace.AFTER_PLACEMENT_TICKS - 1; i++) NapWakeGrace.tick(p -> false);
        assertTrue(NapWakeGrace.isActive(player));
        NapWakeGrace.tick(p -> false);
        assertFalse(NapWakeGrace.isActive(player), "closed after 200 ticks on the deck");
    }

    @Test
    @DisplayName("logging out ends it")
    void closeEnds() {
        UUID player = UUID.randomUUID();
        NapWakeGrace.open(player);
        NapWakeGrace.close(player);
        assertFalse(NapWakeGrace.isActive(player));
    }
}
