package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grace a sighting buys an entity against vanilla's despawn rule.
 *
 * <p>Both corridors of a pair are registered here every tick a pair is walked, so what is pinned is
 * that a sighting protects for its grace and no longer: a mob the walk stops seeing is handed back
 * to vanilla after {@code GRACE_TICKS}, and one it keeps seeing is never handed back at all.</p>
 */
final class PortalOccupantsTest {

    @AfterEach
    void clear() {
        PortalOccupants.clear();
    }

    @Test
    @DisplayName("A sighting protects until its grace runs out, then lapses")
    void protectsForGrace() {
        assertTrue(PortalOccupants.isEmpty());
        PortalOccupants.protect(41, 1000);

        assertTrue(PortalOccupants.isProtected(41, 1000));
        assertTrue(PortalOccupants.isProtected(41, 1000 + PortalOccupants.GRACE_TICKS - 1));
        assertFalse(PortalOccupants.isProtected(41, 1000 + PortalOccupants.GRACE_TICKS));
        assertFalse(PortalOccupants.isProtected(42, 1000), "an entity never sighted is not protected");
    }

    @Test
    @DisplayName("Seeing it again extends the grace from the new sighting")
    void resightingExtends() {
        PortalOccupants.protect(41, 1000);
        PortalOccupants.protect(41, 1300);

        assertTrue(PortalOccupants.isProtected(41, 1000 + PortalOccupants.GRACE_TICKS + 100));
        assertFalse(PortalOccupants.isProtected(41, 1300 + PortalOccupants.GRACE_TICKS));
    }
}
