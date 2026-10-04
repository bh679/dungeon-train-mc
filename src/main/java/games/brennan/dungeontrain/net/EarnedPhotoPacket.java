package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.EarnedPhotos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the local player just earned {@code advancement} with the photo whose Exposure id is
 * {@code exposureId}, printed on {@code photoType}'s paper — or, with a non-empty {@code entry}, logged that
 * photo as one entry of a collection's album (an animal, a mob, a biome). The client fetches the image and keeps its own copy, shown when the advancement
 * is clicked — see {@link EarnedPhotos}.
 */
public record EarnedPhotoPacket(ResourceLocation advancement, String exposureId, ResourceLocation photoType,
                                String entry) implements CustomPacketPayload {

    public static final Type<EarnedPhotoPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "earned_photo"));

    public static final StreamCodec<FriendlyByteBuf, EarnedPhotoPacket> STREAM_CODEC = StreamCodec.composite(
        ResourceLocation.STREAM_CODEC, EarnedPhotoPacket::advancement,
        ByteBufCodecs.STRING_UTF8, EarnedPhotoPacket::exposureId,
        ResourceLocation.STREAM_CODEC, EarnedPhotoPacket::photoType,
        ByteBufCodecs.STRING_UTF8, EarnedPhotoPacket::entry,
        EarnedPhotoPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler — only ever runs on the physical client. */
    public static void handle(EarnedPhotoPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EarnedPhotos.capture(packet.advancement(), packet.exposureId(), packet.photoType(), packet.entry()));
    }
}
