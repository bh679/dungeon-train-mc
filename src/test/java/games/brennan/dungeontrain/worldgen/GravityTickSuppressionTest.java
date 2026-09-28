package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GravityTickSuppressionTest {

    @Test
    void inactiveOutsideRun() {
        assertFalse(GravityTickSuppression.isActive());
    }

    @Test
    void activeInsideRunAndReentrant() {
        GravityTickSuppression.run(() -> {
            assertTrue(GravityTickSuppression.isActive());
            GravityTickSuppression.run(() -> assertTrue(GravityTickSuppression.isActive()));
            assertTrue(GravityTickSuppression.isActive(), "inner release must not clear the outer hold");
        });
        assertFalse(GravityTickSuppression.isActive());
    }

    @Test
    void releasedAfterException() {
        assertThrows(IllegalStateException.class, () -> GravityTickSuppression.run(() -> {
            throw new IllegalStateException("boom");
        }));
        assertFalse(GravityTickSuppression.isActive());
    }

    @Test
    void otherThreadsUnaffected() throws InterruptedException {
        boolean[] seen = {true};
        GravityTickSuppression.run(() -> {
            Thread t = new Thread(() -> seen[0] = GravityTickSuppression.isActive());
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertFalse(seen[0]);
    }
}
