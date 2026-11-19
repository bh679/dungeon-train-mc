package games.brennan.dungeontrain.portal;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue between a capture and the disk. The writer is held back with a latch, which is what a slow
 * disk looks like from here: everything queued has to stay readable, and the newest word has to win.
 */
final class PortalRoomMemoryWriterTest {

    private CountDownLatch disk;

    @BeforeEach
    void stallTheDisk() {
        disk = new CountDownLatch(1);
        CountDownLatch held = disk;
        PortalRoomMemoryWriter.runOnWriterForTests(() -> {
            try {
                held.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @AfterEach
    void releaseTheDisk() {
        disk.countDown();
        PortalRoomMemoryWriter.flush();
    }

    private static PortalRoomMemory.Entry entry(String room) {
        return new PortalRoomMemory.Entry(room, new CompoundTag());
    }

    private static String roomOnDisk(Path file) throws IOException {
        return PortalRoomMemory.decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())).roomName();
    }

    private void land() {
        disk.countDown();
        PortalRoomMemoryWriter.flush();
    }

    @Test
    @DisplayName("A queued write can be read back before it lands, and is gone from the queue after")
    void queuedWriteReadsBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("1.nbt");
        PortalRoomMemoryWriter.write(file, () -> entry("hall"), 10, "test");

        assertFalse(Files.exists(file));
        assertEquals("hall", PortalRoomMemoryWriter.pending(file).entry().roomName());

        land();
        assertNull(PortalRoomMemoryWriter.pending(file));
        assertEquals("hall", roomOnDisk(file));
    }

    @Test
    @DisplayName("Reading a queued write early does not build it a second time")
    void entryBuiltOnce(@TempDir Path dir) {
        Path file = dir.resolve("1.nbt");
        AtomicInteger builds = new AtomicInteger();
        PortalRoomMemoryWriter.write(file, () -> {
            builds.incrementAndGet();
            return entry("hall");
        }, 10, "test");

        assertNotNull(PortalRoomMemoryWriter.pending(file).entry());
        land();
        assertEquals(1, builds.get());
    }

    @Test
    @DisplayName("The newest write to a file wins, and the one it replaced is never built")
    void newestWriteWins(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("1.nbt");
        AtomicInteger staleBuilds = new AtomicInteger();
        PortalRoomMemoryWriter.write(file, () -> {
            staleBuilds.incrementAndGet();
            return entry("old");
        }, 10, "test");
        PortalRoomMemoryWriter.write(file, () -> entry("new"), 10, "test");

        assertEquals("new", PortalRoomMemoryWriter.pending(file).entry().roomName());
        land();
        assertEquals("new", roomOnDisk(file));
        assertEquals(0, staleBuilds.get());
    }

    @Test
    @DisplayName("A queued delete reads as gone at once and takes the file with it")
    void queuedDeleteReadsAsGone(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("1.nbt");
        NbtIo.writeCompressed(PortalRoomMemory.encode(entry("hall")), file);
        PortalRoomMemoryWriter.write(file, () -> entry("newer"), 10, "test");
        PortalRoomMemoryWriter.delete(file, "test");

        assertTrue(PortalRoomMemoryWriter.pending(file).isDelete());
        land();
        assertFalse(Files.exists(file));
        assertNull(PortalRoomMemoryWriter.pending(file));
    }

    @Test
    @DisplayName("A write trims the folder to the newest files and leaves the one just written")
    void writeTrimsFolder(@TempDir Path dir) throws IOException {
        for (int i = 0; i < 3; i++) {
            Path old = dir.resolve("old" + i + ".nbt");
            Files.write(old, new byte[1]);
            Files.setLastModifiedTime(old, FileTime.fromMillis(1000L + i));
        }
        Path file = dir.resolve("1.nbt");
        PortalRoomMemoryWriter.write(file, () -> entry("hall"), 2, "test");

        land();
        assertTrue(Files.exists(file));
        assertTrue(Files.exists(dir.resolve("old2.nbt")));
        assertFalse(Files.exists(dir.resolve("old0.nbt")));
        assertFalse(Files.exists(dir.resolve("old1.nbt")));
    }

    @Test
    @DisplayName("A build that throws clears the queue, and later saves still land")
    void failedBuildDoesNotStopTheWriter(@TempDir Path dir) throws IOException {
        Path bad = dir.resolve("bad.nbt");
        Path good = dir.resolve("good.nbt");
        PortalRoomMemoryWriter.write(bad, () -> {
            throw new IllegalStateException("boom");
        }, 10, "test");
        PortalRoomMemoryWriter.write(good, () -> entry("hall"), 10, "test");

        land();
        assertNull(PortalRoomMemoryWriter.pending(bad));
        assertFalse(Files.exists(bad));
        assertEquals("hall", roomOnDisk(good));
    }
}
