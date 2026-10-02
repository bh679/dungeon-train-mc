package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.SharedPhotos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player chose Tribute on the screen shown after closing a found photo.
 * Carries nothing — the server reads the photo from the player's hand and takes the diamond itself.
 */
public record PhotoTributePacket() implements CustomPacketPayload {

    public static final Type<PhotoTributePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "photo_tribute"));

    public static final StreamCodec<FriendlyByteBuf, PhotoTributePacket> STREAM_CODEC =
        StreamCodec.unit(new PhotoTributePacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhotoTributePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            SharedPhotos.payTribute(player);
        });
    }
}
