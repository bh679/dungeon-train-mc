package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format round-trip for {@link TrainDebugCarriagePacket} — the carriage internals the F3+4
 * panel shows, sent only to players allowed to see them.
 *
 * <p>Three string fields in a row is exactly the shape where a writer/reader mismatch silently
 * transposes values rather than failing, so the load-bearing test is the one giving all three
 * distinct values and checking each lands in its own field.</p>
 */
final class TrainDebugCarriagePacketTest {

    @Test
    @DisplayName("round-trip keeps variant, contents and sub-variant in their own fields")
    void roundTrip_distinctIds_doNotTranspose() {
        TrainDebugCarriagePacket original =
            new TrainDebugCarriagePacket(true, 12, "cargo", "container_wooden", "cagedzombie");

        TrainDebugCarriagePacket decoded = roundTrip(original);

        assertEquals(12, decoded.pIdx());
        assertEquals("cargo", decoded.variantId());
        assertEquals("container_wooden", decoded.contentsId());
        assertEquals("cagedzombie", decoded.subVariantId());
    }

    @Test
    @DisplayName("an empty sub-variant round-trips as empty — the parent's own contents won the draw")
    void roundTrip_noSubVariant() {
        TrainDebugCarriagePacket original =
            new TrainDebugCarriagePacket(true, -3, "flatbed", "piglin", "");

        TrainDebugCarriagePacket decoded = roundTrip(original);

        assertEquals(-3, decoded.pIdx());
        assertEquals("piglin", decoded.contentsId());
        assertTrue(decoded.subVariantId().isEmpty());
    }

    @Test
    @DisplayName("absent writes a single byte and carries no ids")
    void roundTrip_absent_writesOnlyTheFlag() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        TrainDebugCarriagePacket.absent().encode(buf);
        assertEquals(1, buf.writerIndex(), "absent must be a single boolean — no ids on the wire");

        assertEquals(TrainDebugCarriagePacket.absent(), TrainDebugCarriagePacket.decode(buf));
    }

    @Test
    @DisplayName("null ids are normalised to empty at construction")
    void nullIds_normalised() {
        TrainDebugCarriagePacket packet = new TrainDebugCarriagePacket(true, 1, null, null, null);

        assertEquals("", packet.variantId());
        assertEquals("", packet.contentsId());
        assertEquals("", packet.subVariantId());
    }

    private static TrainDebugCarriagePacket roundTrip(TrainDebugCarriagePacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        return TrainDebugCarriagePacket.decode(buf);
    }
}
