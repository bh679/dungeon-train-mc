package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduling core of {@link EditorStampQueue}: budgeting, replacement, flushing and failure
 * isolation. The tick handler itself reads a {@link net.minecraft.server.level.ServerLevel} and is
 * verified in-game (Gate 2); everything here is the pure half, driven by a fake clock.
 */
final class EditorStampQueueTest {

    private final AtomicLong nanos = new AtomicLong();
    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void fakeClock() {
        EditorStampQueue.resetForTest();
        EditorStampQueue.setClock(nanos::get);
    }

    @AfterEach
    void realClock() {
        EditorStampQueue.resetForTest();
    }

    /** A job that records itself and advances the fake clock by {@code costNanos}. */
    private EditorStampQueue.Job job(String label, long costNanos) {
        return new EditorStampQueue.Job(label, () -> {
            ran.add(label);
            nanos.addAndGet(costNanos);
        });
    }

    @Test
    @DisplayName("a tick runs jobs until the budget is spent, then stops")
    void budgetStopsTheTick() {
        EditorStampQueue.start(List.of(job("a", 5), job("b", 5), job("c", 5), job("d", 5)), "test");
        int ran1 = EditorStampQueue.runSome(10);
        assertEquals(2, ran1);
        assertEquals(List.of("a", "b"), ran);
        assertTrue(EditorStampQueue.isBusy());
        assertEquals(2, EditorStampQueue.done());
        assertEquals(4, EditorStampQueue.total());
    }

    @Test
    @DisplayName("at least one job runs per tick even when a single job blows the budget")
    void alwaysMakesProgress() {
        EditorStampQueue.start(List.of(job("slow", 1_000), job("next", 1)), "test");
        assertEquals(1, EditorStampQueue.runSome(10));
        assertEquals(List.of("slow"), ran);
        assertEquals(1, EditorStampQueue.runSome(10));
        assertEquals(List.of("slow", "next"), ran);
        assertFalse(EditorStampQueue.isBusy());
    }

    @Test
    @DisplayName("start replaces an in-flight queue — the old jobs never run")
    void startReplaces() {
        EditorStampQueue.start(List.of(job("old1", 1), job("old2", 1)), "first");
        EditorStampQueue.runSome(0);
        EditorStampQueue.start(List.of(job("new1", 1)), "second");
        EditorStampQueue.flush();
        assertEquals(List.of("old1", "new1"), ran);
        assertEquals(1, EditorStampQueue.total());
    }

    @Test
    @DisplayName("cancel drops everything queued")
    void cancelDrops() {
        EditorStampQueue.start(List.of(job("a", 1), job("b", 1)), "test");
        EditorStampQueue.cancel();
        assertFalse(EditorStampQueue.isBusy());
        assertEquals(0, EditorStampQueue.runSome(Long.MAX_VALUE));
        assertTrue(ran.isEmpty());
    }

    @Test
    @DisplayName("flush runs the rest now, and is a no-op from inside a job")
    void flushRunsRestAndIsReentrantSafe() {
        EditorStampQueue.start(List.of(
            job("a", 1),
            new EditorStampQueue.Job("b-flushes", () -> {
                ran.add("b");
                EditorStampQueue.flush();   // a whole-kind restamp inside a job: must not recurse
                ran.add("b-after");
            }),
            job("c", 1)), "test");
        EditorStampQueue.runSome(0);   // just "a"
        EditorStampQueue.flush();
        assertEquals(List.of("a", "b", "b-after", "c"), ran);
        assertFalse(EditorStampQueue.isBusy());
    }

    @Test
    @DisplayName("a failing job is skipped and the rest still run")
    void failureIsIsolated() {
        EditorStampQueue.start(List.of(
            job("a", 1),
            new EditorStampQueue.Job("boom", () -> { throw new IllegalStateException("bad template"); }),
            job("c", 1)), "test");
        EditorStampQueue.flush();
        assertEquals(List.of("a", "c"), ran);
        assertEquals(3, EditorStampQueue.done());
        assertFalse(EditorStampQueue.isBusy());
    }

    @Test
    @DisplayName("an empty queue is not busy and ticks are free")
    void emptyQueue() {
        EditorStampQueue.start(List.of(), "nothing");
        assertFalse(EditorStampQueue.isBusy());
        assertEquals(0, EditorStampQueue.runSome(10));
    }
}
