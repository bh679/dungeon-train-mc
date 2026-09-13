package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.StagePreviewStamper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: one carriage as a Stage would stamp it — shell, the stage's linked parts, and
 * their block variants rolled from {@code seed} — to draw on the editor screen's Stages tab.
 *
 * <p>The composition is the server's ({@code CarriagePlacer}) and stays there: the client's tile
 * previewer bakes raw template files and knows nothing about part slots or sidecars. A new seed is
 * the Refresh button; a new carriage is Previous / Next.</p>
 */
public record StagePreviewRequestPacket(String stageId, String carriageId, long seed)
        implements CustomPacketPayload {

    public static final Type<StagePreviewRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "stage_preview_request"));

    public static final StreamCodec<FriendlyByteBuf, StagePreviewRequestPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.stageId, 64);
                buf.writeUtf(packet.carriageId, 64);
                buf.writeLong(packet.seed);
            },
            buf -> new StagePreviewRequestPacket(buf.readUtf(64), buf.readUtf(64), buf.readLong())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StagePreviewRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            StagePreviewPacket reply = StagePreviewStamper.compose(player, packet);
            // An answer always goes back, "nothing to draw" included: the client holds a slot open
            // for this ask and a silence would hold it forever.
            DungeonTrainNet.sendTo(player, reply);
        });
    }
}
