package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** The six lines under the preview: what the selected template is and how it spawns. */
public final class TemplateDataSheet {

    static final int LABEL = 0xFFFFEEBB;
    static final int VALUE = 0xFFFFFFFF;
    static final int LINE_H = 10;

    /** One label / value pair. */
    public record Line(String label, String value) {}

    private TemplateDataSheet() {}

    /** The lines for a selection; empty when nothing is selected. */
    public static List<Line> lines(EditorRosterIndex.Tile tile, String pathLabel, TemplateSummary summary,
                                   EditorRosterIndex.Provenance provenance) {
        List<Line> out = new ArrayList<>(6);
        if (tile == null) return out;
        EditorTypeMenusPacket.Variant v = tile.variant();
        String pending = EditorScreenLang.text(EditorScreenLang.SHEET_PENDING);
        out.add(new Line(EditorScreenLang.text(EditorScreenLang.SHEET_PATH), pathLabel));
        if (summary == null || summary.isEmpty()) {
            out.add(new Line(EditorScreenLang.text(EditorScreenLang.SHEET_SIZE), pending));
            out.add(new Line(EditorScreenLang.text(EditorScreenLang.SHEET_BLOCKS), pending));
        } else {
            var s = summary.declaredSize();
            out.add(new Line(EditorScreenLang.text(EditorScreenLang.SHEET_SIZE),
                s.getX() + " × " + s.getY() + " × " + s.getZ()));
            StringBuilder blocks = new StringBuilder(Integer.toString(summary.blocks()));
            if (summary.entities() > 0) {
                blocks.append(" · ").append(EditorScreenLang.text(EditorScreenLang.SHEET_ENTITIES, summary.entities()));
            }
            if (summary.containers() > 0) {
                blocks.append(" · ").append(EditorScreenLang.text(EditorScreenLang.SHEET_CONTAINERS, summary.containers()));
            }
            out.add(new Line(EditorScreenLang.text(EditorScreenLang.SHEET_BLOCKS), blocks.toString()));
        }
        String weight = v.weight() == EditorPlotLabelsPacket.NO_WEIGHT ? pending : Integer.toString(v.weight());
        if (tile.isGroup()) {
            weight += " · " + EditorScreenLang.text(EditorScreenLang.SHEET_SHARE, v.subVariants().size());
        }
        out.add(new Line(EditorScreenLang.text(EditorScreenLang.SHEET_WEIGHT), weight));
        out.add(new Line(EditorScreenLang.text(EditorScreenLang.SHEET_SPAWNS), spawns(v)));
        out.add(new Line(EditorScreenLang.text(EditorScreenLang.SHEET_SOURCE), sourceLabel(provenance)));
        return out;
    }

    static String spawns(EditorTypeMenusPacket.Variant v) {
        if (v.phaseMask() == EditorTypeMenusPacket.Variant.NO_GATE) {
            return EditorScreenLang.text(EditorScreenLang.SHEET_PENDING);
        }
        if (v.isStageLinked()) {
            return EditorScreenLang.text(EditorScreenLang.SHEET_STAGE) + " " + v.primaryStageId();
        }
        String max = v.maxLevel() < 0 ? EditorScreenLang.text(EditorScreenLang.SHEET_LEVELS_ALL)
            : Integer.toString(v.maxLevel());
        return "Lv " + v.minLevel() + " – " + max + " · " + phases(v.phaseMask());
    }

    static String phases(int mask) {
        StringBuilder sb = new StringBuilder();
        for (games.brennan.dungeontrain.worldgen.TrainPhase p : games.brennan.dungeontrain.worldgen.TrainPhase.values()) {
            if ((mask & p.bit()) == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.name().charAt(0)));
        }
        return sb.toString();
    }

    static String sourceLabel(EditorRosterIndex.Provenance p) {
        return switch (p) {
            case BUILTIN -> EditorScreenLang.text(EditorScreenLang.SOURCE_BUILTIN);
            case USER -> EditorScreenLang.text(EditorScreenLang.SOURCE_MINE);
            case IMPORTED -> EditorScreenLang.text(EditorScreenLang.SOURCE_COMMUNITY);
        };
    }

    public static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, List<Line> lines) {
        int labelW = 0;
        for (Line l : lines) labelW = Math.max(labelW, font.width(l.label()));
        int y = r.y() + 1;
        for (Line l : lines) {
            if (y + font.lineHeight > r.bottom()) break;
            g.drawString(font, l.label(), r.x() + 2, y, LABEL, false);
            int vx = r.x() + 2 + labelW + 6;
            String value = font.plainSubstrByWidth(l.value(), Math.max(0, r.right() - vx - 2));
            g.drawString(font, value, vx, y, VALUE, false);
            y += LINE_H;
        }
    }
}
