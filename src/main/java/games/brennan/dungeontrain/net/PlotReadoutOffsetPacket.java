package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPlotReadoutOffset;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: how far the train's Sable plot sat from its real X and Z when it spawned
 * ({@code DungeonTrainWorldData#setPlotReadoutOffset}). Readouts of a targeted carriage block take it off
 * so they read near where the train started. Sent to every player on join and whenever it changes
 * ({@code event/PlotReadoutOffsetEvents}).
 *
 * @param known false for a world whose train spawned before the offset was recorded
 */
public record PlotReadoutOffsetPacket(boolean known, long dx, long dz) implements CustomPacketPayload {

    public static final Type<PlotReadoutOffsetPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "plot_readout_offset"));

    public static final StreamCodec<FriendlyByteBuf, PlotReadoutOffsetPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.known);
                buf.writeLong(packet.dx);
                buf.writeLong(packet.dz);
            },
            buf -> new PlotReadoutOffsetPacket(buf.readBoolean(), buf.readLong(), buf.readLong())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlotReadoutOffsetPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPlotReadoutOffset.update(packet.known, packet.dx, packet.dz));
    }
}
