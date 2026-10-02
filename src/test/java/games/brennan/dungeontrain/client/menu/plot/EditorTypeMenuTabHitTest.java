package games.brennan.dungeontrain.client.menu.plot;

import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer.CellKind;
import games.brennan.dungeontrain.client.menu.plot.EditorTypeMenuRenderer.Hovered;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The nav panel's type-tab strip: every tab, expanded or collapsed, can be pointed at. */
@ExtendWith(MenuTestLanguage.class)
class EditorTypeMenuTabHitTest {

    private static final class FixedFont extends net.minecraft.client.gui.Font {
        FixedFont() {
            super(loc -> null, false);
        }

        @Override
        public int width(String text) {
            return text.length() * 6;
        }
    }

    private static EditorTypeMenusPacket.Menu contentsMenu(String expanded) {
        List<EditorTypeMenusPacket.CategoryButton> bar = List.of(
            new EditorTypeMenusPacket.CategoryButton("whole", "Whole"),
            new EditorTypeMenusPacket.CategoryButton("carriages", "Carriages"),
            new EditorTypeMenusPacket.CategoryButton("contents", "Contents"),
            new EditorTypeMenusPacket.CategoryButton("tracks", "Tracks"));
        List<EditorTypeMenusPacket.TypeTab> strip = List.of(
            new EditorTypeMenusPacket.TypeTab("Room", "CONTENTS", "default", "default"),
            new EditorTypeMenusPacket.TypeTab("Half", "CONTENTS", "portal", "portal"),
            new EditorTypeMenusPacket.TypeTab("Full", "CONTENTS", "default_full", "default_full"));
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>();
        for (String id : List.of("default", "armor", "books", "cows", "craft")) {
            rows.add(new EditorTypeMenusPacket.Variant(id, 1, "CONTENTS", id, id, false, false));
        }
        return new EditorTypeMenusPacket.Menu(new BlockPos(-5, 239, 3), expanded, rows, false,
            "contents", bar, strip);
    }

    /** A companion list (the panel beside the plot) of two rows in {@code category}, each with a gate. */
    private static EditorTypeMenusPacket.Menu companion(String category) {
        List<EditorTypeMenusPacket.Variant> rows = new ArrayList<>();
        for (String id : List.of("cargocontents", "things")) {
            rows.add(new EditorTypeMenusPacket.Variant(id, 1, 0, -1, 0b111, category, id, id, false, false));
        }
        return new EditorTypeMenusPacket.Menu(new BlockPos(0, 239, 0), "Group", rows, true);
    }

    private static TreeSet<CellKind> cellsAlongFirstRow(EditorTypeMenusPacket.Menu menu, FixedFont font) {
        double halfW = EditorTypeMenuRenderer.halfWidth(menu, font);
        double rowY = EditorTypeMenuRenderer.halfHeight(menu, font) - 1.5 * EditorTypeMenuRenderer.ROW_H;
        TreeSet<CellKind> cells = new TreeSet<>();
        for (double x = -halfW + 0.01; x < halfW; x += 0.01) {
            cells.add(EditorTypeMenuRenderer.hitFor(0, menu, font, x, rowY).cell());
        }
        return cells;
    }

    @Test
    @DisplayName("the Contents list beside a plot offers Stage, level and dimension cells on every row")
    void contentsCompanionIsGated() {
        TreeSet<CellKind> cells = cellsAlongFirstRow(companion("CONTENTS"), new FixedFont());
        assertEquals(true, cells.containsAll(List.of(CellKind.NAME, CellKind.WEIGHT, CellKind.STAGE,
            CellKind.MIN_LEVEL, CellKind.MAX_LEVEL, CellKind.PHASE)), "cells found: " + cells);
    }

    @Test
    @DisplayName("a Carriages list beside a plot stays name and weight only")
    void otherCompanionsStayWeightOnly() {
        TreeSet<CellKind> cells = cellsAlongFirstRow(companion("CARRIAGES"), new FixedFont());
        assertEquals(new TreeSet<>(List.of(CellKind.NAME, CellKind.WEIGHT)), cells);
    }

    @Test
    @DisplayName("all three Contents size tabs are hit somewhere along the tab row")
    void everyTabIsReachable() {
        FixedFont font = new FixedFont();
        for (String expanded : List.of("Room", "Half", "Full")) {
            EditorTypeMenusPacket.Menu menu = contentsMenu(expanded);
            double halfW = EditorTypeMenuRenderer.halfWidth(menu, font);
            double tabRowY = EditorTypeMenuRenderer.halfHeight(menu, font) - 1.5 * EditorTypeMenuRenderer.ROW_H;
            TreeSet<Integer> tabs = new TreeSet<>();
            for (double x = -halfW + 0.01; x < halfW; x += 0.01) {
                Hovered hit = EditorTypeMenuRenderer.hitFor(0, menu, font, x, tabRowY);
                if (hit.cell() == CellKind.TYPE_TAB) tabs.add(hit.slotIdx());
            }
            assertEquals(new TreeSet<>(List.of(0, 1, 2)), tabs, "tabs reachable with '" + expanded + "' expanded");
        }
    }
}
