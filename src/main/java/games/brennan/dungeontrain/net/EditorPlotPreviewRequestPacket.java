package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.EditorPlotPreviewCapture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the plot the player stands in, as its blocks are right now — for the editor
 * screen's model after an offset, undo or redo press moved them.
 *
 * <p>The model otherwise bakes the saved file, which none of those presses touch. Sent after the
 * command that moved the blocks, so the server runs the two in that order and the capture sees the
 * move. No fields: the plot is the one the command acted on, found the way the command finds it.</p>
 */
public record EditorPlotPreviewRequestPacket() implements CustomPacketPayload {

    public static final Type<EditorPlotPreviewRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_plot_preview_request"));

    public static final StreamCodec<FriendlyByteBuf, EditorPlotPreviewRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> {}, buf -> new EditorPlotPreviewRequestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorPlotPreviewRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            DungeonTrainNet.sendTo(player, EditorPlotPreviewCapture.compose(player));
        });
    }
}
