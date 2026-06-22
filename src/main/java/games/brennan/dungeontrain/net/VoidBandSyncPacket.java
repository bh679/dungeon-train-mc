package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientVoidBand;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: tells the joining client the per-world facts it needs to locate the
 * disintegration band in world-X — the carriage length (band start = startCarriages
 * × length) and whether this world runs the train system at all. The client
 * combines these with the COMMON config (enabled / fade / core) to fade the sky
 * and fog toward the End look across the band. Sent once on login.
 */
public record VoidBandSyncPacket(int carriageLength, boolean startsWithTrain) implements CustomPacketPayload {

    public static final Type<VoidBandSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "void_band_sync"));

    public static final StreamCodec<FriendlyByteBuf, VoidBandSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeVarInt(packet.carriageLength);
                        buf.writeBoolean(packet.startsWithTrain);
                    },
                    buf -> new VoidBandSyncPacket(buf.readVarInt(), buf.readBoolean())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(VoidBandSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientVoidBand.update(packet.carriageLength, packet.startsWithTrain);
            ClientNetherBand.update(packet.startsWithTrain);
        });
    }
}
