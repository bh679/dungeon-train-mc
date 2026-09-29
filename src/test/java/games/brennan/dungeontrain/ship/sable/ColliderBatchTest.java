package games.brennan.dungeontrain.ship.sable;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bookkeeping coverage for {@link ColliderBatch} — the scope, its dedupe and counts, and the
 * rebuild-on-exit contract — with the rebuild replaced by a recording callback, since the real one
 * needs a live Sable physics system. {@link SectionPos} is plain coordinate math (no registry
 * bootstrap), the same pattern as {@code PhysicsSubstepTunerTest}.
 */
final class ColliderBatchTest {

    private final List<ColliderBatch.Batch> rebuilt = new ArrayList<>();

    @BeforeEach
    void enable() {
        ColliderBatch.ENABLED = true;
        rebuilt.clear();
    }

    @AfterEach
    void restore() {
        ColliderBatch.ENABLED = true;
        PhysicsStepTimer.drain();
    }

    private static void record(int sx, int sy, int sz) {
        ColliderBatch.record(null, SectionPos.of(sx, sy, sz), null);
    }

    @Test
    @DisplayName("inactive outside a scope, active inside, rebuild runs once on exit")
    void scopeLifecycle() {
        assertFalse(ColliderBatch.isActive());
        String out = ColliderBatch.call(() -> {
            assertTrue(ColliderBatch.isActive());
            record(0, 4, 0);
            return "done";
        }, rebuilt::add);
        assertEquals("done", out);
        assertFalse(ColliderBatch.isActive());
        assertEquals(1, rebuilt.size());
        assertEquals(1, rebuilt.get(0).sections.size());
        assertEquals(1, rebuilt.get(0).deferred);
    }

    @Test
    @DisplayName("repeated blocks in one section dedupe to one section but count every deferral")
    void dedupesSections() {
        ColliderBatch.call(() -> {
            record(1, 4, 1);
            record(1, 4, 1);
            record(2, 4, 1);
            return null;
        }, rebuilt::add);
        ColliderBatch.Batch batch = rebuilt.get(0);
        assertEquals(2, batch.sections.size());
        assertEquals(3, batch.deferred);
        assertTrue(batch.sections.containsKey(SectionPos.of(1, 4, 1).asLong()));
        assertTrue(batch.sections.containsKey(SectionPos.of(2, 4, 1).asLong()));
    }

    @Test
    @DisplayName("a nested scope folds into the outermost one: a single rebuild with everything")
    void nestedScopesRebuildOnce() {
        ColliderBatch.call(() -> {
            record(0, 0, 0);
            ColliderBatch.call(() -> {
                assertTrue(ColliderBatch.isActive());
                record(0, 1, 0);
                return null;
            }, rebuilt::add);
            assertTrue(ColliderBatch.isActive(), "inner exit must not close the outer scope");
            assertEquals(0, rebuilt.size(), "inner exit must not rebuild");
            return null;
        }, rebuilt::add);
        assertEquals(1, rebuilt.size());
        assertEquals(2, rebuilt.get(0).sections.size());
    }

    @Test
    @DisplayName("a body that throws still rebuilds and closes the scope")
    void rebuildsInFinally() {
        assertThrows(IllegalStateException.class, () -> ColliderBatch.call(() -> {
            record(3, 3, 3);
            throw new IllegalStateException("stamp failed");
        }, rebuilt::add));
        assertFalse(ColliderBatch.isActive());
        assertFalse(ColliderBatch.scopeOpen());
        assertEquals(1, rebuilt.size());
        assertEquals(1, rebuilt.get(0).sections.size());
    }

    @Test
    @DisplayName("a rebuild that throws is contained and leaves no scope open")
    void rebuildFailureIsContained() {
        ColliderBatch.call(() -> { record(0, 0, 0); return null; }, b -> { throw new RuntimeException("native"); });
        assertFalse(ColliderBatch.scopeOpen());
        assertFalse(ColliderBatch.isActive());
    }

    @Test
    @DisplayName("ENABLED=false: not active, nothing recorded, but the scope still opens and closes")
    void disabledIsInert() {
        ColliderBatch.ENABLED = false;
        ColliderBatch.call(() -> {
            assertFalse(ColliderBatch.isActive());
            assertTrue(ColliderBatch.scopeOpen());
            return null;
        }, rebuilt::add);
        assertEquals(1, rebuilt.size());
        assertEquals(0, rebuilt.get(0).sections.size());
        assertEquals(0, rebuilt.get(0).deferred);
    }

    @Test
    @DisplayName("switching off mid-scope resumes the per-block path at once")
    void disableMidScope() {
        ColliderBatch.call(() -> {
            assertTrue(ColliderBatch.isActive());
            ColliderBatch.ENABLED = false;
            assertFalse(ColliderBatch.isActive());
            return null;
        }, rebuilt::add);
        assertFalse(ColliderBatch.scopeOpen());
    }

    @Test
    @DisplayName("records outside any scope are ignored")
    void recordOutsideScopeIgnored() {
        record(9, 9, 9);
        ColliderBatch.call(() -> null, rebuilt::add);
        assertEquals(0, rebuilt.get(0).sections.size());
    }

    @Test
    @DisplayName("the [mspt] counters land in PhysicsStepTimer.drain()")
    void countersDrain() {
        PhysicsStepTimer.drain();
        PhysicsStepTimer.countColliderRebuild();
        PhysicsStepTimer.countColliderRebuild();
        PhysicsStepTimer.addBatchedBlockChanges(1500);
        PhysicsStepTimer.addBatchedBlockChanges(0);
        PhysicsStepTimer.countBlockChange();
        PhysicsStepTimer.Window w = PhysicsStepTimer.drain();
        assertEquals(2, w.colliderRebuilds());
        assertEquals(1500, w.batchedBlockChanges());
        assertEquals(1, w.blockChanges());
        PhysicsStepTimer.Window empty = PhysicsStepTimer.drain();
        assertEquals(0, empty.colliderRebuilds());
        assertEquals(0, empty.batchedBlockChanges());
    }
}
