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
 * Client → server: my broadcast ended on its own — not because you told me to.
 *
 * <p>Two things end a stream that only the streamer's client can see: the relay refusing the next
 * upload because someone in another world put a camcorder on ({@link Reason#CUT_OFF}), and the
 * stream never getting going or the encoder dying ({@link Reason#FAILED}). The server owns the
 * camcorder on the player's head, so it has to hear about both: a cut-off burns it away, a
 * failure hands it back. The server only acts if the sender really is its current streamer.</p>
 */
public record LiveStreamEndedPacket(Reason reason) implements CustomPacketPayload {

    public enum Reason {
        /** The relay gave the feed to someone else: the camcorder burns away. */
        CUT_OFF,
        /** The stream could not start or the encoder died: the camcorder goes back in the inventory. */
        FAILED
    }

    public static final Type<LiveStreamEndedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_stream_ended"));

    public static final StreamCodec<FriendlyByteBuf, LiveStreamEndedPacket> STREAM_CODEC =
        StreamCodec.of(LiveStreamEndedPacket::encode, LiveStreamEndedPacket::decode);

    private static void encode(FriendlyByteBuf buf, LiveStreamEndedPacket p) {
        buf.writeVarInt(p.reason.ordinal());
    }

    private static LiveStreamEndedPacket decode(FriendlyByteBuf buf) {
        int ord = buf.readVarInt();
        Reason[] all = Reason.values();
        return new LiveStreamEndedPacket(ord >= 0 && ord < all.length ? all[ord] : Reason.FAILED);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveStreamEndedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sender) {
                LiveFeedEvents.onClientEnded(sender, packet.reason());
            }
        });
    }
}
