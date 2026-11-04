package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.LivePlotPreview;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the plot's blocks as they stand, as the structure NBT a template file holds.
 * The answer to {@link EditorPlotPreviewRequestPacket}.
 *
 * <p>Bytes rather than a tag, for the reason {@link StagePreviewPacket} gives.</p>
 *
 * @param found false outside a plot, outside the editor world, or when the capture would not fit
 */
public record EditorPlotPreviewPacket(boolean found, byte[] template) implements CustomPacketPayload {

    public static final EditorPlotPreviewPacket NONE = new EditorPlotPreviewPacket(false, new byte[0]);

    public static final Type<EditorPlotPreviewPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_plot_preview"));

    public static final StreamCodec<FriendlyByteBuf, EditorPlotPreviewPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.found);
                buf.writeByteArray(packet.template);
            },
            buf -> new EditorPlotPreviewPacket(buf.readBoolean(), buf.readByteArray(StagePreviewPacket.MAX_BYTES))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorPlotPreviewPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> LivePlotPreview.accept(packet));
    }
}
