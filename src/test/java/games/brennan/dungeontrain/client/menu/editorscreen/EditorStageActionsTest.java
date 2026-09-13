package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the stage overview's buttons and rows send. */
final class EditorStageActionsTest {

    private static EditorRosterPacket.StageEntry desert() {
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant(
            "Desert", EditorPlotLabelsPacket.NO_WEIGHT, 10, 40, TrainPhase.OVERWORLD.bit() | TrainPhase.VOID.bit(),
            "stages", "desert", "desert", true, false);
        return new EditorRosterPacket.StageEntry(v, List.of(), 0, List.of("floor:sand"));
    }

    @Test
    @DisplayName("Refresh · Apply · Rename · Duplicate · Delete · Prev · Next, each sending its stage command")
    void icons() {
        List<Integer> steps = new ArrayList<>();
        List<String> refreshed = new ArrayList<>();
        List<EditorScreenActions.Icon> icons = EditorStageActions.icons(desert(),
            VariantKey.of(PlotCategory.CARRIAGES, "standard", "standard"), true, () -> refreshed.add("x"), steps::add);
        assertEquals(7, icons.size());
        assertEquals(List.of("refresh", "apply", "rename", "duplicate", "delete", "prev", "next"),
            icons.stream().map(EditorScreenActions.Icon::id).toList());
        ((CommandMenuEntry.ClientAction) icons.get(0).entry()).action().run();
        assertEquals(1, refreshed.size());
        assertEquals("dungeontrain editor stage apply carriage standard desert",
            ((CommandMenuEntry.Stay) icons.get(1).entry()).command());
        assertEquals("standard", icons.get(1).detail());
        CommandMenuEntry.TypeArg rename = assertInstanceOf(CommandMenuEntry.TypeArg.class, icons.get(2).entry());
        assertEquals("dungeontrain editor stage rename desert", rename.commandPrefix());
        assertEquals("Desert", rename.initialBuffer());
        assertEquals("dungeontrain editor stage duplicate desert", ((CommandMenuEntry.Stay) icons.get(3).entry()).command());
        assertInstanceOf(CommandMenuEntry.DrillIn.class, icons.get(4).entry(), "delete asks first");
        ((CommandMenuEntry.ClientAction) icons.get(5).entry()).action().run();
        ((CommandMenuEntry.ClientAction) icons.get(6).entry()).action().run();
        assertEquals(List.of(-1, 1), steps);
    }

    @Test
    @DisplayName("Apply is off with nothing gateable selected; Prev/Next are off with one template to show")
    void disabled() {
        List<EditorScreenActions.Icon> icons = EditorStageActions.icons(desert(), null, false, () -> { }, d -> { });
        assertFalse(icons.get(1).enabled());
        assertEquals(EditorScreenLang.STAGES_APPLY_NONE, icons.get(1).disabledKey());
        assertFalse(icons.get(5).enabled());
        assertFalse(icons.get(6).enabled());
        assertNull(EditorStageActions.applyCommand(VariantKey.of(PlotCategory.PARTS, "floor", "sand"), "desert"));
        assertEquals("dungeontrain editor stage apply contents-group armor armor2 desert",
            EditorStageActions.applyCommand(new VariantKey(PlotCategory.CONTENTS, "armor2", "armor2", "armor"), "desert"));
    }

    @Test
    @DisplayName("Min Lv and Max Lv step and type; the bands row lights the set dimensions and each toggles the other way")
    void settingRows() {
        List<CommandMenuEntry> rows = EditorStageActions.settingRows(desert());
        assertEquals(3, rows.size());
        CommandMenuEntry.Triple min = assertInstanceOf(CommandMenuEntry.Triple.class, rows.get(0));
        assertEquals("dungeontrain editor stage minlevel desert dec", ((CommandMenuEntry.Stay) min.leftEntry()).command());
        assertEquals("dungeontrain editor stage minlevel desert", ((CommandMenuEntry.TypeArg) min.middleEntry()).commandPrefix());
        assertEquals("dungeontrain editor stage maxlevel desert inc",
            ((CommandMenuEntry.Stay) ((CommandMenuEntry.Triple) rows.get(1)).rightEntry()).command());
        CommandMenuEntry[] bands = MenuRowPainter.cellsOf(rows.get(2));
        assertEquals(1 + TrainPhase.values().length, bands.length);
        CommandMenuEntry.Stay overworld = assertInstanceOf(CommandMenuEntry.Stay.class, bands[1]);
        assertTrue(overworld.highlighted());
        assertEquals("dungeontrain editor stage phase desert overworld off", overworld.command());
        CommandMenuEntry.Stay nether = assertInstanceOf(CommandMenuEntry.Stay.class, bands[2]);
        assertFalse(nether.highlighted());
        assertEquals("dungeontrain editor stage phase desert nether on", nether.command());
    }
}
