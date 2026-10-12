package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The container-spill scope nests and never underflows, so a stray {@code end()} can't open it early. */
class ContainerSpillTest {

    @Test
    void nestsAndClosesCleanly() {
        assertFalse(ContainerSpill.inProgress());
        ContainerSpill.begin();
        ContainerSpill.begin();
        ContainerSpill.end();
        assertTrue(ContainerSpill.inProgress());
        ContainerSpill.end();
        assertFalse(ContainerSpill.inProgress());
    }

    @Test
    void extraEndDoesNotUnderflow() {
        ContainerSpill.end();
        ContainerSpill.begin();
        assertTrue(ContainerSpill.inProgress());
        ContainerSpill.end();
        assertFalse(ContainerSpill.inProgress());
    }
}
