package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.OwnPhotoTributeClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: what this player's next Tribute to their own photo costs, in emeralds.
 * {@code 0} means it isn't offered (Free Play, or the player doesn't share).
 */
public record OwnPhotoTributeCostPacket(int cost) implements CustomPacketPayload {

    public static final Type<OwnPhotoTributeCostPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "own_photo_tribute_cost"));

    public static final StreamCodec<FriendlyByteBuf, OwnPhotoTributeCostPacket> STREAM_CODEC =
        ByteBufCodecs.VAR_INT.<FriendlyByteBuf>cast().map(OwnPhotoTributeCostPacket::new, OwnPhotoTributeCostPacket::cost);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OwnPhotoTributeCostPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> OwnPhotoTributeClientState.setCost(packet.cost()));
    }
}
