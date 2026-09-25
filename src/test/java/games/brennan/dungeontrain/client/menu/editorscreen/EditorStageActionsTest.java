package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.worldgen.LapBand;
import org.junit.jupiter.api.extension.ExtendWith;
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
@ExtendWith(MenuTestLanguage.class)
final class EditorStageActionsTest {

    private static EditorRosterPacket.StageEntry desert() {
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant(
            "Desert", EditorPlotLabelsPacket.NO_WEIGHT, 10, 40, LapBand.V_OVERWORLD_1.bit() | LapBand.L_VOID.bit(),
            "stages", "desert", "desert", true, false);
        return new EditorRosterPacket.StageEntry(v, List.of(), 0, List.of("floor:sand"));
    }

    @Test
    @DisplayName("Refresh · Select · Rename · Duplicate · Delete · Prev · Next · Re-bake, each sending its stage command")
    void icons() {
        List<Integer> steps = new ArrayList<>();
        List<String> refreshed = new ArrayList<>();
        List<EditorScreenActions.Icon> icons = EditorStageActions.icons(desert(),
            false, true, () -> refreshed.add("x"), steps::add);
        assertEquals(8, icons.size());
        assertEquals(List.of("refresh", "select", "rename", "duplicate", "delete", "prev", "next", "rebake"),
            icons.stream().map(EditorScreenActions.Icon::id).toList());
        assertEquals("dungeontrain editor stage bake desert", ((CommandMenuEntry.Stay) icons.get(7).entry()).command());
        ((CommandMenuEntry.ClientAction) icons.get(0).entry()).action().run();
        assertEquals(1, refreshed.size());
        assertEquals("dungeontrain editor stage select desert",
            ((CommandMenuEntry.Stay) icons.get(1).entry()).command());
        assertEquals(EditorScreenLang.STAGES_ICON_SELECT, icons.get(1).labelKey());
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
    @DisplayName("Select is always on and reads Deselect once the stage is focused; Prev/Next are off with one template to show")
    void disabled() {
        List<EditorScreenActions.Icon> icons = EditorStageActions.icons(desert(), true, false, () -> { }, d -> { });
        assertTrue(icons.get(1).enabled());
        assertEquals(EditorScreenLang.STAGES_ICON_DESELECT, icons.get(1).labelKey());
        assertEquals("dungeontrain editor stage select desert",
            ((CommandMenuEntry.Stay) icons.get(1).entry()).command(), "the command toggles, so it is the same either way");
        assertFalse(icons.get(5).enabled());
        assertFalse(icons.get(6).enabled());
    }

    @Test
    @DisplayName("the sheet: Built by (a picker in dev mode), Spawns bounds that step and type, a Bands row with every letter a button, then counts")
    void sheetLines() {
        List<TemplateDataSheet.Line> lines = EditorStageActions.sheetLines(desert(), 4, true);
        assertEquals(6, lines.size());
        TemplateDataSheet.Action.PickBuilder pick = assertInstanceOf(TemplateDataSheet.Action.PickBuilder.class,
            lines.get(0).cells().get(0).action());
        assertEquals("dungeontrain editor stage builder desert", pick.prefix());
        assertNull(EditorStageActions.sheetLines(desert(), 4, false).get(0).cells().get(0).action(), "read-only off dev");
        List<TemplateDataSheet.Cell> levels = lines.get(1).cells();
        assertEquals(4, levels.size(), "Lv · min · — · max");
        TemplateDataSheet.Action.Step min = assertInstanceOf(TemplateDataSheet.Action.Step.class, levels.get(1).action());
        assertEquals("10", levels.get(1).text());
        assertEquals("dungeontrain editor stage minlevel desert", min.prefix());
        assertEquals("dungeontrain editor stage minlevel desert dec", min.dec());
        assertEquals("dungeontrain editor stage minlevel desert inc", min.inc());
        TemplateDataSheet.Action.Step max = assertInstanceOf(TemplateDataSheet.Action.Step.class, levels.get(3).action());
        assertEquals("dungeontrain editor stage maxlevel desert inc", max.inc());
        // Every band a button; the plain lap letters between the groups are labels.
        List<TemplateDataSheet.Cell> bands = lines.get(2).cells().stream().filter(c -> c.action() != null).toList();
        assertEquals(LapBand.values().length, bands.size(), "one button per band");
        TemplateDataSheet.Cell overworld = bands.get(0);
        assertTrue(overworld.on());
        assertEquals("dungeontrain editor stage phase desert v_overworld_1 off",
            assertInstanceOf(TemplateDataSheet.Action.Run.class, overworld.action()).command());
        TemplateDataSheet.Cell nether = bands.get(1);
        assertFalse(nether.on());
        assertEquals("dungeontrain editor stage phase desert v_nether on",
            assertInstanceOf(TemplateDataSheet.Action.Run.class, nether.action()).command());
        assertEquals("1", lines.get(3).cells().get(0).text(), "one linked part");
        assertEquals("4", lines.get(4).cells().get(0).text(), "templates as counted by the caller");
    }
}
