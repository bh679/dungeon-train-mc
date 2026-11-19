package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.LiveCloseCallWatch;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the relay just told the streamer on air that this player is close to a rare challenge
 * ({@code event/LiveCloseCallEvents}). The client watches the relay for a Live Eye gift made out to it for
 * {@code watchSeconds} ({@link LiveCloseCallWatch}): without a TV on, nothing else would read it.
 */
public record LiveCloseCallPacket(int watchSeconds) implements CustomPacketPayload {

    /** Long enough for the streamer to notice the line and click it. */
    public static final int WATCH_SECONDS = 10 * 60;
    private static final int MAX_SECONDS = 30 * 60;

    public static final Type<LiveCloseCallPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_close_call"));

    public static final StreamCodec<FriendlyByteBuf, LiveCloseCallPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeVarInt(p.watchSeconds), buf -> new LiveCloseCallPacket(buf.readVarInt()));

    public LiveCloseCallPacket {
        watchSeconds = Math.max(0, Math.min(MAX_SECONDS, watchSeconds));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveCloseCallPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> LiveCloseCallWatch.start(packet.watchSeconds()));
    }
}
