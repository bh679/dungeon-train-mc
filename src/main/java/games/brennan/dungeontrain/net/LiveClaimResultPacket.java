package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.LiveFeedEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: what the relay made of this player's Live Eye claim.
 *
 * <p>Putting a Live Eye on no longer makes its wearer the streamer at once. Their client claims the
 * channel first; when someone else holds it they are asked whether to take over (a vote inside the
 * holder's first ten minutes, their own call after). The server owns the item on their head, so it
 * hears the outcome: {@link Result#LIVE} makes them the streamer (and turns the previous one's Live Eye
 * into a TV), {@link Result#REJECTED} and {@link Result#STAYED_VIEWER} turn theirs into a TV. The
 * server only acts if the sender is wearing a pending Live Eye.</p>
 */
public record LiveClaimResultPacket(Result result) implements CustomPacketPayload {

    public enum Result {
        /** The relay gave this player the channel: they are live. */
        LIVE,
        /** The audience voted the takeover down: the Live Eye becomes a TV. */
        REJECTED,
        /** The player chose to stay a viewer: the Live Eye becomes a TV. */
        STAYED_VIEWER
    }

    public static final Type<LiveClaimResultPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_claim_result"));

    public static final StreamCodec<FriendlyByteBuf, LiveClaimResultPacket> STREAM_CODEC =
        StreamCodec.of(LiveClaimResultPacket::encode, LiveClaimResultPacket::decode);

    private static void encode(FriendlyByteBuf buf, LiveClaimResultPacket p) {
        buf.writeVarInt(p.result.ordinal());
    }

    private static LiveClaimResultPacket decode(FriendlyByteBuf buf) {
        int ord = buf.readVarInt();
        Result[] all = Result.values();
        return new LiveClaimResultPacket(ord >= 0 && ord < all.length ? all[ord] : Result.STAYED_VIEWER);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveClaimResultPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sender) {
                LiveFeedEvents.onTakeoverResult(sender, packet.result());
            }
        });
    }
}
