package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every template in every editor category at once — what the inventory-style editor screen
 * browses.
 *
 * <p>The world-space type menus ({@link EditorTypeMenus}) describe one stamped category and hang
 * their rows on world anchors. The inventory screen shows all four categories with no anchors, so
 * it asks for this instead. Both are built from the same per-row helpers in
 * {@link EditorTypeMenus}, which is what keeps a template's weight, gate, stage links, provenance
 * and sub-variants identical between the two surfaces.</p>
 *
 * <p>Server side: reads the registries and the config-dir sidecars, so it runs where the stamping
 * commands run and answers the same on a dedicated server as in single-player.</p>
 */
public final class EditorRoster {

    private EditorRoster() {}

    /** Every group of every category, in the order the screen's tabs and type strips show them. */
    public static List<EditorRosterPacket.Group> all() {
        List<EditorRosterPacket.Group> out = new ArrayList<>();
        addCarriages(out);
        addParts(out);
        addContents(out);
        addTracks(out);
        addPortals(out);
        return out;
    }

    private static void addCarriages(List<EditorRosterPacket.Group> out) {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        if (variants.isEmpty()) return;
        out.add(group(EditorCategory.CARRIAGES.id(), "Carriages", "",
            EditorTypeMenus.carriageRows(variants), null));
    }

    private static void addParts(List<EditorRosterPacket.Group> out) {
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            List<String> names = CarriagePartRegistry.registeredNames(kind);
            if (names.isEmpty()) continue;
            out.add(group(PlotCategory.PARTS.id(), partTypeName(kind), kind.id(),
                EditorTypeMenus.partRows(kind, names), null));
        }
    }

    private static void addContents(List<EditorRosterPacket.Group> out) {
        List<CarriageContents> topLevel = EditorTypeMenus.topLevelContents();
        if (topLevel.isEmpty()) return;
        out.add(group(EditorCategory.CONTENTS.id(), "Contents", "",
            EditorTypeMenus.contentsRows(topLevel), EditorRoster::contentsSelfWeight));
    }

    private static void addTracks(List<EditorRosterPacket.Group> out) {
        for (Map.Entry<TrackKind, String> kind : EditorTypeMenus.trackKindsInOrder()) {
            List<String> names = TrackVariantGroupStore.topLevelNames(kind.getKey());
            if (names.isEmpty()) continue;
            out.add(group(EditorCategory.TRACKS.id(), kind.getValue(), kind.getKey().id(),
                EditorTypeMenus.trackKindRows(kind.getKey(), names, EditorCategory.TRACKS),
                v -> trackSelfWeight(kind.getKey(), v)));
        }
    }

    private static void addPortals(List<EditorRosterPacket.Group> out) {
        List<String> names = TrackVariantGroupStore.topLevelNames(TrackKind.PORTAL_ROOM);
        if (names.isEmpty()) return;
        out.add(group(EditorCategory.PORTALS.id(), "Dimensional Carriage", TrackKind.PORTAL_ROOM.id(),
            EditorTypeMenus.trackKindRows(TrackKind.PORTAL_ROOM, names, EditorCategory.PORTALS),
            v -> trackSelfWeight(TrackKind.PORTAL_ROOM, v)));
    }

    private static EditorRosterPacket.Group group(
        String categoryId, String typeName, String modelId,
        List<EditorTypeMenusPacket.Variant> rows, SelfWeight selfWeight
    ) {
        List<EditorRosterPacket.Entry> entries = new ArrayList<>(rows.size());
        for (EditorTypeMenusPacket.Variant v : rows) {
            int self = selfWeight == null ? EditorPlotLabelsPacket.NO_WEIGHT : selfWeight.of(v);
            entries.add(new EditorRosterPacket.Entry(v, self, sourceOf(v)));
        }
        return new EditorRosterPacket.Group(categoryId, typeName, modelId, entries);
    }

    /**
     * Which imported package a row's template came from, or {@code ""}.
     *
     * <p>Only asked for imported rows: the lookup walks the package directories, and every bundled
     * or authored template would walk them all to answer "none".</p>
     */
    private static String sourceOf(EditorTypeMenusPacket.Variant v) {
        if (!v.isImported()) return "";
        PlotCategory category = v.plotCategory();
        if (category == null) return "";
        return switch (category) {
            case CARRIAGES -> EditorPlotLabels.sourceOf(CarriageTemplateStore.fileForId(v.modelId()));
            case CONTENTS -> EditorPlotLabels.sourceOf(CarriageContentsStore.fileForId(v.modelId()));
            case PARTS -> {
                CarriagePartKind kind = CarriagePartKind.fromId(v.modelId());
                yield kind == null ? "" : EditorPlotLabels.sourceOf(
                    CarriagePartTemplateStore.fileFor(kind, v.modelName()));
            }
            case TRACKS, PORTALS -> {
                TrackKind kind = TrackKind.fromId(v.modelId());
                yield kind == null ? "" : EditorPlotLabels.sourceOf(
                    games.brennan.dungeontrain.track.variant.TrackVariantStore.fileFor(kind, v.modelName()));
            }
            case ARCHITECTURE -> "";
        };
    }

    /** How a group parent's own template is weighted against its members — see the sidecars. */
    @FunctionalInterface
    private interface SelfWeight {
        int of(EditorTypeMenusPacket.Variant parent);
    }

    /** The parent's editable self weight, or NO_WEIGHT when it has no members to compete with. */
    static int contentsSelfWeight(EditorTypeMenusPacket.Variant parent) {
        Optional<CarriageContentsGroup> group = CarriageContentsGroupStore.get(parent.modelId());
        if (group.isEmpty() || group.get().isEmpty()) return EditorPlotLabelsPacket.NO_WEIGHT;
        return group.get().selfWeight();
    }

    static int trackSelfWeight(TrackKind kind, EditorTypeMenusPacket.Variant parent) {
        Optional<TrackVariantGroup> group = TrackVariantGroupStore.get(kind, parent.modelName());
        if (group.isEmpty() || group.get().isEmpty()) return EditorPlotLabelsPacket.NO_WEIGHT;
        return group.get().selfWeight();
    }

    /** The type-strip label for a part kind, matching the world-space strip. */
    static String partTypeName(CarriagePartKind kind) {
        return switch (kind) {
            case FLOOR -> "Floor";
            case WALLS -> "Walls";
            case ROOF -> "Roof";
            case DOORS -> "Doors";
        };
    }
}
