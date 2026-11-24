package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.PhotoReportReason;
import games.brennan.dungeontrain.compat.photo.PhotoReports;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player reported the found photo they are viewing, with a reason from
 * {@link PhotoReportReason}. Carries only the reason: the server reads the photo from the hand.
 * Sent just before the view closes, so it arrives ahead of {@link PhotographViewClosedPacket}.
 */
public record PhotoReportPacket(String reason) implements CustomPacketPayload {

    public static final Type<PhotoReportPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "photo_report"));

    public static final StreamCodec<FriendlyByteBuf, PhotoReportPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> buf.writeUtf(p.reason == null ? "" : p.reason, PhotoReportReason.MAX_CHARS),
            buf -> new PhotoReportPacket(buf.readUtf(PhotoReportReason.MAX_CHARS)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhotoReportPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            PhotoReports.report(player, packet.reason);
        });
    }
}
