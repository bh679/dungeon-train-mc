package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.LostCityTemplateLoads.ThreadKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LostCityTemplateLoadsTest {

    @Test
    @DisplayName("threads are classified by name: the pre-load, vanilla workers, Distant Horizons, the server")
    void kinds() {
        assertEquals(ThreadKind.PRELOAD, LostCityTemplateLoads.kindOf(LostCityTemplateLoads.PRELOAD_THREAD_NAME));
        assertEquals(ThreadKind.WORLDGEN, LostCityTemplateLoads.kindOf("Worker-Main-7"));
        assertEquals(ThreadKind.WORLDGEN, LostCityTemplateLoads.kindOf("worldgen"));   // the dev environment's task rename
        assertEquals(ThreadKind.DISTANT_HORIZONS, LostCityTemplateLoads.kindOf("DH-World Gen Thread[3]"));
        assertEquals(ThreadKind.SERVER, LostCityTemplateLoads.kindOf("Server thread"));
        assertEquals(ThreadKind.OTHER, LostCityTemplateLoads.kindOf("DH-LOD Builder Thread[0]"));
        assertEquals(ThreadKind.OTHER, LostCityTemplateLoads.kindOf("Test worker"));
        assertEquals(ThreadKind.OTHER, LostCityTemplateLoads.kindOf(null));
    }

    @Test
    @DisplayName("a snapshot's off-thread count leaves the pre-load thread out")
    void offThread() {
        long[] counts = new long[ThreadKind.values().length];
        long[] nanos = new long[ThreadKind.values().length];
        counts[ThreadKind.PRELOAD.ordinal()] = 42;
        counts[ThreadKind.WORLDGEN.ordinal()] = 3;
        counts[ThreadKind.DISTANT_HORIZONS.ordinal()] = 2;
        counts[ThreadKind.SERVER.ordinal()] = 1;
        nanos[ThreadKind.WORLDGEN.ordinal()] = 1_500_000_000L;
        LostCityTemplateLoads.Snapshot s = new LostCityTemplateLoads.Snapshot(counts, nanos);
        assertEquals(6, s.offThreadCount());
        assertEquals(1500, s.millis(ThreadKind.WORLDGEN));
        assertEquals(42, s.count(ThreadKind.PRELOAD));
    }
}
