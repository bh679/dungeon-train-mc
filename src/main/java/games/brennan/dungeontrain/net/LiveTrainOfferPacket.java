package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.live.LiveGiftClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: nobody is live, so the train itself offers this close-call player the Live Eye
 * ({@code event/LiveTrainOffer}). The client prints [Accept] / [No] when it could take one
 * ({@link LiveGiftClient#onTrainOffer}); accepting sends {@link LiveTrainAcceptPacket} with {@code offerId}.
 */
public record LiveTrainOfferPacket(String offerId) implements CustomPacketPayload {

    public static final Type<LiveTrainOfferPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_train_offer"));

    public static final StreamCodec<FriendlyByteBuf, LiveTrainOfferPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeUtf(p.offerId, 32), buf -> new LiveTrainOfferPacket(buf.readUtf(32)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LiveTrainOfferPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> LiveGiftClient.onTrainOffer(packet.offerId()));
    }
}
