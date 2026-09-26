package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

final class PerThreadArraysTest {

    @Test
    @DisplayName("a thread gets one stable copy per shared array, never the shared array itself")
    void copyIsStablePerThread() {
        String[] shared = {"a", "b", "c"};
        String[] mine = PerThreadArrays.of(shared);
        assertNotSame(shared, mine);
        assertArrayEquals(shared, mine);
        assertSame(mine, PerThreadArrays.of(shared), "the same thread must see the same copy on every read");
    }

    @Test
    @DisplayName("reordering one thread's copy leaves the shared array and other threads' copies alone")
    void threadsDoNotShareCopies() throws InterruptedException {
        String[] shared = {"a", "b", "c"};
        String[] mine = PerThreadArrays.of(shared);
        mine[0] = "c";
        mine[2] = "a";

        AtomicReference<String[]> theirs = new AtomicReference<>();
        Thread other = new Thread(() -> theirs.set(PerThreadArrays.of(shared)));
        other.start();
        other.join();

        assertArrayEquals(new String[]{"a", "b", "c"}, shared);
        assertArrayEquals(new String[]{"a", "b", "c"}, theirs.get());
        assertNotSame(mine, theirs.get());
    }
}
