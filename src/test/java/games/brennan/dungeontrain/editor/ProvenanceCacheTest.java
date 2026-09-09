package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.RepoPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the memo that keeps the editor overlay's provenance lookups off the filesystem.
 *
 * <p>Pure-function tests against an injected clock — resolving a real path needs
 * {@code FMLPaths} and a Forge bootstrap, so the filesystem half is covered in-game. The
 * source-text guards at the bottom catch the two wirings that make the cache matter at all:
 * that {@code provenanceOf} actually reads through it, and that the reload barrier flushes it.</p>
 */
final class ProvenanceCacheTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void injectClock() {
        ProvenanceCache.setClockForTest(now::get);
    }

    @AfterEach
    void restoreClock() {
        ProvenanceCache.setClockForTest(null);
    }

    @Test
    @DisplayName("repeated lookups of one key call the loader once")
    void repeatedLookupsHitOnce() {
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 50; i++) {
            UserContentPaths.Provenance p = ProvenanceCache.get("templates", "cracked.nbt", key -> {
                loads.incrementAndGet();
                assertEquals("templates/cracked.nbt", key);
                return UserContentPaths.Provenance.USER;
            });
            assertEquals(UserContentPaths.Provenance.USER, p);
        }
        assertEquals(1, loads.get(), "the whole point: one stat run per template, not one per tick");
    }

    @Test
    @DisplayName("keys are independent")
    void keysAreIndependent() {
        UserContentPaths.Provenance a = ProvenanceCache.get("templates", "a.nbt", k -> UserContentPaths.Provenance.USER);
        UserContentPaths.Provenance b = ProvenanceCache.get("templates", "b.nbt", k -> UserContentPaths.Provenance.BUNDLED);
        UserContentPaths.Provenance c = ProvenanceCache.get("contents", "a.nbt", k -> UserContentPaths.Provenance.IMPORTED);
        assertEquals(UserContentPaths.Provenance.USER, a);
        assertEquals(UserContentPaths.Provenance.BUNDLED, b);
        assertEquals(UserContentPaths.Provenance.IMPORTED, c);
        assertEquals(3, ProvenanceCache.size());
    }

    @Test
    @DisplayName("invalidateAll drops entries and moves the generation")
    void invalidateAllReloads() {
        AtomicInteger loads = new AtomicInteger();
        ProvenanceCache.get("templates", "x.nbt", k -> { loads.incrementAndGet(); return UserContentPaths.Provenance.BUNDLED; });
        long before = ProvenanceCache.generation();

        ProvenanceCache.invalidateAll();

        assertNotEquals(before, ProvenanceCache.generation());
        assertEquals(0, ProvenanceCache.size());
        UserContentPaths.Provenance p = ProvenanceCache.get("templates", "x.nbt",
            k -> { loads.incrementAndGet(); return UserContentPaths.Provenance.USER; });
        assertEquals(UserContentPaths.Provenance.USER, p, "post-save answer wins over the stale one");
        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("entries expire after the TTL even with no explicit invalidation")
    void ttlIsTheSafetyNet() {
        AtomicInteger loads = new AtomicInteger();
        ProvenanceCache.get("templates", "y.nbt", k -> { loads.incrementAndGet(); return UserContentPaths.Provenance.BUNDLED; });
        long gen = ProvenanceCache.generation();

        now.addAndGet(ProvenanceCache.TTL_NANOS - 1);
        ProvenanceCache.get("templates", "y.nbt", k -> { loads.incrementAndGet(); return UserContentPaths.Provenance.BUNDLED; });
        assertEquals(1, loads.get(), "just inside the TTL is still a hit");
        assertEquals(gen, ProvenanceCache.generation());

        now.addAndGet(1);
        ProvenanceCache.get("templates", "y.nbt", k -> { loads.incrementAndGet(); return UserContentPaths.Provenance.USER; });
        assertEquals(2, loads.get(), "past the TTL the loader runs again");
        assertNotEquals(gen, ProvenanceCache.generation());
    }

    @Test
    @DisplayName("provenanceOf reads through the cache; the reload barrier flushes it")
    void wiringIsInPlace() throws IOException {
        Path base = RepoPaths.root().resolve("src/main/java/games/brennan/dungeontrain");
        String paths = Files.readString(base.resolve("editor/UserContentPaths.java"), StandardCharsets.UTF_8);
        String stores = Files.readString(base.resolve("template/TemplateStores.java"), StandardCharsets.UTF_8);

        assertTrue(paths.contains("ProvenanceCache.get("),
            "UserContentPaths.provenanceOf must resolve through ProvenanceCache — without it the "
                + "editor overlay stats every template file on every tick");
        assertTrue(stores.contains("ProvenanceCache.invalidateAll()"),
            "TemplateStores.reloadAll must flush ProvenanceCache — package switches, imports and "
                + "the Free Play suppression flip all funnel through it");
    }
}
