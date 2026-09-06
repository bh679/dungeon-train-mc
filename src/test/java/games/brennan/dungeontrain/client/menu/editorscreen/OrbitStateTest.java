package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OrbitStateTest {

    @Test
    @DisplayName("spins on its own at the tile speed and wraps into [0, 360)")
    void spins() {
        OrbitState o = new OrbitState();
        assertEquals(OrbitState.REST_YAW, o.yaw());
        o.advance(1.0F);
        assertEquals(OrbitState.REST_YAW + OrbitState.SPIN_DEGREES_PER_SECOND, o.yaw(), 1e-4);
        for (int i = 0; i < 20; i++) o.advance(1.0F);
        assertTrue(o.yaw() >= 0 && o.yaw() < 360);
    }

    @Test
    @DisplayName("a drag turns by pixels, pauses the spin, and the spin resumes after the idle window")
    void drag() {
        OrbitState o = new OrbitState();
        o.beginDrag();
        o.drag(10);
        assertEquals(OrbitState.REST_YAW + 10 * OrbitState.DRAG_DEGREES_PER_PIXEL, o.yaw(), 1e-4);
        o.advance(1.0F);
        assertEquals(OrbitState.REST_YAW + 10 * OrbitState.DRAG_DEGREES_PER_PIXEL, o.yaw(), 1e-4);
        o.endDrag();
        o.advance(OrbitState.RESUME_AFTER_SECONDS / 2);
        assertEquals(OrbitState.REST_YAW + 10 * OrbitState.DRAG_DEGREES_PER_PIXEL, o.yaw(), 1e-4);
        o.advance(OrbitState.RESUME_AFTER_SECONDS);
        o.advance(1.0F);
        assertTrue(o.yaw() > OrbitState.REST_YAW + 10 * OrbitState.DRAG_DEGREES_PER_PIXEL);
    }

    @Test
    @DisplayName("reset returns to the resting angle")
    void reset() {
        OrbitState o = new OrbitState();
        o.advance(2.0F);
        o.reset();
        assertEquals(OrbitState.REST_YAW, o.yaw());
    }
}
