package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
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
 * and fog toward the End look across the band. {@code trainY} is the carriage height,
 * needed client-side to place the upside-down mirror plane for the exit-crossfade
 * render Y-split; {@code bedrockY} is the world's terrain floor, which the client
 * cannot derive (it lives on the chunk generator) and which bounds the sealed space
 * portal twins are stamped into — the one part of an upside-down band that must
 * <em>not</em> render flipped. Sent once on login.
 */
public record VoidBandSyncPacket(int carriageLength, boolean startsWithTrain, int trainY,
                                 int bedrockY) implements CustomPacketPayload {

    public static final Type<VoidBandSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "void_band_sync"));

    public static final StreamCodec<FriendlyByteBuf, VoidBandSyncPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeVarInt(packet.carriageLength);
                        buf.writeBoolean(packet.startsWithTrain);
                        buf.writeVarInt(packet.trainY);
                        buf.writeInt(packet.bedrockY);
                    },
                    buf -> new VoidBandSyncPacket(
                            buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(VoidBandSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientVoidBand.update(packet.carriageLength, packet.startsWithTrain);
            ClientNetherBand.update(packet.startsWithTrain);
            ClientUpsideDownBand.update(packet.startsWithTrain, packet.trainY, packet.bedrockY);
        });
    }
}
