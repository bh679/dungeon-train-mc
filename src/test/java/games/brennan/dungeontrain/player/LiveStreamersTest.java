package games.brennan.dungeontrain.player;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two players on one server may both be live (Two Streams); the registry keeps them in claim order. */
final class LiveStreamersTest {

    /** The registry keys by server; a null key is a server of its own to a WeakHashMap, which is all the test needs. */
    private static final MinecraftServer SERVER = null;

    @Test
    @DisplayName("two streamers coexist; the first to claim is the one `any` names; clearing one keeps the other")
    void twoStreamersOnOneServer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try {
            assertNull(LiveStreamers.any(SERVER));
            assertTrue(LiveStreamers.add(SERVER, a));
            assertTrue(LiveStreamers.add(SERVER, b));
            assertFalse(LiveStreamers.add(SERVER, b), "already a streamer");
            assertTrue(LiveStreamers.isStreamer(SERVER, a));
            assertTrue(LiveStreamers.isStreamer(SERVER, b));
            assertFalse(LiveStreamers.isStreamer(SERVER, UUID.randomUUID()));
            assertFalse(LiveStreamers.isStreamer(SERVER, null));
            assertEquals(a, LiveStreamers.any(SERVER));
            assertEquals(Set.of(a, b), LiveStreamers.all(SERVER));
            assertEquals(Set.of(b), LiveStreamers.othersThan(SERVER, a));
            assertEquals(Set.of(a), LiveStreamers.othersThan(SERVER, b));
            assertTrue(LiveStreamers.clearIf(SERVER, a));
            assertFalse(LiveStreamers.clearIf(SERVER, a), "already gone");
            assertTrue(LiveStreamers.isStreamer(SERVER, b), "the other stream carries on");
            assertEquals(b, LiveStreamers.any(SERVER));
        } finally {
            LiveStreamers.clearIf(SERVER, a);
            LiveStreamers.clearIf(SERVER, b);
        }
        assertNull(LiveStreamers.any(SERVER));
        assertEquals(Set.of(), LiveStreamers.all(SERVER));
    }

    @Test
    @DisplayName("streamers keep their names, so the relay's cut-off name can be matched; clearing forgets it")
    void namesTravelWithStreamers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try {
            LiveStreamers.add(SERVER, a, "Alice");
            LiveStreamers.add(SERVER, b, "Bob");
            assertEquals(java.util.Map.of(b, "Bob"), LiveStreamers.othersWithNames(SERVER, a));
            assertEquals(java.util.Map.of(a, "Alice"), LiveStreamers.othersWithNames(SERVER, b));
            LiveStreamers.clearIf(SERVER, b);
            assertEquals(java.util.Map.of(), LiveStreamers.othersWithNames(SERVER, a));
        } finally {
            LiveStreamers.clearIf(SERVER, a);
            LiveStreamers.clearIf(SERVER, b);
        }
    }

    @Test
    @DisplayName("both started = the second-earliest start; 0 with fewer than two")
    void secondEarliestStart() {
        assertEquals(0L, LiveStreamers.secondEarliest(java.util.List.of()));
        assertEquals(0L, LiveStreamers.secondEarliest(java.util.List.of(5L)));
        assertEquals(9L, LiveStreamers.secondEarliest(java.util.List.of(9L, 3L)));
        assertEquals(7L, LiveStreamers.secondEarliest(java.util.List.of(12L, 3L, 7L)));
    }
}
