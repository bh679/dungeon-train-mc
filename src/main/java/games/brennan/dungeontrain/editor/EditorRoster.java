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
        return all(null);
    }

    /**
     * The roster, with each template's relay row attached where {@code overworld}'s world data
     * records one — what lets the previewer page through the versions the relay kept of it. Null
     * attaches none.
     */
    public static List<EditorRosterPacket.Group> all(net.minecraft.server.level.ServerLevel overworld) {
        RELAY_ROWS.set(overworld == null ? null
            : games.brennan.dungeontrain.world.DungeonTrainWorldData.get(overworld).builderRelayBuilds());
        try {
            List<EditorRosterPacket.Group> out = new ArrayList<>();
            addCarriages(out);
            addParts(out);
            addContents(out);
            addTracks(out);
            addPortals(out);
            return out;
        } finally {
            RELAY_ROWS.set(null);
        }
    }

    /** The world's relay rows for the roster being built, on this thread; null when not attaching. */
    private static final ThreadLocal<games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds> RELAY_ROWS =
        new ThreadLocal<>();

    /**
     * The relay row a template lives in, or 0.
     *
     * <p>Keyed the way an upload keys it — relay kind, sub-kind, id — from what a roster row knows:
     * a carriage or contents template is named by its model id, a part or track-side template by
     * its name under its kind. A portal room has been filed under both its own kind and as a track
     * kind over time, so both are asked.</p>
     */
    private static int relayIdFor(String categoryId, String groupModelId, EditorTypeMenusPacket.Variant v) {
        games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds rows = RELAY_ROWS.get();
        if (rows == null) return 0;
        java.util.List<String> keys = new ArrayList<>(2);
        String K = null;
        if (EditorCategory.CARRIAGES.id().equals(categoryId)) {
            keys.add(games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds.keyOf(
                games.brennan.dungeontrain.builder.relay.BuilderRelayKinds.CARRIAGE, "", v.modelId()));
        } else if (PlotCategory.PARTS.id().equals(categoryId)) {
            keys.add(games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds.keyOf(
                games.brennan.dungeontrain.builder.relay.BuilderRelayKinds.PART, groupModelId, v.modelName()));
        } else if (EditorCategory.CONTENTS.id().equals(categoryId)) {
            keys.add(games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds.keyOf(
                games.brennan.dungeontrain.builder.relay.BuilderRelayKinds.CONTENTS, "", v.modelId()));
        } else if (EditorCategory.TRACKS.id().equals(categoryId)) {
            keys.add(games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds.keyOf(
                games.brennan.dungeontrain.builder.relay.BuilderRelayKinds.TRACK, groupModelId, v.modelName()));
        } else if (EditorCategory.PORTALS.id().equals(categoryId)) {
            keys.add(games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds.keyOf(
                games.brennan.dungeontrain.builder.relay.BuilderRelayKinds.PORTAL_ROOM, "", v.modelName()));
            keys.add(games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds.keyOf(
                games.brennan.dungeontrain.builder.relay.BuilderRelayKinds.TRACK, TrackKind.PORTAL_ROOM.id(), v.modelName()));
        }
        for (String key : keys) {
            games.brennan.dungeontrain.builder.relay.BuilderRelayBuilds.Entry row = rows.get(key);
            if (row != null && row.relayId() > 0) return row.relayId();
        }
        return 0;
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
            entries.add(new EditorRosterPacket.Entry(v, self, relayIdFor(categoryId, modelId, v)));
        }
        return new EditorRosterPacket.Group(categoryId, typeName, modelId, entries);
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
