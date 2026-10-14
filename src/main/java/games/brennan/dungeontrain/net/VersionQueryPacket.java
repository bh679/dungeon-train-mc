package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.logrequest.VersionRequestClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the Developer asked which version this player's game runs (request {@code id};
 * {@code full} = every mod too). The client answers silently — see {@link VersionRequestClient}.
 */
public record VersionQueryPacket(int id, boolean full) implements CustomPacketPayload {

    public static final Type<VersionQueryPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "version_query"));

    public static final StreamCodec<FriendlyByteBuf, VersionQueryPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.id());
            buf.writeBoolean(p.full());
        }, buf -> new VersionQueryPacket(buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client. */
    public static void handle(VersionQueryPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> VersionRequestClient.onQuery(packet.id(), packet.full()));
    }
}
