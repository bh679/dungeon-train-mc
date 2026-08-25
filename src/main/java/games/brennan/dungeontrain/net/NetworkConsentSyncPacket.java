package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.KidTesterMirror;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.net.relay.KidTesterClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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
 *
 * <p>It is also where the relay is asked whether this player is a kid-safe tester
 * ({@link KidTesterClient} → {@link KidTesterMirror} → {@link KidTesterSyncPacket}). This is the right
 * moment for both halves of the question: the lookup sends the player's uuid to the relay, so it may
 * not happen before consent is known, and a consent WITHDRAWN mid-session has to take the resulting
 * control away again — which the same handler does by clearing the mark rather than re-asking.</p>
 */
public record NetworkConsentSyncPacket(boolean granted) implements CustomPacketPayload {

    public static final Type<NetworkConsentSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "network_consent_sync"));

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

    public static void handle(NetworkConsentSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            NetworkConsentMirror.set(player, packet.granted());
            refreshKidTester(player, packet.granted());
        });
    }

    /**
     * Re-answer "is this player a kid-safe tester?" for the consent that just landed, and tell their
     * client so the vote page knows whether to draw the control.
     *
     * <p>Consent denied is answered without asking anybody: no uuid leaves the machine, the mirror is
     * cleared, and the client is told {@code false}. Consent granted goes to the relay off-thread; the
     * callback hops back to the server thread before touching the mirror or sending anything, and
     * answers {@code false} on any failure, so an unreachable relay quietly grants nobody the control
     * rather than leaving a previous login's mark standing.</p>
     */
    private static void refreshKidTester(ServerPlayer player, boolean granted) {
        if (!granted) {
            KidTesterMirror.set(player.getUUID(), false);
            DungeonTrainNet.sendToPlayer(player, new KidTesterSyncPacket(false));
            return;
        }
        java.util.UUID id = player.getUUID();
        net.minecraft.server.MinecraftServer server = player.server;
        KidTesterClient.fetch(id, tester -> server.execute(() -> {
            KidTesterMirror.set(id, tester);
            // The player may have left while the request was in flight — look them up again rather
            // than holding the reference, and simply drop the answer if they are gone.
            ServerPlayer still = server.getPlayerList().getPlayer(id);
            if (still != null) DungeonTrainNet.sendToPlayer(still, new KidTesterSyncPacket(tester));
        }));
    }
}
