package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.progress.ProgressAccounts;
import games.brennan.dungeontrain.progress.ProgressSyncService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: hand the server this player's relay progress credential on login, so the
 * server-side sync can read and write their canonical Ender Chest and progress state.
 *
 * <p>Shaped like {@link NetworkConsentSyncPacket} — the credential is a CLIENT-scope secret only the
 * client can read, so a login sync is the only way the server learns it.</p>
 *
 * <p>The client only sends this when it owns the server (single-player / LAN host); see
 * {@code ProgressSyncClient} for that gate and why it exists. Receiving the packet immediately kicks
 * the pull, because it is the moment the server first knows which account this player is.</p>
 */
public record ProgressAccountSyncPacket(String secret) implements CustomPacketPayload {

    /** Bound so a malformed/hostile packet can't make the server allocate an unreasonable string. */
    private static final int MAX_SECRET_CHARS = 256;

    public static final Type<ProgressAccountSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "progress_account_sync"));

    public static final StreamCodec<FriendlyByteBuf, ProgressAccountSyncPacket> STREAM_CODEC =
        StreamCodec.of(ProgressAccountSyncPacket::encode, ProgressAccountSyncPacket::decode);

    private static void encode(FriendlyByteBuf buf, ProgressAccountSyncPacket pkt) {
        buf.writeUtf(pkt.secret() == null ? "" : pkt.secret(), MAX_SECRET_CHARS);
    }

    private static ProgressAccountSyncPacket decode(FriendlyByteBuf buf) {
        return new ProgressAccountSyncPacket(buf.readUtf(MAX_SECRET_CHARS));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ProgressAccountSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            var server = player.getServer();
            // A dedicated server only accepts a credential when its operator has opted in. The client
            // has its own opt-in for sending one; both sides must agree, so an operator flipping this on
            // still receives nothing from a client that hasn't.
            if (server != null && server.isDedicatedServer()
                    && !DungeonTrainConfig.progressSyncOnDedicatedServers()) {
                return;
            }
            ProgressAccounts.set(player, packet.secret());
            ProgressSyncService.onCredentialReceived(player);
        });
    }

    /** Never print the secret — a record's default toString would. */
    @Override
    public String toString() {
        return "ProgressAccountSyncPacket[" + (secret == null || secret.isBlank() ? "empty" : "present") + "]";
    }
}
