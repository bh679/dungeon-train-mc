package games.brennan.dungeontrain.client;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShotPathTest {

    @Test
    void samplesCoverTheWholeMoveEvenly() {
        List<Vector3d> s = ShotPath.samples(f -> new Vector3d(10 * f, 0, 0));
        assertEquals(ShotPath.SAMPLES, s.size());
        assertEquals(0, s.get(0).x, 1e-9);
        assertEquals(10, s.get(s.size() - 1).x, 1e-9);
        assertEquals(5, s.get(s.size() / 2).x, 1e-9);
    }
}
