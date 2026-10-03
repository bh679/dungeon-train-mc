package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.MilestonePostBuffer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the screenshot taken for {@link CaptureAdvancementPacket} (JPEG bytes, empty when
 * none could be taken so the server posts promptly). Same 1 MB cap as {@link DeathPhotoPacket}.
 */
public record AdvancementPhotoPacket(ResourceLocation advancementId, byte[] image) implements CustomPacketPayload {

    public static final Type<AdvancementPhotoPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "advancement_photo"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdvancementPhotoPacket> STREAM_CODEC =
        StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, AdvancementPhotoPacket::advancementId,
            ByteBufCodecs.byteArray(1024 * 1024), AdvancementPhotoPacket::image,
            AdvancementPhotoPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AdvancementPhotoPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MilestonePostBuffer.onPhoto(player, packet.advancementId(), packet.image());
        });
    }
}
