package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionHudOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the closest train carriage index for the receiving player.
 * {@code present=false} means "no train within range — hide the suffix";
 * otherwise {@code pIdx} is the signed carriage index (0 = origin carriage,
 * positive forward, negative back). Sent only when the value changes so the
 * pipe stays quiet.
 */
public record CarriageIndexPacket(boolean present, int pIdx) implements CustomPacketPayload {

    public static final Type<CarriageIndexPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "carriage_index"));

    public static final StreamCodec<FriendlyByteBuf, CarriageIndexPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            CarriageIndexPacket::decode
        );

    public static CarriageIndexPacket absent() {
        return new CarriageIndexPacket(false, 0);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(present);
        if (present) {
            buf.writeVarInt(pIdx);
        }
    }

    public static CarriageIndexPacket decode(FriendlyByteBuf buf) {
        boolean present = buf.readBoolean();
        int pIdx = present ? buf.readVarInt() : 0;
        return new CarriageIndexPacket(present, pIdx);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CarriageIndexPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> VersionHudOverlay.setCarriageIndex(packet.present, packet.pIdx));
    }
}
