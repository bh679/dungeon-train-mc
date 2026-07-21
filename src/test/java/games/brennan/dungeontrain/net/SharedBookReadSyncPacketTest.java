package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-format round-trip for {@link SharedBookReadSyncPacket}, the client→server sync of a player's global
 * community-book read set (var-int count + var-int pool ids). Pins the codec so a book the client says it
 * read arrives intact — the mirror it feeds is the loot selector's unread-first fallback.
 */
final class SharedBookReadSyncPacketTest {

    @Test
    @DisplayName("round-trip preserves an empty read set (login before anything is read)")
    void roundTrip_empty() {
        SharedBookReadSyncPacket decoded = roundTrip(new SharedBookReadSyncPacket(List.of()));
        assertEquals(List.of(), decoded.ids());
    }

    @Test
    @DisplayName("round-trip preserves a single id (the per-read top-up)")
    void roundTrip_single() {
        SharedBookReadSyncPacket decoded = roundTrip(new SharedBookReadSyncPacket(List.of(42)));
        assertEquals(List.of(42), decoded.ids());
    }

    @Test
    @DisplayName("round-trip preserves a multi-id set in order (the login full-set)")
    void roundTrip_many() {
        List<Integer> ids = List.of(1, 7, 42, 1000, 65535);
        SharedBookReadSyncPacket decoded = roundTrip(new SharedBookReadSyncPacket(ids));
        assertEquals(ids, decoded.ids());
    }

    private static SharedBookReadSyncPacket roundTrip(SharedBookReadSyncPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SharedBookReadSyncPacket.STREAM_CODEC.encode(buf, original);
        return SharedBookReadSyncPacket.STREAM_CODEC.decode(buf);
    }
}
