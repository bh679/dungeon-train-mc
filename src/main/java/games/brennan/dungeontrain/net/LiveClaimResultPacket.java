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
 * hears the outcome: {@link Result#LIVE} makes them a streamer (and, when the relay cut another player on
 * this server off for it, turns that player's Live Eye into a TV), {@link Result#REJECTED} and
 * {@link Result#STAYED_VIEWER} turn theirs into a TV. The
 * server only acts if the sender is wearing a pending Live Eye.</p>
 */
public record LiveClaimResultPacket(Result result, boolean tookOver) implements CustomPacketPayload {

    /** A result that cut nobody off (a REJECTED / STAYED_VIEWER outcome, or a LIVE claim on a free channel). */
    public LiveClaimResultPacket(Result result) {
        this(result, false);
    }

    public enum Result {
        /**
         * The relay gave this player a channel: they are live. {@link #tookOver()} says whether the
         * relay cut somebody off for it (its claim named a {@code prevName}); with the Two Streams reward
         * on, a second player gets a second channel and nobody is cut off.
         */
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
        buf.writeBoolean(p.tookOver);
    }

    private static LiveClaimResultPacket decode(FriendlyByteBuf buf) {
        int ord = buf.readVarInt();
        Result[] all = Result.values();
        boolean tookOver = buf.readBoolean();
        return new LiveClaimResultPacket(ord >= 0 && ord < all.length ? all[ord] : Result.STAYED_VIEWER, tookOver);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveClaimResultPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sender) {
                LiveFeedEvents.onTakeoverResult(sender, packet.result(), packet.tookOver());
            }
        });
    }
}
