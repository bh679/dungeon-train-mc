package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.OwnPhotoTribute;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player chose Tribute while viewing their own fresh print. Carries nothing —
 * the server reads the print from the player's hand and works out the cost itself.
 */
public record OwnPhotoTributePacket() implements CustomPacketPayload {

    public static final Type<OwnPhotoTributePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "own_photo_tribute"));

    public static final StreamCodec<FriendlyByteBuf, OwnPhotoTributePacket> STREAM_CODEC =
        StreamCodec.unit(new OwnPhotoTributePacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OwnPhotoTributePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            OwnPhotoTribute.pay(player);
        });
    }
}
