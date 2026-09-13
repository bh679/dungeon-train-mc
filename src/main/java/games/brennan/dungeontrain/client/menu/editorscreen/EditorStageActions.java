package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.ConfirmScreen;
import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * The stage preview's overview page, as entries: the icon row over the model and the gate rows
 * under it. Pure, so what each button sends can be pinned without a screen.
 *
 * <p>Every action is one of the {@code editor stage} commands the world-space Stages panel already
 * sends, so the two surfaces cannot disagree about what a stage edit is.</p>
 */
public final class EditorStageActions {

    static final String REFRESH = "refresh";
    static final String APPLY = "apply";
    static final String RENAME = "rename";
    static final String DUPLICATE = "duplicate";
    static final String DELETE = "delete";
    static final String PREV = "prev";
    static final String NEXT = "next";
    static final String REBAKE = "rebake";

    private EditorStageActions() {}

    /**
     * Refresh · Apply · Rename | Duplicate · Delete | Previous · Next | Re-bake.
     *
     * @param stage    the shown stage
     * @param applyTo  the template the Apply button links to the stage, or null for none selected
     * @param canPage  whether there is more than one carriage to page the model through
     * @param refresh  what Refresh does on the client (re-roll the model's block variants)
     * @param step     what Previous / Next do on the client ({@code -1} / {@code +1})
     */
    public static List<EditorScreenActions.Icon> icons(EditorRosterPacket.StageEntry stage, VariantKey applyTo,
                                                       boolean canPage, Runnable refresh,
                                                       java.util.function.IntConsumer step) {
        List<EditorScreenActions.Icon> out = new ArrayList<>(8);
        String id = stage.id();
        out.add(new EditorScreenActions.Icon(REFRESH, EditorScreenLang.STAGES_ICON_REFRESH,
            new CommandMenuEntry.ClientAction(EditorScreenLang.text(EditorScreenLang.STAGES_ICON_REFRESH), refresh, false),
            null));
        String apply = applyCommand(applyTo, id);
        out.add(new EditorScreenActions.Icon(APPLY, EditorScreenLang.STAGES_ICON_APPLY,
            apply == null ? null : new CommandMenuEntry.Stay(APPLY, apply),
            EditorScreenLang.STAGES_APPLY_NONE,
            applyTo == null ? null : applyTo.displayName()));
        out.add(new EditorScreenActions.Icon(RENAME, EditorScreenLang.STAGES_ICON_RENAME,
            new CommandMenuEntry.TypeArg(EditorScreenLang.text(EditorScreenLang.STAGES_ICON_RENAME), "name",
                "dungeontrain editor stage rename " + id, "", stage.name()),
            null));
        out.add(new EditorScreenActions.Icon(DUPLICATE, EditorScreenLang.STAGES_ICON_DUPLICATE,
            new CommandMenuEntry.Stay(DUPLICATE, "dungeontrain editor stage duplicate " + id), null));
        out.add(new EditorScreenActions.Icon(DELETE, EditorScreenLang.STAGES_ICON_DELETE,
            new CommandMenuEntry.DrillIn(EditorScreenLang.text(EditorScreenLang.STAGES_ICON_DELETE),
                new ConfirmScreen(EditorScreenLang.text(EditorScreenLang.STAGES_DELETE_CONFIRM, stage.name()),
                    "dungeontrain editor stage delete " + id)),
            null));
        out.add(new EditorScreenActions.Icon(PREV, EditorScreenLang.STAGES_ICON_PREV,
            canPage ? new CommandMenuEntry.ClientAction(PREV, () -> step.accept(-1), false) : null,
            EditorScreenLang.STAGES_ONE_CARRIAGE));
        out.add(new EditorScreenActions.Icon(NEXT, EditorScreenLang.STAGES_ICON_NEXT,
            canPage ? new CommandMenuEntry.ClientAction(NEXT, () -> step.accept(+1), false) : null,
            EditorScreenLang.STAGES_ONE_CARRIAGE));
        // Re-derive the placeholder palette from the linked parts; overrides and locks are kept.
        out.add(new EditorScreenActions.Icon(REBAKE, EditorScreenLang.STAGES_ICON_REBAKE,
            new CommandMenuEntry.Stay(REBAKE, "dungeontrain editor stage bake " + id), null));
        return out;
    }

    /**
     * The command linking {@code target} to the stage, or null when nothing gateable is selected:
     * parts link through their own packet, and track-side groups have no member verb.
     */
    static String applyCommand(VariantKey target, String stageId) {
        if (target == null || target.category() == null) return null;
        if (target.isSubVariant()) {
            return target.category() == PlotCategory.CONTENTS
                ? EditorPlotTeleport.groupMemberStageApplyCommandFor(target.parentId(), target.modelName(), stageId)
                : null;
        }
        return EditorPlotTeleport.stageApplyCommandFor(target.category(), target.modelId(), target.modelName(), stageId);
    }

    /**
     * The sheet under the model: the gate as the template sheet shows a Stage's — {@code Lv [min]
     * — [max] · O N V E U C}, bounds that step and letters that toggle — then what links to it.
     */
    public static List<TemplateDataSheet.Line> sheetLines(EditorRosterPacket.StageEntry stage,
                                                          int templateCount, boolean devMode) {
        List<TemplateDataSheet.Line> out = new ArrayList<>(6);
        String id = stage.id();
        int min = stage.stage().minLevel();
        int max = stage.stage().maxLevel();
        out.add(TemplateDataSheet.builderLine(stage.stage(),
            devMode ? "dungeontrain editor stage builder " + id : null));
        out.add(new TemplateDataSheet.Line(EditorScreenLang.text(EditorScreenLang.SHEET_SPAWNS),
            TemplateDataSheet.levelCells(
                min, TemplateDataSheet.Stepper.of(levelRow(id, "minlevel",
                    EditorScreenLang.text(EditorScreenLang.STAGES_MIN_LEVEL, min), "0-1000")),
                max, TemplateDataSheet.Stepper.of(levelRow(id, "maxlevel",
                    EditorScreenLang.text(EditorScreenLang.STAGES_MAX_LEVEL,
                        max < 0 ? EditorScreenLang.text(EditorScreenLang.SHEET_LEVELS_ALL) : Integer.toString(max)),
                    "-1..1000")))));
        out.add(TemplateDataSheet.bandsLine(stage.stage().phaseMask(),
            (p, on) -> EditorPlotTeleport.stagePhaseCommandFor(id, p.token(), on ? "off" : "on")));
        out.add(TemplateDataSheet.Line.of(EditorScreenLang.text(EditorScreenLang.STAGES_PARTS_LABEL),
            Integer.toString(stage.partCount())));
        out.add(TemplateDataSheet.Line.of(EditorScreenLang.text(EditorScreenLang.STAGES_TEMPLATES_LABEL),
            Integer.toString(templateCount)));
        out.add(TemplateDataSheet.Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_BLOCKS),
            Integer.toString(stage.totalUnique())));
        return out;
    }

    /** {@code - · Min Lv (n) · +}: the ends step, the middle takes a typed value. */
    static CommandMenuEntry levelRow(String stageId, String sub, String label, String hint) {
        return new CommandMenuEntry.Triple(
            new CommandMenuEntry.Stay("-", EditorPlotTeleport.stageLevelCommandFor(stageId, sub, "dec")),
            new CommandMenuEntry.TypeArg(label, hint, "dungeontrain editor stage " + sub + " " + stageId),
            new CommandMenuEntry.Stay("+", EditorPlotTeleport.stageLevelCommandFor(stageId, sub, "inc")),
            0.10, 0.90);
    }
}
