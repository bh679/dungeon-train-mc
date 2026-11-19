package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientBandDimension;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the dimension id the debug screen should print for the band the player is standing in
 * ({@link games.brennan.dungeontrain.worldgen.BandDimensionName}).
 *
 * <p>Sent to every player, unlike {@link TrainDebugBandPacket}, so it carries only the place they are
 * already standing in: no style, stage or lap, and nothing about what lies ahead. The band has to come
 * from the server because the classifier reads state the client does not hold.</p>
 *
 * @param id the id to print; empty means "print the real dimension"
 */
public record BandDimensionPacket(String id) implements CustomPacketPayload {

    public static final Type<BandDimensionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "band_dimension"));

    public static final StreamCodec<FriendlyByteBuf, BandDimensionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeUtf(packet.id),
            buf -> new BandDimensionPacket(buf.readUtf())
        );

    public BandDimensionPacket {
        id = id == null ? "" : id;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BandDimensionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientBandDimension.update(packet.id));
    }
}
