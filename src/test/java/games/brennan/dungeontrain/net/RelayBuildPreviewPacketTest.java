package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format round trip for a preview answer: the three version arrays run in step, and a strip
 * captioning "from v1 · by Grace" off arrays that had drifted would name the wrong version.
 */
final class RelayBuildPreviewPacketTest {

    @Test
    @DisplayName("a versioned answer carries every version's seq, parent and author in step")
    void versionedRoundTrip() {
        RelayBuildPreviewPacket original = new RelayBuildPreviewPacket(4271, 9,
                new int[] {1, 4, 9}, new int[] {0, 1, 1}, new String[] {"Ada", "", "Grace"},
                true, false, new byte[] {1, 2, 3});
        RelayBuildPreviewPacket back = roundTrip(original);
        assertEquals(4271, back.relayId());
        assertEquals(9, back.seq());
        assertArrayEquals(new int[] {1, 4, 9}, back.seqs());
        assertArrayEquals(new int[] {0, 1, 1}, back.parentSeqs());
        assertArrayEquals(new String[] {"Ada", "", "Grace"}, back.authors());
        assertTrue(back.found());
        assertArrayEquals(new byte[] {1, 2, 3}, back.template());
    }

    @Test
    @DisplayName("a plain answer names no versions, and a miss says whether to ask again")
    void plainAndMissRoundTrip() {
        RelayBuildPreviewPacket plain = roundTrip(new RelayBuildPreviewPacket(7, true, false, new byte[] {9}));
        assertEquals(0, plain.seq());
        assertEquals(0, plain.seqs().length);
        assertEquals(0, plain.parentSeqs().length);
        assertEquals(0, plain.authors().length);
        RelayBuildPreviewPacket miss = roundTrip(RelayBuildPreviewPacket.none(7, true));
        assertFalse(miss.found());
        assertTrue(miss.retryable());
        assertEquals(0, miss.template().length);
    }

    private static RelayBuildPreviewPacket roundTrip(RelayBuildPreviewPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RelayBuildPreviewPacket.STREAM_CODEC.encode(buf, packet);
        return RelayBuildPreviewPacket.STREAM_CODEC.decode(buf);
    }
}
