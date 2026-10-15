package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.LiveGiftServer;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: this player accepted the streamer's Live Eye gift {@code giftId}, offered on the
 * relay of the channel its TVs show ({@code dev}). The server redeems it with the relay before handing
 * anything over ({@link LiveGiftServer#onAccept}), so a forged id earns nothing.
 */
public record LiveGiftAcceptPacket(String giftId, boolean dev) implements CustomPacketPayload {

    public static final Type<LiveGiftAcceptPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_gift_accept"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LiveGiftAcceptPacket> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), LiveGiftAcceptPacket::giftId,
            ByteBufCodecs.BOOL, LiveGiftAcceptPacket::dev,
            LiveGiftAcceptPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveGiftAcceptPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            LiveGiftServer.onAccept(player, packet.giftId(), packet.dev() ? Channel.DEV : Channel.LIVE);
        });
    }
}
