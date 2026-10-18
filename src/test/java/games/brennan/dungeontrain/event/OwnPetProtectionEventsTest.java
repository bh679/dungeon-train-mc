package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnPetProtectionEventsTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void ownerHittingOwnPetIsBlocked() {
        assertTrue(OwnPetProtectionEvents.isOwnPet(OWNER, OWNER));
    }

    @Test
    void anotherPlayersPetCanBeHit() {
        assertFalse(OwnPetProtectionEvents.isOwnPet(UUID.randomUUID(), OWNER));
    }

    @Test
    void wildAnimalCanBeHit() {
        assertFalse(OwnPetProtectionEvents.isOwnPet(OWNER, null));
    }
}
