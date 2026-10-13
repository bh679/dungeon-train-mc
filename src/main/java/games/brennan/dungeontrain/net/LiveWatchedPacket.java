package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.LiveWatchTally;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: "I watched the Live Feed for {@code seconds} since my last report" — one per live
 * status poll the relay credits toward "Longest Watching". The server adds it to this life's stat-book
 * figure ({@link LiveWatchTally}), bounded by real time.
 */
public record LiveWatchedPacket(int seconds) implements CustomPacketPayload {

    public static final Type<LiveWatchedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_watched"));

    public static final StreamCodec<FriendlyByteBuf, LiveWatchedPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeVarInt(p.seconds), buf -> new LiveWatchedPacket(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveWatchedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                LiveWatchTally.report(player, packet.seconds(), System.currentTimeMillis());
            }
        });
    }
}
