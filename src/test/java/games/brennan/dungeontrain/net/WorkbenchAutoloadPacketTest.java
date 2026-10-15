package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wire round trip for the Workbench tab's Autoload, and its cap. */
final class WorkbenchAutoloadPacketTest {

    @Test
    @DisplayName("every item and the pool flag survive the wire, in order")
    void roundTrip() {
        WorkbenchAutoloadPacket original = new WorkbenchAutoloadPacket(List.of(
            new WorkbenchAutoloadPacket.Item(4271, "0000-1111", "Alex"),
            new WorkbenchAutoloadPacket.Item(88, "", ""),
            new WorkbenchAutoloadPacket.Item(5, "2222-3333", "Sam")), true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        WorkbenchAutoloadPacket.STREAM_CODEC.encode(buf, original);
        assertEquals(original, WorkbenchAutoloadPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("more than the cap is cut to the cap, keeping the first ones")
    void capped() {
        List<WorkbenchAutoloadPacket.Item> many = new ArrayList<>();
        for (int i = 0; i < WorkbenchAutoloadPacket.MAX_ITEMS + 10; i++) {
            many.add(new WorkbenchAutoloadPacket.Item(i, "", ""));
        }
        WorkbenchAutoloadPacket packet = new WorkbenchAutoloadPacket(many, false);
        assertEquals(WorkbenchAutoloadPacket.MAX_ITEMS, packet.items().size());
        assertEquals(0, packet.items().get(0).relayId());
        assertEquals(WorkbenchAutoloadPacket.MAX_ITEMS - 1, packet.items().get(WorkbenchAutoloadPacket.MAX_ITEMS - 1).relayId());
    }

    @Test
    @DisplayName("nulls normalise to blanks and an empty list")
    void nulls() {
        WorkbenchAutoloadPacket packet = new WorkbenchAutoloadPacket(null, false);
        assertEquals(List.of(), packet.items());
        WorkbenchAutoloadPacket.Item item = new WorkbenchAutoloadPacket.Item(1, null, null);
        assertEquals("", item.ownerUuid());
        assertEquals("", item.ownerName());
    }
}
