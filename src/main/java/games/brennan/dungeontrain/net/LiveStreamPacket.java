package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.LiveStreamController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: you are (or are no longer) the Live Feed streamer.
 *
 * <p>The server is the only authority on who wears the broadcast camcorder — it sees the head slot
 * change and sends {@link Action#START} to that one player. Everything relay-facing (claiming the channel,
 * encoding, uploading) happens on the streamer's own client, which is why this is a packet even in
 * single-player: a dedicated server works with no extra code, and the integrated server is just the
 * case where both halves share a JVM.</p>
 *
 * <p>{@code by} names who replaced the streamer for {@link Action#STOP_REPLACED}; empty otherwise.
 * {@code eyeId} is the worn Live Eye's id for {@link Action#START} ({@code item/LiveEyeId}), passed on
 * to the relay's claim so a relog can be told from a new Live Eye; empty otherwise.
 * Only the streamer is ever sent one of these — viewers learn everything from the relay.</p>
 */
public record LiveStreamPacket(Action action, String by, String eyeId) implements CustomPacketPayload {

    public enum Action {
        /** Put the headpiece on: claim the channel and start streaming. */
        START,
        /** Someone else on this server put one on. */
        STOP_REPLACED,
        /** The streamer died. */
        STOP_DIED,
        /** The streamer left the world (sent before the connection closes; the client also stops itself). */
        STOP_LEFT,
        /** The streamer took the camcorder off. */
        STOP_REMOVED,
        /** The streamer's run turned Free Play, which can't go live. */
        STOP_FREE_PLAY,
        /** The text-filter ladder struck the streamer out (or banned them): the camcorder burned. */
        STOP_MODERATED
    }

    private static final int MAX_NAME = 48;
    private static final int MAX_EYE_ID = 64;

    public static final Type<LiveStreamPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_stream"));

    public static final StreamCodec<FriendlyByteBuf, LiveStreamPacket> STREAM_CODEC =
        StreamCodec.of(LiveStreamPacket::encode, LiveStreamPacket::decode);

    public static LiveStreamPacket start(String eyeId) {
        return new LiveStreamPacket(Action.START, "", eyeId == null ? "" : eyeId);
    }

    public static LiveStreamPacket stop(Action why, String by) {
        return new LiveStreamPacket(why == Action.START ? Action.STOP_LEFT : why, by == null ? "" : by, "");
    }

    private static void encode(FriendlyByteBuf buf, LiveStreamPacket p) {
        buf.writeVarInt(p.action.ordinal());
        buf.writeUtf(p.by == null ? "" : p.by, MAX_NAME);
        buf.writeUtf(p.eyeId == null ? "" : p.eyeId, MAX_EYE_ID);
    }

    private static LiveStreamPacket decode(FriendlyByteBuf buf) {
        int ord = buf.readVarInt();
        Action[] all = Action.values();
        Action action = ord >= 0 && ord < all.length ? all[ord] : Action.STOP_LEFT;
        String by = buf.readUtf(MAX_NAME);
        return new LiveStreamPacket(action, by, buf.readUtf(MAX_EYE_ID));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveStreamPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> LiveStreamController.get().onServerSaid(packet));
    }
}
