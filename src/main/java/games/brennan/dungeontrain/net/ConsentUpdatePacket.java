package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DevMessageConsentClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: push the authoritative dev-message-consent state back so the client persists it
 * (see {@link games.brennan.dungeontrain.client.DevMessageConsentClient}). Sent when consent is
 * granted in-game (the player typed {@code @Dev}), so the client stores {@code granted=true}, the
 * server session token consent was granted in, and the fresh last-message timestamp.
 *
 * <p>Mirror of {@link ConsentSyncPacket}; carries the same three fields.</p>
 */
public record ConsentUpdatePacket(boolean granted, double grantSession, double lastMsgToDevMs)
        implements CustomPacketPayload {

    public static final Type<ConsentUpdatePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "consent_update"));

    public static final StreamCodec<FriendlyByteBuf, ConsentUpdatePacket> STREAM_CODEC =
        StreamCodec.of(ConsentUpdatePacket::encode, ConsentUpdatePacket::decode);

    private static void encode(FriendlyByteBuf buf, ConsentUpdatePacket pkt) {
        buf.writeBoolean(pkt.granted());
        buf.writeDouble(pkt.grantSession());
        buf.writeDouble(pkt.lastMsgToDevMs());
    }

    private static ConsentUpdatePacket decode(FriendlyByteBuf buf) {
        boolean granted = buf.readBoolean();
        double grantSession = buf.readDouble();
        double lastMsgToDevMs = buf.readDouble();
        return new ConsentUpdatePacket(granted, grantSession, lastMsgToDevMs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound — only runs on the physical client (mirrors {@code ShowFreePlayConfirmPacket}). */
    public static void handle(ConsentUpdatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            DevMessageConsentClient.applyUpdate(packet.granted(), packet.grantSession(), packet.lastMsgToDevMs()));
    }
}
