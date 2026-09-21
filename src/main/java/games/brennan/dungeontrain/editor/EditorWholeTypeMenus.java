package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.train.WholeWeights;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * The WHOLE category's world-space type menus and roster rows — one {@code Room} menu at the room
 * row's anchor and one {@code Group} menu at the group row's, each carrying the shared category bar
 * and a two-tab strip.
 *
 * <p>Kept beside {@link EditorTypeMenus} rather than inside it: that class already carries every
 * other category and the row builders the roster shares, and it was at the size where another
 * category would be the one too many.</p>
 *
 * <p>Whole rows carry a weight and no gate ({@link PlotCategory#hasGate()} is false for both kinds),
 * so the renderer draws name + weight cells only.</p>
 */
public final class EditorWholeTypeMenus {

    /** Menu / tab headers — also what {@code MenuLang.typeName} slugs to {@code type_name.room|group}. */
    public static final String ROOM_TYPE_NAME = "Room";
    public static final String GROUP_TYPE_NAME = "Group";

    private EditorWholeTypeMenus() {}

    /** Every visible menu for the WHOLE category at {@code dims}. */
    public static List<EditorTypeMenusPacket.Menu> menus(CarriageDims dims) {
        List<EditorTypeMenusPacket.Menu> out = new ArrayList<>(2);
        List<EditorTypeMenusPacket.CategoryButton> bar = EditorTypeMenus.buildCategoryBar();
        List<EditorTypeMenusPacket.TypeTab> strip = typeStrip();
        String activeId = EditorCategory.WHOLE.id();

        List<WholeCarriage> rooms = WholeCarriageRegistry.all();
        if (!rooms.isEmpty()) {
            BlockPos origin = WholeCarriageEditor.rowOrigin(WholeKind.ROOM, dims);
            Vec3i footprint = WholeCarriageEditor.plotSize(WholeKind.ROOM, dims);
            out.add(new EditorTypeMenusPacket.Menu(EditorTypeMenus.anchorForXRow(origin, footprint),
                ROOM_TYPE_NAME, roomRows(rooms), false, activeId, bar, strip));
        }
        List<CarriageGroup> groups = CarriageGroupRegistry.all();
        if (!groups.isEmpty()) {
            BlockPos origin = WholeCarriageEditor.rowOrigin(WholeKind.GROUP, dims);
            Vec3i footprint = WholeCarriageEditor.plotSize(WholeKind.GROUP, dims);
            out.add(new EditorTypeMenusPacket.Menu(EditorTypeMenus.anchorForXRow(origin, footprint),
                GROUP_TYPE_NAME, groupRows(groups), false, activeId, bar, strip));
        }
        return out;
    }

    /** The two tabs, in row order; a kind with nothing registered is skipped (a dead click otherwise). */
    static List<EditorTypeMenusPacket.TypeTab> typeStrip() {
        List<EditorTypeMenusPacket.TypeTab> strip = new ArrayList<>(2);
        List<String> rooms = WholeCarriageRegistry.ids();
        if (!rooms.isEmpty()) {
            strip.add(new EditorTypeMenusPacket.TypeTab(ROOM_TYPE_NAME, PlotCategory.WHOLE.name(),
                rooms.get(0), rooms.get(0)));
        }
        List<String> groups = CarriageGroupRegistry.ids();
        if (!groups.isEmpty()) {
            strip.add(new EditorTypeMenusPacket.TypeTab(GROUP_TYPE_NAME, PlotCategory.WHOLE_GROUP.name(),
                groups.get(0), groups.get(0)));
        }
        return strip;
    }

    /** One packet row per room, in registry order. Weight only — no gate. */
    public static List<EditorTypeMenusPacket.Variant> roomRows(List<WholeCarriage> rooms) {
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(rooms.size());
        for (WholeCarriage r : rooms) {
            rows.add(row(WholeKind.ROOM, PlotCategory.WHOLE.name(), r.id(),
                EditorPlotLabels.provenanceOf(WholeCarriageTemplateStore.fileForId(r.id()))));
        }
        return rows;
    }

    /** One packet row per group, in registry order. Weight only — no gate. */
    public static List<EditorTypeMenusPacket.Variant> groupRows(List<CarriageGroup> groups) {
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>(groups.size());
        for (CarriageGroup g : groups) {
            rows.add(row(WholeKind.GROUP, PlotCategory.WHOLE_GROUP.name(), g.id(),
                EditorPlotLabels.provenanceOf(CarriageGroupTemplateStore.fileForId(g.id()))));
        }
        return rows;
    }

    private static EditorTypeMenusPacket.Variant row(WholeKind kind, String category, String id,
                                                     EditorPlotLabels.Provenance p) {
        TemplateGate g = WholeWeights.gateFor(kind, id);
        String stageId = WholeWeights.stageIdFor(kind, id);
        games.brennan.dungeontrain.template.BuilderCredit credit = TemplateBuilderLookup.whole(kind, id);
        return new EditorTypeMenusPacket.Variant(
            id, WholeWeights.weightFor(kind, id),
            g.minLevel(), g.maxLevel(), TrainPhase.toMask(g.phases()),
            category, id, id, p.isUser(), p.isImported(), List.of(),
            stageId == null || stageId.isEmpty() ? List.of() : List.of(stageId))
            .withDisplayName(WholeWeights.nameFor(kind, id))
            .withBuilder(credit == null ? "" : credit.uuid(), credit == null ? "" : credit.name());
    }
}
