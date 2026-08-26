package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: what have I built?
 *
 * <p>Empty payload — the answer is about whoever asked, and the server knows who that is. The reply is
 * a {@link BuilderProfilePacket}, sent once the relay answers rather than in this handler: the fetch is
 * a network call and the server thread does not wait on one.</p>
 */
public record BuilderProfileRequestPacket() implements CustomPacketPayload {

    public static final Type<BuilderProfileRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_request"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileRequestPacket> STREAM_CODEC =
        StreamCodec.unit(new BuilderProfileRequestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // The same gate the upload uses: with profiles off, or without this player's network
            // consent, nothing of theirs is on the relay and nothing should be asked about them.
            if (!BuilderRelayUpload.canUpload(player)) {
                DungeonTrainNet.sendTo(player, BuilderProfilePacket.of(BuilderProfilePacket.Status.DISABLED));
                return;
            }
            SharedCarriageClient.listMine(player.getUUID().toString()).thenAccept(rows -> {
                if (player.getServer() == null) return;
                player.getServer().execute(() -> {
                    if (player.hasDisconnected()) return;
                    DungeonTrainNet.sendTo(player, rows == null
                            ? BuilderProfilePacket.of(BuilderProfilePacket.Status.UNAVAILABLE)
                            : BuilderProfilePacket.of(rows));
                });
            });
        });
    }
}
