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
 *
 * <p>{@link #cutOff()} is the name the relay gave as the claim's {@code prevName}: the streamer it cut
 * off, or empty when nobody was (a free channel, or the Two Streams reward's second channel). Only a
 * current streamer on this server with that name is demoted. What stays trusted: a pending wearer's
 * client can say LIVE without a real relay claim, and can name a real streamer here to demote them.
 * Both need a modified client, and the worst they do is turn a Live Eye into a TV.</p>
 */
public record LiveClaimResultPacket(Result result, String cutOff) implements CustomPacketPayload {

    /** Longest name the packet carries; the relay's names are Minecraft names (16). */
    public static final int MAX_NAME = 64;

    public LiveClaimResultPacket {
        cutOff = cutOff == null ? "" : cutOff;
    }

    /** A result that cut nobody off (a REJECTED / STAYED_VIEWER outcome, or a LIVE claim on a free channel). */
    public LiveClaimResultPacket(Result result) {
        this(result, "");
    }

    public enum Result {
        /**
         * The relay gave this player a channel: they are live. {@link #cutOff()} names whoever the
         * relay cut off for it (its claim's {@code prevName}); with the Two Streams reward on, a second
         * player gets a second channel and nobody is cut off.
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
        String name = p.cutOff;
        buf.writeUtf(name.length() > MAX_NAME ? "" : name, MAX_NAME);
    }

    private static LiveClaimResultPacket decode(FriendlyByteBuf buf) {
        int ord = buf.readVarInt();
        Result[] all = Result.values();
        String cutOff = buf.readUtf(MAX_NAME);
        return new LiveClaimResultPacket(ord >= 0 && ord < all.length ? all[ord] : Result.STAYED_VIEWER, cutOff);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveClaimResultPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sender) {
                LiveFeedEvents.onTakeoverResult(sender, packet.result(), packet.cutOff());
            }
        });
    }
}
