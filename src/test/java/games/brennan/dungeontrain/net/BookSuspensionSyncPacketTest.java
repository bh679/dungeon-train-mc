package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire round-trip for the book-upload pause sync, and the clamps that keep a garbled or hostile
 * value from turning into a permanently dead Sign button.
 */
class BookSuspensionSyncPacketTest {

    private static BookSuspensionSyncPacket roundTrip(BookSuspensionSyncPacket in) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        BookSuspensionSyncPacket.STREAM_CODEC.encode(buf, in);
        return BookSuspensionSyncPacket.STREAM_CODEC.decode(buf);
    }

    @Test
    void anOpenWindowSurvivesTheWire() {
        BookSuspensionSyncPacket out = roundTrip(BookSuspensionSyncPacket.of(120L, 3));
        assertEquals(120L, out.remainingSec());
        assertEquals(3, out.strikes());
    }

    @Test
    void theClearedPacketIsTheLiftSignal() {
        BookSuspensionSyncPacket out = roundTrip(BookSuspensionSyncPacket.cleared());
        assertEquals(0L, out.remainingSec());
        assertEquals(0, out.strikes());
    }

    @Test
    void negativeAndAbsurdValuesAreClamped() {
        assertEquals(0L, BookSuspensionSyncPacket.of(-30L, -2).remainingSec());
        assertEquals(0, BookSuspensionSyncPacket.of(-30L, -2).strikes());
        long huge = BookSuspensionSyncPacket.of(Long.MAX_VALUE, 1).remainingSec();
        assertTrue(huge <= 86_400L, "a day is already far past the relay's 1h ceiling");
        assertEquals(huge, roundTrip(BookSuspensionSyncPacket.of(Long.MAX_VALUE, 1)).remainingSec());
    }
}
