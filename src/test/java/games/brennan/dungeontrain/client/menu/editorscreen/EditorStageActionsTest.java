package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
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
    @DisplayName("the sheet's Spawns line is the template sheet's gate: bounds that step and type, letters that toggle")
    void sheetLines() {
        List<TemplateDataSheet.Line> lines = EditorStageActions.sheetLines(desert(), 4);
        assertEquals(4, lines.size());
        List<TemplateDataSheet.Cell> gate = lines.get(0).cells();
        // Lv · min · — · max · · · six letters
        assertEquals(5 + TrainPhase.values().length, gate.size());
        TemplateDataSheet.Action.Step min = assertInstanceOf(TemplateDataSheet.Action.Step.class, gate.get(1).action());
        assertEquals("10", gate.get(1).text());
        assertEquals("dungeontrain editor stage minlevel desert", min.prefix());
        assertEquals("dungeontrain editor stage minlevel desert dec", min.dec());
        assertEquals("dungeontrain editor stage minlevel desert inc", min.inc());
        TemplateDataSheet.Action.Step max = assertInstanceOf(TemplateDataSheet.Action.Step.class, gate.get(3).action());
        assertEquals("dungeontrain editor stage maxlevel desert inc", max.inc());
        TemplateDataSheet.Cell overworld = gate.get(5);
        assertTrue(overworld.on());
        assertEquals("dungeontrain editor stage phase desert overworld off",
            assertInstanceOf(TemplateDataSheet.Action.Run.class, overworld.action()).command());
        TemplateDataSheet.Cell nether = gate.get(6);
        assertFalse(nether.on());
        assertEquals("dungeontrain editor stage phase desert nether on",
            assertInstanceOf(TemplateDataSheet.Action.Run.class, nether.action()).command());
        assertEquals("1", lines.get(1).cells().get(0).text(), "one linked part");
        assertEquals("4", lines.get(2).cells().get(0).text(), "templates as counted by the caller");
    }
}
