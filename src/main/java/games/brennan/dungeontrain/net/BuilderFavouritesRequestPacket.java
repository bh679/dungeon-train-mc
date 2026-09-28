package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: what have I starred?
 *
 * <p>{@link BuilderProfileRequestPacket}'s sibling, and deliberately smaller than it: a favourites
 * list is always the caller's own, so there is no "whose" to name and nothing for a dev build to point
 * somewhere else. The only field is {@code live}, which picks the relay the same way — and through the
 * same fail-closed rule, so a release server reads its own pool however the packet was crafted.</p>
 *
 * <p>The reply is a {@link BuilderFavouritesPacket}, sent once the relay answers rather than in this
 * handler: the fetch is a network call and the server thread does not wait on one.</p>
 */
public record BuilderFavouritesRequestPacket(boolean live) implements CustomPacketPayload {

    public static final Type<BuilderFavouritesRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_favourites_request"));

    public static final StreamCodec<FriendlyByteBuf, BuilderFavouritesRequestPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeBoolean(packet.live),
            buf -> new BuilderFavouritesRequestPacket(buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderFavouritesRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // The same gate My Builds reports, reported the same way: which limb closed decides what
            // the screen can tell the player to do about it, so the two are never collapsed into one
            // "no favourites" — which is what a player who declined consent would otherwise be told.
            BuilderProfilePacket.Status blocked = BuilderProfileRequestPacket.blockedReason(player);
            if (blocked != null) {
                DungeonTrainNet.sendTo(player, BuilderFavouritesPacket.of(blocked));
                return;
            }
            String relay = RelayTarget.of(BuilderProfileRequestPacket.liveRequested(packet.live));
            SharedCarriageClient.listFavourites(player.getUUID().toString(), relay).thenAccept(favs -> {
                if (player.getServer() == null) return;
                player.getServer().execute(() -> {
                    if (player.hasDisconnected()) return;
                    DungeonTrainNet.sendTo(player, favs == null
                            ? BuilderFavouritesPacket.of(BuilderProfilePacket.Status.UNAVAILABLE)
                            : BuilderFavouritesPacket.of(favs));
                });
            });
        });
    }
}
