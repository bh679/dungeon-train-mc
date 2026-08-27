package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format round-trip for {@link CarriageIndexPacket}, which grew a {@code variantId} — the
 * "cart type" the F3+4 debug panel reads — alongside the carriage index it already carried.
 *
 * <p>The load-bearing cases are the absent form (writes one boolean and nothing else, so a reader
 * that unconditionally reads the string would overrun) and the negative index (the field is a
 * VarInt, whose zigzag-free encoding of negatives is easy to get wrong).</p>
 */
final class CarriageIndexPacketTest {

    @Test
    @DisplayName("round-trip preserves a forward carriage with a variant")
    void roundTrip_forwardWithVariant() {
        CarriageIndexPacket original = new CarriageIndexPacket(true, 12, "cargo");
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("round-trip preserves a negative carriage index")
    void roundTrip_negativeIndex() {
        CarriageIndexPacket original = new CarriageIndexPacket(true, -42, "flatbed");
        CarriageIndexPacket decoded = roundTrip(original);
        assertEquals(-42, decoded.pIdx());
        assertEquals("flatbed", decoded.variantId());
    }

    @Test
    @DisplayName("absent packet writes no index or variant and decodes back to absent")
    void roundTrip_absent() {
        CarriageIndexPacket decoded = roundTrip(CarriageIndexPacket.absent());
        assertEquals(CarriageIndexPacket.absent(), decoded);
        assertTrue(decoded.variantId().isEmpty());
    }

    @Test
    @DisplayName("an unknown variant round-trips as an empty id, not a crash")
    void roundTrip_emptyVariant() {
        CarriageIndexPacket original = new CarriageIndexPacket(true, 3, "");
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("a null variant id is normalised to empty at construction")
    void nullVariantId_normalised() {
        assertEquals("", new CarriageIndexPacket(true, 3, null).variantId());
    }

    private static CarriageIndexPacket roundTrip(CarriageIndexPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        return CarriageIndexPacket.decode(buf);
    }
}
