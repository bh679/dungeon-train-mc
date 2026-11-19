package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vault-eject scope nests and never underflows, so a stray {@code end()} can't open it early. */
class VaultEjectionTest {

    @Test
    void nestsAndClosesCleanly() {
        assertFalse(VaultEjection.inProgress());
        VaultEjection.begin();
        VaultEjection.begin();
        VaultEjection.end();
        assertTrue(VaultEjection.inProgress());
        VaultEjection.end();
        assertFalse(VaultEjection.inProgress());
    }

    @Test
    void extraEndDoesNotUnderflow() {
        VaultEjection.end();
        VaultEjection.begin();
        assertTrue(VaultEjection.inProgress());
        VaultEjection.end();
        assertFalse(VaultEjection.inProgress());
    }
}
