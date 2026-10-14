package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.logrequest.VersionReportText;
import games.brennan.dungeontrain.logrequest.VersionRequestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the answer to version request {@code id}. Only accepted while that request is
 * outstanding — see {@link VersionRequestManager#onReport}.
 */
public record VersionReportPacket(int id, String text) implements CustomPacketPayload {

    public static final Type<VersionReportPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "version_report"));

    public static final StreamCodec<FriendlyByteBuf, VersionReportPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.id());
            buf.writeUtf(VersionReportText.cap(p.text()), VersionReportText.MAX_CHARS);
        }, buf -> new VersionReportPacket(buf.readVarInt(), buf.readUtf(VersionReportText.MAX_CHARS)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(VersionReportPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            VersionRequestManager.onReport(player, packet.id(), packet.text());
        });
    }
}
