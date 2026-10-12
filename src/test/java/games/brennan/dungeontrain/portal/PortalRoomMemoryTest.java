package games.brennan.dungeontrain.portal;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of a dimensional carriage's memory that decide whether a remembered room is laid back —
 * a remembered room is what stops a re-stamp refilling what a player already took.
 */
final class PortalRoomMemoryTest {

    private static CompoundTag snapshot(int l, int h, int w) {
        CompoundTag snap = new CompoundTag();
        snap.putInt("l", l);
        snap.putInt("h", h);
        snap.putInt("w", w);
        return snap;
    }

    @Test
    @DisplayName("a remembered room fits the same room at the same box")
    void fitsSameRoomAndBox() {
        PortalRoomMemory.Entry entry = new PortalRoomMemory.Entry("hall", snapshot(9, 6, 7));
        assertTrue(PortalRoomMemory.fits(entry, "hall", new Vec3i(9, 6, 7)));
    }

    @Test
    @DisplayName("a renamed or resized room falls back to its template")
    void staleEntriesDoNotFit() {
        PortalRoomMemory.Entry entry = new PortalRoomMemory.Entry("hall", snapshot(9, 6, 7));
        assertFalse(PortalRoomMemory.fits(entry, "library", new Vec3i(9, 6, 7)));
        assertFalse(PortalRoomMemory.fits(entry, "hall", new Vec3i(10, 6, 7)));
        assertFalse(PortalRoomMemory.fits(null, "hall", new Vec3i(9, 6, 7)));
    }

    @Test
    @DisplayName("an entry survives a compressed write and read")
    void roundTripsThroughDisk(@TempDir Path dir) throws IOException {
        CompoundTag snap = snapshot(9, 6, 7);
        snap.putString("marker", "diamond-mined");
        Path file = dir.resolve("42.nbt");
        NbtIo.writeCompressed(PortalRoomMemory.encode(new PortalRoomMemory.Entry("hall", snap)), file);

        PortalRoomMemory.Entry back =
            PortalRoomMemory.decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        assertEquals("hall", back.roomName());
        assertEquals("diamond-mined", back.snapshot().getString("marker"));
        assertTrue(PortalRoomMemory.fits(back, "hall", new Vec3i(9, 6, 7)));
    }

    @Test
    @DisplayName("a tag that holds no entry decodes to nothing")
    void foreignTagDecodesToNull() {
        assertNull(PortalRoomMemory.decode(new CompoundTag()));
        assertNull(PortalRoomMemory.decode(null));
    }

    @Test
    @DisplayName("pruning forgets the oldest rooms first and keeps the cap")
    void prunesOldestFirst() {
        Map<Path, Long> writtenAt = new HashMap<>();
        writtenAt.put(Path.of("a.nbt"), 300L);
        writtenAt.put(Path.of("b.nbt"), 100L);
        writtenAt.put(Path.of("c.nbt"), 200L);
        writtenAt.put(Path.of("d.nbt"), 400L);

        assertEquals(List.of(Path.of("b.nbt"), Path.of("c.nbt")), PortalRoomMemory.toPrune(writtenAt, 2));
        assertEquals(List.of(), PortalRoomMemory.toPrune(writtenAt, 4));
    }
}
