package games.brennan.dungeontrain.worldgen;

import org.betterx.bclib.sdf.SDF;
import org.betterx.bclib.sdf.operator.SDFDisplacement;
import org.betterx.bclib.sdf.operator.SDFFlatWave;
import org.betterx.bclib.sdf.operator.SDFTranslate;
import org.betterx.bclib.sdf.primitive.SDFSphere;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

final class PerThreadSdfTest {

    private static Object field(Object owner, Class<?> declaring, String name) throws ReflectiveOperationException {
        Field field = declaring.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test
    @DisplayName("a copy keeps the graph's wiring: two statics into one graph still share their node")
    void copyKeepsWiring() throws ReflectiveOperationException {
        SDFSphere sphere = new SDFSphere().setRadius(3.0F);
        SDF graph = new SDFTranslate().setTranslate(0, 2, 0).setSource(sphere);

        var copies = new IdentityHashMap<Object, Object>();
        SDFSphere sphereCopy = PerThreadSdf.copyOf(sphere, copies);
        SDF graphCopy = PerThreadSdf.copyOf(graph, copies);

        assertNotSame(sphere, sphereCopy);
        assertNotSame(graph, graphCopy);
        assertSame(sphereCopy, field(graphCopy, graphCopy.getClass().getSuperclass(), "source"),
            "the copied graph must point at the copied sphere, whichever was copied first");
        assertEquals(graph.getDistance(1, 0, 0), graphCopy.getDistance(1, 0, 0));
    }

    @Test
    @DisplayName("re-configuring a copy leaves the shared graph alone")
    void copyIsIndependent() {
        SDFSphere sphere = new SDFSphere().setRadius(3.0F);
        SDFSphere copy = PerThreadSdf.copyOf(sphere, new IdentityHashMap<>());
        float before = sphere.getDistance(0, 0, 0);
        copy.setRadius(9.0F);
        assertEquals(before, sphere.getDistance(0, 0, 0));
        assertEquals(before - 6.0F, copy.getDistance(0, 0, 0), 1e-6F);
    }

    @Test
    @DisplayName("a node's scratch vector is copied, not shared")
    void scratchVectorIsCopied() throws ReflectiveOperationException {
        SDFDisplacement displacement = new SDFDisplacement();
        SDFDisplacement copy = PerThreadSdf.copyOf(displacement, new IdentityHashMap<>());
        assertNotSame(field(displacement, SDFDisplacement.class, "pos"), field(copy, SDFDisplacement.class, "pos"));
    }

    @Test
    @DisplayName("a node holding one of BCLib's own lambdas copies, sharing the lambda")
    void nodeWithLibraryLambdaCopies() {
        SDF wave = new SDFFlatWave().setRaysCount(12).setIntensity(1.3F).setSource(new SDFSphere().setRadius(3.0F));
        SDF copy = PerThreadSdf.copyOf(wave, new IdentityHashMap<>());
        assertNotSame(wave, copy);
        assertEquals(wave.getDistance(1, 0, 2), copy.getDistance(1, 0, 2));
    }

    @Test
    @DisplayName("each thread gets its own stable copy")
    void copyIsPerThread() throws InterruptedException {
        SDFSphere shared = new SDFSphere().setRadius(3.0F);
        SDFSphere mine = PerThreadSdf.of(shared);
        assertNotSame(shared, mine);
        assertSame(mine, PerThreadSdf.of(shared), "the same thread must see the same copy on every read");

        AtomicReference<SDFSphere> theirs = new AtomicReference<>();
        Thread other = new Thread(() -> theirs.set(PerThreadSdf.of(shared)));
        other.start();
        other.join();
        assertNotSame(mine, theirs.get());
        assertNotSame(shared, theirs.get());
    }
}
