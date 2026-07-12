package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.event.DevMessageConsent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

/**
 * Client → server: seed the server's per-player dev-message-consent mirror from the client's
 * persisted state, sent once when the player logs in.
 *
 * <p>Consent must survive a world reload and is fed partly by the client-only main-menu chat, so
 * the client is the authoritative store (see
 * {@link games.brennan.dungeontrain.client.DevMessageConsentClient}). The server keeps a
 * per-session mirror to decide, when a relayed Developer message arrives, whether to show it in
 * in-game chat directly or gate it behind the consent prompt — see {@link DevMessageConsent}.</p>
 *
 * <ul>
 *   <li>{@code granted} — has the player accepted a Developer message before?</li>
 *   <li>{@code grantSession} — the server session token in which consent was granted (matched
 *       against the current session so consent stays valid indefinitely within the same world).</li>
 *   <li>{@code lastMsgToDevMs} — wall-clock millis of the player's last message to the dev
 *       (in-game chat after consent, or a main-menu chat send); anchors the 20-minute window.</li>
 * </ul>
 *
 * <p>Session token and timestamp are carried as {@code double} (millis are exact well past any
 * realistic date) so no {@code long} config type is required client-side.</p>
 */
public record ConsentSyncPacket(boolean granted, double grantSession, double lastMsgToDevMs)
        implements CustomPacketPayload {

    public static final Type<ConsentSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "consent_sync"));

    public static final StreamCodec<FriendlyByteBuf, ConsentSyncPacket> STREAM_CODEC =
        StreamCodec.of(ConsentSyncPacket::encode, ConsentSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, ConsentSyncPacket pkt) {
        buf.writeBoolean(pkt.granted());
        buf.writeDouble(pkt.grantSession());
        buf.writeDouble(pkt.lastMsgToDevMs());
    }

    private static ConsentSyncPacket decode(FriendlyByteBuf buf) {
        boolean granted = buf.readBoolean();
        double grantSession = buf.readDouble();
        double lastMsgToDevMs = buf.readDouble();
        return new ConsentSyncPacket(granted, grantSession, lastMsgToDevMs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ConsentSyncPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            DevMessageConsent.onClientSync(player, packet.granted(), packet.grantSession(), packet.lastMsgToDevMs());
        });
    }
}
