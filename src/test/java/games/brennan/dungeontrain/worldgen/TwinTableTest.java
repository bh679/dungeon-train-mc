package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TwinTable} readers never lock, so what they can observe while a writer publishes or clears is
 * the whole contract: only finished maps, a twin never without its spawns while the table only grows,
 * and — racing a {@code clear()} — {@code null}, which callers read as "let the live biome answer".
 */
final class TwinTableTest {

    private static final int BATCH = 64;
    private static final int READERS = 4;
    private static final int GROW_ROUNDS = 300;
    private static final int CLEAR_ROUNDS = 20_000;

    /** A batch of identity keys with the twin / spawns each must map to. */
    private record Batch(Object[] keys, Map<Object, String> twins, Map<Object, String> spawns) {
        static Batch of(String name) {
            Object[] keys = new Object[BATCH];
            Map<Object, String> twins = new IdentityHashMap<>();
            Map<Object, String> spawns = new IdentityHashMap<>();
            for (int i = 0; i < BATCH; i++) {
                keys[i] = new Object();
                twins.put(keys[i], name + "-twin-" + i);
                spawns.put(keys[i], name + "-spawns-" + i);
            }
            return new Batch(keys, twins, spawns);
        }
    }

    @Test
    @DisplayName("publish adds to the table; clear empties it")
    void publishAndClear() {
        TwinTable<Object, String, String> table = new TwinTable<>();
        Batch server = Batch.of("server");
        Batch client = Batch.of("client");
        assertNull(table.twin(server.keys[0]));

        table.publish(server.twins, server.spawns);
        table.publish(client.twins, client.spawns);
        assertEquals(2 * BATCH, table.size());
        for (Batch b : List.of(server, client)) {
            for (Object k : b.keys) {
                assertSame(b.twins.get(k), table.twin(k));
                assertSame(b.spawns.get(k), table.spawns(k));
            }
        }

        table.clear();
        assertEquals(0, table.size());
        assertNull(table.twin(server.keys[0]));
        assertNull(table.spawns(server.keys[0]));
    }

    @Test
    @DisplayName("the table keeps its own copy: changing the published maps afterwards changes nothing")
    void publishCopies() {
        TwinTable<Object, String, String> table = new TwinTable<>();
        Batch b = Batch.of("server");
        table.publish(b.twins, b.spawns);
        Object late = new Object();
        b.twins.put(late, "late");
        b.twins.remove(b.keys[0]);
        b.spawns.clear();
        assertNull(table.twin(late));
        assertNotNull(table.twin(b.keys[0]));
        assertNotNull(table.spawns(b.keys[0]));
        assertEquals(BATCH, table.size());
    }

    @Test
    @DisplayName("a reader that took a twin just before clear() gets null spawns")
    void readerRacingClearGetsNull() {
        TwinTable<Object, String, String> table = new TwinTable<>();
        Batch b = Batch.of("server");
        table.publish(b.twins, b.spawns);
        String twin = table.twin(b.keys[0]);   // spawnsFor's first read …
        assertNotNull(twin);
        table.clear();                          // … clear lands between the two …
        assertNull(table.spawns(b.keys[0]));    // … and the second read says "use the live spawns"
    }

    @Test
    @DisplayName("while the table only grows, readers see each batch whole and every twin with its spawns")
    void concurrentPublishIsAtomic() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(READERS);
        try {
            for (int round = 0; round < GROW_ROUNDS; round++) {
                TwinTable<Object, String, String> table = new TwinTable<>();
                Batch server = Batch.of("server");
                Batch client = Batch.of("client");
                AtomicBoolean done = new AtomicBoolean();
                List<Future<?>> readers = new ArrayList<>();
                for (int r = 0; r < READERS; r++) {
                    readers.add(pool.submit(() -> {
                        boolean last;
                        do {
                            last = done.get();
                            for (Batch b : List.of(server, client)) {
                                boolean seen = false;
                                for (Object k : b.keys) {
                                    String twin = table.twin(k);
                                    if (twin == null) {
                                        // nothing is ever removed here, so a batch seen once is there in full
                                        assertFalse(seen, "batch seen in part");
                                        continue;
                                    }
                                    seen = true;
                                    assertSame(b.twins.get(k), twin);
                                    assertSame(b.spawns.get(k), table.spawns(k), "twin without its spawns");
                                }
                            }
                        } while (!last);
                    }));
                }
                table.publish(server.twins, server.spawns);
                table.publish(client.twins, client.spawns);
                done.set(true);
                for (Future<?> f : readers) f.get();
                assertEquals(2 * BATCH, table.size());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("with clear() in the mix, readers only ever get the right value or null")
    void concurrentPublishAndClearNeverTear() throws Exception {
        TwinTable<Object, String, String> table = new TwinTable<>();
        Batch server = Batch.of("server");
        Batch client = Batch.of("client");
        AtomicBoolean done = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(READERS);
        try {
            List<Future<Long>> readers = new ArrayList<>();
            for (int r = 0; r < READERS; r++) {
                readers.add(pool.submit(() -> {
                    long hits = 0;
                    boolean last;
                    do {
                        last = done.get();
                        for (Batch b : List.of(server, client)) {
                            for (Object k : b.keys) {
                                String twin = table.twin(k);
                                String spawns = table.spawns(k);
                                if (twin != null) {
                                    assertSame(b.twins.get(k), twin);
                                    hits++;
                                }
                                if (spawns != null) assertSame(b.spawns.get(k), spawns);
                            }
                        }
                    } while (!last);
                    return hits;
                }));
            }
            for (int round = 0; round < CLEAR_ROUNDS; round++) {
                table.publish(server.twins, server.spawns);
                table.publish(client.twins, client.spawns);
                table.clear();
            }
            table.publish(server.twins, server.spawns);
            done.set(true);
            long hits = 0;
            for (Future<Long> f : readers) hits += f.get();
            assertTrue(hits > 0, "readers never overlapped a published table");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(BATCH, table.size());
    }
}
