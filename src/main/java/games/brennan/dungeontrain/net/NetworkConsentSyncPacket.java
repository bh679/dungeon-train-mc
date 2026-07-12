package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

/**
 * Client → server: seed the server's per-player network-consent mirror from the client's persisted
 * Discord Presence "use the internet?" consent, sent on login (and again if the client's consent
 * changes while connected).
 *
 * <p>The client is authoritative — the consent is a CLIENT-scope config on Discord Presence
 * ({@code DiscordPresenceClientConfig.isGranted()}), so the server can only know it if the client
 * tells it. The server keeps a per-session mirror ({@link NetworkConsentMirror}) so the community
 * shared-books contribution gate ({@link games.brennan.dungeontrain.event.SharedBookGate}) can decide,
 * when a player signs a book, whether uploading their text to the relay is permitted.</p>
 *
 * <p>Mirror of {@link ConsentSyncPacket}'s shape — a single {@code boolean} payload, its own
 * {@link Type} id under the mod namespace, and a {@link StreamCodec} built from encode/decode.</p>
 */
public record NetworkConsentSyncPacket(boolean granted) implements CustomPacketPayload {

    public static final Type<NetworkConsentSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "network_consent_sync"));

    public static final StreamCodec<FriendlyByteBuf, NetworkConsentSyncPacket> STREAM_CODEC =
        StreamCodec.of(NetworkConsentSyncPacket::encode, NetworkConsentSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, NetworkConsentSyncPacket pkt) {
        buf.writeBoolean(pkt.granted());
    }

    private static NetworkConsentSyncPacket decode(FriendlyByteBuf buf) {
        return new NetworkConsentSyncPacket(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(NetworkConsentSyncPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            NetworkConsentMirror.set(player, packet.granted());
        });
    }
}
