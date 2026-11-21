package games.brennan.dungeontrain.client;

import io.github.mortuusars.exposure.world.photograph.PhotographType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Client setup can run twice in one JVM (resource-pack rollback); the styles must register once. */
class WornPhotographStylesTest {

    @Test
    void secondSetupRegistersNothing() {
        AtomicBoolean done = new AtomicBoolean();
        List<PhotographType> seen = new ArrayList<>();

        assertTrue(WornPhotographStyles.registerOnce(done, seen::add));
        assertFalse(WornPhotographStyles.registerOnce(done, seen::add));

        assertEquals(WornPhotographStyles.types(), seen);
    }

    @Test
    void typesAreDistinctAndCoverEveryWear() {
        List<PhotographType> types = WornPhotographStyles.types();
        assertEquals(6, types.size());
        assertEquals(types.size(), new HashSet<>(types).size());
        assertEquals("worn_1", types.get(0).id().getPath());
    }
}
