package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DistantComets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: a comet is on its way to strike the train, so the far sky fills with a shower of comets falling
 * the same way ({@code dx/dy/dz}, a unit vector) for {@code ticks} ticks, starting before it arrives and running on
 * after. Purely visual; the client draws the shower itself ({@link DistantComets}).
 */
public record CometShowerPacket(float dx, float dy, float dz, int ticks) implements CustomPacketPayload {

    public static final Type<CometShowerPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "comet_shower"));

    public static final StreamCodec<FriendlyByteBuf, CometShowerPacket> STREAM_CODEC =
        StreamCodec.of(CometShowerPacket::encode, CometShowerPacket::decode);

    private static void encode(FriendlyByteBuf buf, CometShowerPacket p) {
        buf.writeFloat(p.dx);
        buf.writeFloat(p.dy);
        buf.writeFloat(p.dz);
        buf.writeVarInt(p.ticks);
    }

    private static CometShowerPacket decode(FriendlyByteBuf buf) {
        return new CometShowerPacket(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CometShowerPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DistantComets.shower(packet));
    }
}
