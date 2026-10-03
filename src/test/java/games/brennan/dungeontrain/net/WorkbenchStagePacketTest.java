package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wire round trip for the "To Workbench" request. */
final class WorkbenchStagePacketTest {

    @Test
    @DisplayName("relay id, owner, name and pool survive the wire")
    void roundTrip() {
        WorkbenchStagePacket original = new WorkbenchStagePacket(4271, "0000-1111-2222", "Alex", true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        WorkbenchStagePacket.STREAM_CODEC.encode(buf, original);
        assertEquals(original, WorkbenchStagePacket.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("nulls normalise to blanks so the codec never sees one")
    void nullsAreBlank() {
        WorkbenchStagePacket packet = new WorkbenchStagePacket(1, null, null, false);
        assertEquals("", packet.ownerUuid());
        assertEquals("", packet.ownerName());
    }
}
