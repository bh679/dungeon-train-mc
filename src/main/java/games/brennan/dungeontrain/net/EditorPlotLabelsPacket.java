package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server → client snapshot of the floating name+weight labels that float above
 * each editor plot in the player's current category. Drives
 * {@link EditorPlotLabelsRenderer}, which billboards each label to the camera.
 *
 * <p>Each {@link Entry} carries a world position, a short display name, and a
 * pick weight. Use {@link #NO_WEIGHT} (matches
 * {@link EditorStatusPacket#NO_WEIGHT}) for templates that don't have a weight
 * pool (carriage parts) — the renderer omits the weight line in that case.</p>
 *
 * <p>Sent only when the player's category context or the underlying
 * (name, weight) tuple changes — see
 * {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer} for the
 * dedup loop. {@link #empty()} clears the client cache when the player exits
 * every plot.</p>
 */
public record EditorPlotLabelsPacket(List<Entry> entries) implements CustomPacketPayload {

    /** Weight sentinel — mirrors {@link EditorStatusPacket#NO_WEIGHT}. */
    public static final int NO_WEIGHT = EditorStatusPacket.NO_WEIGHT;

    /**
     * A single billboarded label anchor in the world. Carries enough metadata
     * for the floating panel's click handler to dispatch weight / save / reset
     * / clear / contents actions without re-resolving the plot from the
     * player's current position.
     *
     * <p>{@code category} of {@code ""} (empty string) signals "parts plot —
     * render plain name only, skip the action rows" (parts have a different
     * save/remove command shape).</p>
     *
     * <p>{@code inPlot} is true on exactly the entry whose plot the player is
     * currently standing inside; the renderer uses it to gate the interactive
     * controls (weight arrows + save/reset/clear + contents button) and to
     * draw a green border around the panel as a visual signal.</p>
     */
    public record Entry(
        BlockPos worldPos,
        String name,
        int weight,
        String category,
        String modelId,
        String modelName,
        boolean inPlot
    ) {}

    public static final Type<EditorPlotLabelsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_plot_labels"));

    public static final StreamCodec<FriendlyByteBuf, EditorPlotLabelsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorPlotLabelsPacket::decode
        );

    public static EditorPlotLabelsPacket empty() {
        return new EditorPlotLabelsPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeBlockPos(e.worldPos());
            buf.writeUtf(e.name(), 128);
            buf.writeVarInt(e.weight());
            buf.writeUtf(e.category(), 32);
            buf.writeUtf(e.modelId(), 64);
            buf.writeUtf(e.modelName(), 64);
            buf.writeBoolean(e.inPlot());
        }
    }

    public static EditorPlotLabelsPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n <= 0) return EditorPlotLabelsPacket.empty();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos pos = buf.readBlockPos();
            String name = buf.readUtf(128);
            int weight = buf.readVarInt();
            String category = buf.readUtf(32);
            String modelId = buf.readUtf(64);
            String modelName = buf.readUtf(64);
            boolean inPlot = buf.readBoolean();
            out.add(new Entry(pos, name, weight, category, modelId, modelName, inPlot));
        }
        return new EditorPlotLabelsPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorPlotLabelsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorPlotLabelsRenderer.applySnapshot(packet));
    }
}
