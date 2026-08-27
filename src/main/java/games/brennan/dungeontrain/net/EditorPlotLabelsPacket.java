package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.PlotCategory;
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
     * draw a coloured border around the panel as a visual signal.</p>
     *
     * <p>{@code isUser} is true when the template has a file under
     * {@code <config>/dungeontrain/user/...} — i.e. the player has saved it
     * themselves. The renderer swaps the border colour from green to blue so
     * the player can tell at a glance which templates they authored vs which
     * ship with the mod jar.</p>
     *
     * <p>{@code isImported} is true when the variant's file lives under
     * {@code <config>/dungeontrain/imported/<package>/...} and no
     * shadowing copy exists in {@code user/}. The player can revert from
     * imported to bundled by deleting the package directory, or edit-and-
     * save through the editor to create a user-folder copy that shadows
     * the imported one (flipping the variant from orange to blue).
     * Takes precedence over {@code isUser} for rendering: imported
     * variants get an orange tint, user-saved variants get blue, bundled
     * variants stay green.</p>
     */
    public record Entry(
        BlockPos worldPos,
        String name,
        int weight,
        String category,
        String modelId,
        String modelName,
        boolean inPlot,
        boolean isUser,
        boolean isImported,
        int roomLength,
        int roomWidth,
        int roomHeight,
        String roomMode,
        String copiesFloorBlock,
        String copiesRoofBlock
    ) {
        public Entry {
            // Never null: the renderer reads them every frame the panel is up, and "" is the
            // "nothing authored yet" case the rows draw their hint for.
            if (copiesFloorBlock == null) copiesFloorBlock = "";
            if (copiesRoofBlock == null) copiesRoofBlock = "";
        }

        /**
         * {@link #category()} resolved to the type the client routes on, or {@code null} when the
         * row carries no category — {@code ""} for a row that has none, and anything the wire
         * offers that is not a category.
         *
         * <p>Derived rather than stored: the field stays a {@code String} because it is the wire
         * format, but nothing downstream of here should be comparing category strings.</p>
         */
        public PlotCategory plotCategory() {
            return PlotCategory.fromId(category).orElse(null);
        }

        /** The shape before the Copies palette existed — no blocks to draw icons for. */
        public Entry(BlockPos worldPos, String name, int weight, String category,
                     String modelId, String modelName,
                     boolean inPlot, boolean isUser, boolean isImported,
                     int roomLength, int roomWidth, int roomHeight, String roomMode) {
            this(worldPos, name, weight, category, modelId, modelName, inPlot, isUser, isImported,
                roomLength, roomWidth, roomHeight, roomMode, "", "");
        }

        /** Back-compat shape for every category but PORTALS — no authored size or mode to show. */
        public Entry(BlockPos worldPos, String name, int weight, String category,
                     String modelId, String modelName,
                     boolean inPlot, boolean isUser, boolean isImported) {
            this(worldPos, name, weight, category, modelId, modelName,
                inPlot, isUser, isImported, NO_SIZE, NO_SIZE, NO_SIZE, NO_MODE, "", "");
        }

        /** Back-compat shape from before portal rooms carried a mode. */
        public Entry(BlockPos worldPos, String name, int weight, String category,
                     String modelId, String modelName,
                     boolean inPlot, boolean isUser, boolean isImported,
                     int roomLength, int roomWidth, int roomHeight) {
            this(worldPos, name, weight, category, modelId, modelName,
                inPlot, isUser, isImported, roomLength, roomWidth, roomHeight, NO_MODE);
        }
    }

    /**
     * Sentinel for "this model has no authored size" — every category but portal rooms, whose plot
     * box the author chooses rather than the kind fixing it.
     */
    public static final int NO_SIZE = -1;

    /** Sentinel for "this model has no mode row" — everything but a portal room. */
    public static final String NO_MODE = "";

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
            buf.writeBoolean(e.isUser());
            buf.writeBoolean(e.isImported());
            buf.writeVarInt(e.roomLength());
            buf.writeVarInt(e.roomWidth());
            buf.writeVarInt(e.roomHeight());
            buf.writeUtf(e.roomMode(), EditorStatusPacket.MODE_TAG_MAX);
            buf.writeUtf(e.copiesFloorBlock(), 128);
            buf.writeUtf(e.copiesRoofBlock(), 128);
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
            boolean isUser = buf.readBoolean();
            boolean isImported = buf.readBoolean();
            int roomLength = buf.readVarInt();
            int roomWidth = buf.readVarInt();
            int roomHeight = buf.readVarInt();
            String roomMode = buf.readUtf(EditorStatusPacket.MODE_TAG_MAX);
            String copiesFloorBlock = buf.readUtf(128);
            String copiesRoofBlock = buf.readUtf(128);
            out.add(new Entry(pos, name, weight, category, modelId, modelName,
                inPlot, isUser, isImported, roomLength, roomWidth, roomHeight, roomMode,
                copiesFloorBlock, copiesRoofBlock));
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
