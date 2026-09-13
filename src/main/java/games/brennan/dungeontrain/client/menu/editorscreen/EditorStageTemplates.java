package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.train.CarriagePartKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything linked to one Stage, as rows for the stage preview's template page: the carriage
 * parts the roster's stage entry names, then every roster template (and group member) whose
 * stage links include it. Pure, so the list can be pinned without a screen.
 *
 * <p>Each row carries the {@link VariantKey} the click selects — the same key the browser uses,
 * so choosing a row lands on that template's tile.</p>
 */
public final class EditorStageTemplates {

    /** One linked template: its label ({@code Type · name}) and the key selecting it. */
    public record Row(String label, VariantKey key) {}

    private EditorStageTemplates() {}

    public static List<Row> rows(EditorRosterPacket.StageEntry stage, EditorRosterIndex index) {
        List<Row> out = new ArrayList<>();
        if (stage == null) return out;
        for (String part : stage.parts()) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            String kindId = part.substring(0, colon);
            String name = part.substring(colon + 1);
            CarriagePartKind kind = CarriagePartKind.fromId(kindId);
            String type = kind == null ? kindId : partTypeName(kind);
            out.add(new Row(type + " · " + name, VariantKey.of(PlotCategory.PARTS, kindId, name)));
        }
        if (index == null) return out;
        String id = stage.id();
        for (EditorRosterPacket.Group g : index.groups()) {
            for (EditorRosterPacket.Entry e : g.entries()) {
                EditorTypeMenusPacket.Variant v = e.variant();
                if (linksTo(v, id)) {
                    out.add(new Row(g.typeName() + " · " + v.displayName(), VariantKey.of(v, "")));
                }
                for (EditorTypeMenusPacket.Variant sv : v.subVariants()) {
                    if (linksTo(sv, id)) {
                        out.add(new Row(g.typeName() + " · " + v.displayName() + " / " + sv.displayName(),
                            VariantKey.of(sv, VariantKey.of(v, "").displayName())));
                    }
                }
            }
        }
        return out;
    }

    private static boolean linksTo(EditorTypeMenusPacket.Variant v, String stageId) {
        for (String s : v.stageIds()) {
            if (s.equalsIgnoreCase(stageId)) return true;
        }
        return false;
    }

    private static String partTypeName(CarriagePartKind kind) {
        return switch (kind) {
            case FLOOR -> "Floor";
            case WALLS -> "Walls";
            case ROOF -> "Roof";
            case DOORS -> "Doors";
        };
    }
}
