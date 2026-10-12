package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.snapshot.AdvancementToastCapture;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the player just earned a milestone advancement; grab a screenshot of their screen
 * while the advancement toast is up (chat hidden) and send it back as {@link AdvancementPhotoPacket}
 * for the passenger-log announcement's image. Mirrors {@link CaptureEchoPacket}.
 */
public record CaptureAdvancementPacket(ResourceLocation advancementId) implements CustomPacketPayload {

    public static final Type<CaptureAdvancementPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "capture_advancement"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CaptureAdvancementPacket> STREAM_CODEC =
        StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, CaptureAdvancementPacket::advancementId,
            CaptureAdvancementPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CaptureAdvancementPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AdvancementToastCapture.request(packet.advancementId()));
    }
}
