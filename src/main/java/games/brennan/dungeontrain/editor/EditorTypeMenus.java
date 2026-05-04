package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Server-side enumerator for the floating template-type menus that float at
 * the start of each variant row. One {@link EditorTypeMenusPacket.Menu} per
 * visible row in a category.
 *
 * <p>Layout (row → menu anchor):
 * <ul>
 *   <li>X-extending rows (carriages, contents, parts kinds) — anchor sits
 *       one {@link EditorLayout#GAP} past the first plot's {@code -X} cage
 *       edge, at the per-plot label Y, centred over the footprint Z.</li>
 *   <li>Z-extending rows (track tile, pillars, adjuncts, tunnels) — anchor
 *       sits one {@code GAP} past the first plot's {@code -Z} cage edge, at
 *       the per-plot label Y, centred over the footprint X.</li>
 * </ul>
 *
 * <p>Mirrors the parts-fallthrough that
 * {@link EditorPlotLabels#carriageLabels} uses — a CARRIAGES-view menu set
 * includes the carriage-row menu plus all four parts kind menus, since
 * {@code runEnterCategory(CARRIAGES)} stamps both grids together.</p>
 */
public final class EditorTypeMenus {

    /** One {@code GAP} of clear airspace between the first plot's cage edge and the menu anchor. */
    private static final int MENU_GAP = EditorLayout.GAP;
    /** Same Y lift the per-plot labels use — keeps the panel families on the same horizontal plane. */
    private static final int Y_ANCHOR_LIFT = 2;

    private EditorTypeMenus() {}

    /** Every visible type menu for {@code category} at the current world dims. */
    public static List<EditorTypeMenusPacket.Menu> forCategory(EditorCategory category, CarriageDims dims) {
        return switch (category) {
            case CARRIAGES -> carriageMenus(dims);
            case CONTENTS -> contentsMenus(dims);
            case TRACKS -> trackMenus(dims);
            case ARCHITECTURE -> Collections.emptyList();
        };
    }

    private static List<EditorTypeMenusPacket.Menu> carriageMenus(CarriageDims dims) {
        List<EditorTypeMenusPacket.Menu> out = new ArrayList<>();

        // Carriages row (extends along +X). First plot is the first variant
        // in the registry's ordering — same as EditorPlotLabels.carriageLabels.
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        if (!variants.isEmpty()) {
            CarriageVariant first = variants.get(0);
            BlockPos firstOrigin = CarriageEditor.plotOrigin(first, dims);
            if (firstOrigin != null) {
                Vec3i footprint = new Vec3i(dims.length(), dims.height(), dims.width());
                BlockPos anchor = anchorForXRow(firstOrigin, footprint);
                String cat = EditorCategory.CARRIAGES.name();
                CarriageWeights weights = CarriageWeights.current();
                List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(variants.size());
                for (CarriageVariant v : variants) {
                    rows.add(new EditorTypeMenusPacket.Variant(
                        v.id(), weights.weightFor(v.id()), cat, v.id(), v.id()));
                }
                out.add(new EditorTypeMenusPacket.Menu(anchor, "Carriages", rows));
            }
        }

        // Parts kind rows (CARRIAGES view stamps these alongside the carriage row).
        addPartMenu(out, CarriagePartKind.FLOOR, "Floor", dims);
        addPartMenu(out, CarriagePartKind.WALLS, "Walls", dims);
        addPartMenu(out, CarriagePartKind.ROOF, "Roof", dims);
        addPartMenu(out, CarriagePartKind.DOORS, "Doors", dims);

        return out;
    }

    private static void addPartMenu(
        List<EditorTypeMenusPacket.Menu> out, CarriagePartKind kind, String typeName, CarriageDims dims
    ) {
        List<String> names = CarriagePartRegistry.registeredNames(kind);
        if (names.isEmpty()) return;
        BlockPos firstOrigin = CarriagePartEditor.plotOrigin(kind, names.get(0), dims);
        if (firstOrigin == null) return;
        Vec3i footprint = kind.dims(dims);
        BlockPos anchor = anchorForXRow(firstOrigin, footprint);
        // Parts have no weight pool — every variant row gets NO_WEIGHT so the
        // renderer omits the weight cell and lets the name fill the row.
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(names.size());
        for (String name : names) {
            rows.add(new EditorTypeMenusPacket.Variant(
                name, EditorPlotLabelsPacket.NO_WEIGHT, "PARTS", kind.id(), name));
        }
        out.add(new EditorTypeMenusPacket.Menu(anchor, typeName, rows));
    }

    private static List<EditorTypeMenusPacket.Menu> contentsMenus(CarriageDims dims) {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        if (all.isEmpty()) return Collections.emptyList();
        CarriageContents first = all.get(0);
        BlockPos firstOrigin = CarriageContentsEditor.plotOrigin(first, dims);
        if (firstOrigin == null) return Collections.emptyList();
        Vec3i footprint = new Vec3i(dims.length(), dims.height(), dims.width());
        BlockPos anchor = anchorForXRow(firstOrigin, footprint);
        String cat = EditorCategory.CONTENTS.name();
        CarriageContentsWeights weights = CarriageContentsWeights.current();
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(all.size());
        for (CarriageContents c : all) {
            rows.add(new EditorTypeMenusPacket.Variant(
                c.id(), weights.weightFor(c.id()), cat, c.id(), c.id()));
        }
        return List.of(new EditorTypeMenusPacket.Menu(anchor, "Contents", rows));
    }

    private static List<EditorTypeMenusPacket.Menu> trackMenus(CarriageDims dims) {
        List<EditorTypeMenusPacket.Menu> out = new ArrayList<>();
        addTrackKindMenu(out, TrackKind.TILE, "Track", dims);
        for (PillarSection s : PillarSection.values()) {
            addTrackKindMenu(out, PillarTemplateStore.pillarKind(s),
                "Pillar " + capitalise(s.id()), dims);
        }
        for (PillarAdjunct a : PillarAdjunct.values()) {
            addTrackKindMenu(out, PillarTemplateStore.adjunctKind(a),
                capitalise(a.id()), dims);
        }
        for (TunnelVariant v : TunnelVariant.values()) {
            addTrackKindMenu(out, TunnelTemplateStore.tunnelKind(v),
                "Tunnel " + capitalise(v.name().toLowerCase(Locale.ROOT)), dims);
        }
        return out;
    }

    private static void addTrackKindMenu(
        List<EditorTypeMenusPacket.Menu> out, TrackKind kind, String typeName, CarriageDims dims
    ) {
        List<String> names = TrackVariantRegistry.namesFor(kind);
        if (names.isEmpty()) return;
        BlockPos firstOrigin = TrackSidePlots.plotOrigin(kind, names.get(0), dims);
        Vec3i footprint = TrackSidePlots.footprint(kind, dims);
        BlockPos anchor = anchorForZRow(firstOrigin, footprint);
        String cat = EditorCategory.TRACKS.name();
        String modelId = kind.id();
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(names.size());
        for (String name : names) {
            rows.add(new EditorTypeMenusPacket.Variant(
                name, TrackVariantWeights.weightFor(kind, name), cat, modelId, name));
        }
        out.add(new EditorTypeMenusPacket.Menu(anchor, typeName, rows));
    }

    /**
     * Anchor for an X-extending row: one {@code GAP} past the first plot's
     * {@code -X} cage edge, lifted {@link #Y_ANCHOR_LIFT} blocks above the
     * cage top, centred over the footprint Z.
     */
    private static BlockPos anchorForXRow(BlockPos firstOrigin, Vec3i footprint) {
        return new BlockPos(
            firstOrigin.getX() - MENU_GAP,
            firstOrigin.getY() + footprint.getY() + Y_ANCHOR_LIFT,
            firstOrigin.getZ() + footprint.getZ() / 2
        );
    }

    /**
     * Anchor for a Z-extending row: one {@code GAP} past the first plot's
     * {@code -Z} cage edge, lifted {@link #Y_ANCHOR_LIFT} blocks above the
     * cage top, centred over the footprint X.
     */
    private static BlockPos anchorForZRow(BlockPos firstOrigin, Vec3i footprint) {
        return new BlockPos(
            firstOrigin.getX() + footprint.getX() / 2,
            firstOrigin.getY() + footprint.getY() + Y_ANCHOR_LIFT,
            firstOrigin.getZ() - MENU_GAP
        );
    }

    private static String capitalise(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
