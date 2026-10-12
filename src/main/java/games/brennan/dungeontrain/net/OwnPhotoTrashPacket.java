package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.OwnPhotoTrash;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player chose Trash while viewing their own fresh print. Carries nothing —
 * the server reads the print from the player's hand itself.
 */
public record OwnPhotoTrashPacket() implements CustomPacketPayload {

    public static final Type<OwnPhotoTrashPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "own_photo_trash"));

    public static final StreamCodec<FriendlyByteBuf, OwnPhotoTrashPacket> STREAM_CODEC =
        StreamCodec.unit(new OwnPhotoTrashPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OwnPhotoTrashPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            OwnPhotoTrash.trash(player);
        });
    }
}
