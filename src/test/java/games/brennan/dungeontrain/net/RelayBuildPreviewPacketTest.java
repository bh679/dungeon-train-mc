package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.builder.relay.BuilderRelayPreview;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-format round trips for a relay build's preview answer, oversized included. */
final class RelayBuildPreviewPacketTest {

    private static RelayBuildPreviewPacket roundTrip(RelayBuildPreviewPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RelayBuildPreviewPacket.STREAM_CODEC.encode(buf, packet);
        RelayBuildPreviewPacket decoded = RelayBuildPreviewPacket.STREAM_CODEC.decode(buf);
        assertFalse(buf.isReadable());
        return decoded;
    }

    @Test
    @DisplayName("an oversized answer carries the build's block count and box, and no picture")
    void oversized() {
        BuilderRelayPreview.Oversize size = new BuilderRelayPreview.Oversize(63_669, 64, 64, 64);
        RelayBuildPreviewPacket decoded = roundTrip(RelayBuildPreviewPacket.oversized(43234, 0, new int[0], size));
        assertEquals(43234, decoded.relayId());
        assertFalse(decoded.found());
        assertFalse(decoded.retryable(), "a build too big to preview stays too big");
        assertEquals(size, BuilderRelayPreview.Oversize.fromArray(decoded.oversize()));
    }

    @Test
    @DisplayName("a picture and a plain miss carry no oversize")
    void pictureAndMiss() {
        byte[] bytes = {1, 2, 3};
        RelayBuildPreviewPacket picture = roundTrip(new RelayBuildPreviewPacket(7, true, false, bytes));
        assertTrue(picture.found());
        assertArrayEquals(bytes, picture.template());
        assertNull(BuilderRelayPreview.Oversize.fromArray(picture.oversize()));
        RelayBuildPreviewPacket miss = roundTrip(RelayBuildPreviewPacket.none(7, true));
        assertTrue(miss.retryable());
        assertNull(BuilderRelayPreview.Oversize.fromArray(miss.oversize()));
    }

    @Test
    @DisplayName("a version answer keeps its seq and index alongside the oversize")
    void versionedOversized() {
        BuilderRelayPreview.Oversize size = new BuilderRelayPreview.Oversize(20_000, 32, 24, 32);
        RelayBuildPreviewPacket decoded = roundTrip(RelayBuildPreviewPacket.oversized(9, 5, new int[]{1, 3, 5}, size));
        assertEquals(5, decoded.seq());
        assertArrayEquals(new int[]{1, 3, 5}, decoded.seqs());
        assertEquals(size, BuilderRelayPreview.Oversize.fromArray(decoded.oversize()));
    }

    @Test
    @DisplayName("a lighter picture of a big build still carries its oversize, so Load can ask first")
    void pictureWithOversize() {
        byte[] bytes = {4, 5, 6};
        BuilderRelayPreview.Oversize size = new BuilderRelayPreview.Oversize(63_669, 64, 64, 64);
        RelayBuildPreviewPacket decoded = roundTrip(RelayBuildPreviewPacket.of(43234, 0, new int[0],
            new BuilderRelayPreview.Attempt(bytes, false, size)));
        assertTrue(decoded.found());
        assertArrayEquals(bytes, decoded.template());
        assertEquals(size, BuilderRelayPreview.Oversize.fromArray(decoded.oversize()));
    }
}
