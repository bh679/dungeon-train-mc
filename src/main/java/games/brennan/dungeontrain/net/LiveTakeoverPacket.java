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
 * Client → server: I won a dead streamer's takeover; here is the relay's ticket. The server checks
 * it with the relay and swaps the TV on the sender's head for a Live Eye
 * ({@link LiveFeedEvents#onTakeoverWon}), which then goes live like any other.
 */
public record LiveTakeoverPacket(String ticket) implements CustomPacketPayload {

    /** Relay tickets are 48 hex characters; anything far longer is not one. */
    static final int MAX_TICKET = 128;

    public static final Type<LiveTakeoverPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_takeover"));

    public static final StreamCodec<FriendlyByteBuf, LiveTakeoverPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeUtf(p.ticket, MAX_TICKET), buf -> new LiveTakeoverPacket(buf.readUtf(MAX_TICKET)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveTakeoverPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sender) {
                LiveFeedEvents.onTakeoverWon(sender, packet.ticket());
            }
        });
    }
}
