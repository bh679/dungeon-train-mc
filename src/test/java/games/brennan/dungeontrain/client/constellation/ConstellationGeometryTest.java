package games.brennan.dungeontrain.client.constellation;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConstellationGeometryTest {

    @Test
    void centreIsFortyFiveDegreesUpToTheSouth() {
        Vector3f c = ConstellationGeometry.point(0f, 0f);
        assertEquals(0f, c.x, 1e-4f);
        assertEquals(c.y, c.z, 1e-3f); // 45 degrees: as high as it is far
        assertEquals(ConstellationGeometry.DISTANCE, c.length(), 1e-3f);
    }

    @Test
    void lookingAtThePointHitsIt() {
        Vector3f target = ConstellationGeometry.point(0.4f, -0.3f).normalize();
        float[] hit = ConstellationGeometry.hit(target);
        assertNotNull(hit);
        assertEquals(0.4f, hit[0], 1e-4f);
        assertEquals(-0.3f, hit[1], 1e-4f);
    }

    @Test
    void lookingAwayMisses() {
        assertNull(ConstellationGeometry.hit(new Vector3f(0f, -0.7071f, -0.7071f)));
    }

    @Test
    void rightIsWestWhenFacingSouth() {
        // facing +Z, east (+X) is to the left: the disc's right points -X
        assertEquals(-1f, ConstellationGeometry.RIGHT.x);
        assertEquals(0f, ConstellationGeometry.UP.dot(ConstellationGeometry.FORWARD), 1e-5f);
    }
}
