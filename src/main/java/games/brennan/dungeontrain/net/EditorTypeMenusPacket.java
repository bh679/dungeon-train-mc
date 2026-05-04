package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer;
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
 * Server → client snapshot of the floating template-type menus that float at
 * the start of each variant row. Each {@link Menu} is a billboarded panel
 * carrying a header (the type name) and a list of clickable variant rows.
 *
 * <p>Variant rows have two cells: name (left) → teleport on click, weight
 * ({@code ×N} on the right) → bump on click ({@code +1} normal, {@code -1}
 * shift). Parts have no weight pool, so the weight cell is skipped on
 * parts-kind menus — see {@link EditorTypeMenuRenderer} for layout.</p>
 *
 * <p>Empty list ({@link #empty()}) clears the client cache when the editor
 * exits or switches categories — same lifecycle as
 * {@link EditorPlotLabelsPacket}.</p>
 */
public record EditorTypeMenusPacket(List<Menu> menus) implements CustomPacketPayload {

    /**
     * A single billboarded type menu.
     *
     * <p>{@code isCompanion} marks the in-plot duplicate menu that floats
     * next to the per-plot panel. Companion menus use the per-plot panel's
     * own {@code worldPos} as their anchor and the renderer translates them
     * sideways in panel-local space (after the cylindrical billboard basis)
     * so the two panels share orientation and read as one extended UI.</p>
     */
    public record Menu(BlockPos worldPos, String typeName, List<Variant> variants, boolean isCompanion) {
        /** Convenience: row-start menus default to {@code isCompanion=false}. */
        public Menu(BlockPos worldPos, String typeName, List<Variant> variants) {
            this(worldPos, typeName, variants, false);
        }
    }

    /**
     * One variant row inside a type menu.
     * {@code weight = EditorPlotLabelsPacket.NO_WEIGHT} means "no weight
     * pool" — the renderer omits the weight cell.
     */
    public record Variant(
        String name,
        int weight,
        String category,
        String modelId,
        String modelName
    ) {}

    public static final Type<EditorTypeMenusPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_type_menus"));

    public static final StreamCodec<FriendlyByteBuf, EditorTypeMenusPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorTypeMenusPacket::decode
        );

    public static EditorTypeMenusPacket empty() {
        return new EditorTypeMenusPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return menus.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(menus.size());
        for (Menu m : menus) {
            buf.writeBlockPos(m.worldPos());
            buf.writeUtf(m.typeName(), 64);
            buf.writeBoolean(m.isCompanion());
            buf.writeVarInt(m.variants().size());
            for (Variant v : m.variants()) {
                buf.writeUtf(v.name(), 128);
                buf.writeVarInt(v.weight());
                buf.writeUtf(v.category(), 32);
                buf.writeUtf(v.modelId(), 64);
                buf.writeUtf(v.modelName(), 64);
            }
        }
    }

    public static EditorTypeMenusPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n <= 0) return EditorTypeMenusPacket.empty();
        List<Menu> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos pos = buf.readBlockPos();
            String typeName = buf.readUtf(64);
            boolean isCompanion = buf.readBoolean();
            int vn = buf.readVarInt();
            List<Variant> variants = new ArrayList<>(vn);
            for (int j = 0; j < vn; j++) {
                String name = buf.readUtf(128);
                int weight = buf.readVarInt();
                String category = buf.readUtf(32);
                String modelId = buf.readUtf(64);
                String modelName = buf.readUtf(64);
                variants.add(new Variant(name, weight, category, modelId, modelName));
            }
            out.add(new Menu(pos, typeName, variants, isCompanion));
        }
        return new EditorTypeMenusPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorTypeMenusPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorTypeMenuRenderer.applySnapshot(packet));
    }
}
