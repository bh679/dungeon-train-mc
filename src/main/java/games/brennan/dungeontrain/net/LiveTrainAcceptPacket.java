package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.LiveTrainOffer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: this player accepted the train's Live Eye offer {@code offerId}. The server only hands
 * one over for the offer it holds for them ({@link LiveTrainOffer#onAccept}), so a forged id earns nothing.
 */
public record LiveTrainAcceptPacket(String offerId) implements CustomPacketPayload {

    public static final Type<LiveTrainAcceptPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_train_accept"));

    public static final StreamCodec<FriendlyByteBuf, LiveTrainAcceptPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeUtf(p.offerId, 32), buf -> new LiveTrainAcceptPacket(buf.readUtf(32)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveTrainAcceptPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            LiveTrainOffer.onAccept(player, packet.offerId());
        });
    }
}
