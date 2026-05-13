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
 * <p>Row-start menus (isCompanion=false) additionally carry the full template
 * navigation chrome: a category bar listing every {@code EditorCategory} and a
 * type-tab strip listing every type within the active category. The renderer
 * uses these to draw one wide nav panel per anchor — categories on top, tab
 * strip below, expanded variant list inside the column matching the menu's
 * own {@code typeName}, collapsed tabs for the remaining types. Companion
 * menus (sub-variants) leave the chrome empty and render as a simple variant
 * list, unchanged from pre-nav behaviour.</p>
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
     *
     * <p>{@code activeCategoryId} is the lowercase id of the editor category
     * currently active ({@code EditorCategory.id()}) — empty for companions.
     * Used by the renderer to highlight the matching button in
     * {@code categoryBar}.</p>
     *
     * <p>{@code categoryBar} is the ordered list of every available category
     * shown as a row of buttons at the top of the nav panel. Empty for
     * companions.</p>
     *
     * <p>{@code typeStrip} is the ordered list of every type within
     * {@code activeCategoryId} — each entry becomes one tab in the strip
     * directly below the category bar. The tab whose {@code typeName} matches
     * the menu's outer {@code typeName} is rendered expanded (variant list
     * fills the column); every other tab is rendered collapsed (header only).
     * Empty for companions.</p>
     */
    public record Menu(
        BlockPos worldPos,
        String typeName,
        List<Variant> variants,
        boolean isCompanion,
        String activeCategoryId,
        List<CategoryButton> categoryBar,
        List<TypeTab> typeStrip,
        boolean isPackageMenu
    ) {
        /** Convenience: row-start menus default to {@code isCompanion=false} and empty nav chrome. */
        public Menu(BlockPos worldPos, String typeName, List<Variant> variants) {
            this(worldPos, typeName, variants, false, "", List.of(), List.of(), false);
        }

        /** Convenience: explicit companion flag, no nav chrome. */
        public Menu(BlockPos worldPos, String typeName, List<Variant> variants, boolean isCompanion) {
            this(worldPos, typeName, variants, isCompanion, "", List.of(), List.of(), false);
        }

        /** Convenience: full nav-menu constructor without the package flag — keeps the existing
         *  nav-menu call sites compiling unchanged. */
        public Menu(BlockPos worldPos, String typeName, List<Variant> variants, boolean isCompanion,
                    String activeCategoryId, List<CategoryButton> categoryBar, List<TypeTab> typeStrip) {
            this(worldPos, typeName, variants, isCompanion, activeCategoryId, categoryBar, typeStrip, false);
        }

        /** True when this menu carries the category bar + type strip (a row-start nav panel). */
        public boolean isNavMenu() {
            return !isCompanion && !isPackageMenu && !categoryBar.isEmpty();
        }
    }

    /**
     * One entry in the category bar at the top of a nav panel. {@code id} is
     * the lowercase command token ({@code EditorCategory.id()}) and dispatch
     * runs {@code /dt editor <id>}; {@code displayName} is the player-facing
     * label drawn in the button.
     */
    public record CategoryButton(String id, String displayName) {}

    /**
     * One column in the type-tab strip below the category bar.
     * {@code typeName} is the column header (matched against the outer
     * {@code Menu#typeName} to decide which tab is rendered expanded);
     * {@code category}/{@code modelId}/{@code modelName} carry the
     * teleport-target info for clicking a collapsed tab (mirrors the
     * existing {@code Variant} fields so the dispatch can call
     * {@code EditorPlotTeleport.commandFor} directly).
     */
    public record TypeTab(String typeName, String category, String modelId, String modelName) {}

    /**
     * One variant row inside a type menu.
     * {@code weight = EditorPlotLabelsPacket.NO_WEIGHT} means "no weight
     * pool" — the renderer omits the weight cell.
     *
     * <p>{@code isUser} is true when the variant has a file under
     * {@code <config>/dungeontrain/user/...} — i.e. the player saved it
     * themselves. The renderer paints a subtle blue background on user rows
     * so player-authored variants are distinguishable from bundled ones at a
     * glance.</p>
     *
     * <p>{@code isImported} is true when the variant came from a package
     * extracted by {@link games.brennan.dungeontrain.editor.UserContentImporter}
     * and hasn't been edited locally since. Renderer paints these rows
     * orange — same shape as the blue user-content tint but a different
     * hue so the player can tell at a glance which variants arrived from
     * a shared package vs which they authored themselves. Takes precedence
     * over {@code isUser} when both are true.</p>
     */
    public record Variant(
        String name,
        int weight,
        String category,
        String modelId,
        String modelName,
        boolean isUser,
        boolean isImported,
        List<Variant> subVariants
    ) {
        /** Convenience constructor for variants with no children — keeps every existing call site compiling unchanged. */
        public Variant(String name, int weight, String category, String modelId, String modelName,
                       boolean isUser, boolean isImported) {
            this(name, weight, category, modelId, modelName, isUser, isImported, List.of());
        }
    }

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
                encodeVariant(buf, v);
            }
            buf.writeUtf(m.activeCategoryId(), 32);
            buf.writeVarInt(m.categoryBar().size());
            for (CategoryButton b : m.categoryBar()) {
                buf.writeUtf(b.id(), 32);
                buf.writeUtf(b.displayName(), 64);
            }
            buf.writeVarInt(m.typeStrip().size());
            for (TypeTab t : m.typeStrip()) {
                buf.writeUtf(t.typeName(), 64);
                buf.writeUtf(t.category(), 32);
                buf.writeUtf(t.modelId(), 64);
                buf.writeUtf(t.modelName(), 64);
            }
            buf.writeBoolean(m.isPackageMenu());
        }
    }

    private static void encodeVariant(FriendlyByteBuf buf, Variant v) {
        buf.writeUtf(v.name(), 128);
        buf.writeVarInt(v.weight());
        buf.writeUtf(v.category(), 32);
        buf.writeUtf(v.modelId(), 64);
        buf.writeUtf(v.modelName(), 64);
        buf.writeBoolean(v.isUser());
        buf.writeBoolean(v.isImported());
        buf.writeVarInt(v.subVariants().size());
        for (Variant sv : v.subVariants()) {
            encodeVariant(buf, sv);
        }
    }

    private static Variant decodeVariant(FriendlyByteBuf buf) {
        String name = buf.readUtf(128);
        int weight = buf.readVarInt();
        String category = buf.readUtf(32);
        String modelId = buf.readUtf(64);
        String modelName = buf.readUtf(64);
        boolean isUser = buf.readBoolean();
        boolean isImported = buf.readBoolean();
        int sn = buf.readVarInt();
        List<Variant> subs = new ArrayList<>(sn);
        for (int k = 0; k < sn; k++) {
            subs.add(decodeVariant(buf));
        }
        return new Variant(name, weight, category, modelId, modelName, isUser, isImported, subs);
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
                variants.add(decodeVariant(buf));
            }
            String activeCategoryId = buf.readUtf(32);
            int bn = buf.readVarInt();
            List<CategoryButton> categoryBar = new ArrayList<>(bn);
            for (int j = 0; j < bn; j++) {
                String id = buf.readUtf(32);
                String displayName = buf.readUtf(64);
                categoryBar.add(new CategoryButton(id, displayName));
            }
            int tn = buf.readVarInt();
            List<TypeTab> typeStrip = new ArrayList<>(tn);
            for (int j = 0; j < tn; j++) {
                String tabName = buf.readUtf(64);
                String tabCategory = buf.readUtf(32);
                String tabModelId = buf.readUtf(64);
                String tabModelName = buf.readUtf(64);
                typeStrip.add(new TypeTab(tabName, tabCategory, tabModelId, tabModelName));
            }
            boolean isPackageMenu = buf.readBoolean();
            out.add(new Menu(pos, typeName, variants, isCompanion,
                activeCategoryId, categoryBar, typeStrip, isPackageMenu));
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
