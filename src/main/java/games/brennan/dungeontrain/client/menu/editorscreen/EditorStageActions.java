package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.ConfirmScreen;
import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.worldgen.TrainPhase;

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

    /** The bands row's bounds: a title cell, then one cell per phase. */
    static final List<Double> BAND_BOUNDS = bandBounds();

    private EditorStageActions() {}

    /**
     * Refresh · Apply · Rename | Duplicate · Delete | Previous · Next.
     *
     * @param stage    the shown stage
     * @param applyTo  the template the Apply button links to the stage, or null for none selected
     * @param canPage  whether there is more than one template to page the model through
     * @param refresh  what Refresh does on the client (re-roll the model shown)
     * @param step     what Previous / Next do on the client ({@code -1} / {@code +1})
     */
    public static List<EditorScreenActions.Icon> icons(EditorRosterPacket.StageEntry stage, VariantKey applyTo,
                                                       boolean canPage, Runnable refresh,
                                                       java.util.function.IntConsumer step) {
        List<EditorScreenActions.Icon> out = new ArrayList<>(7);
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
            EditorScreenLang.STAGES_ONE_TEMPLATE));
        out.add(new EditorScreenActions.Icon(NEXT, EditorScreenLang.STAGES_ICON_NEXT,
            canPage ? new CommandMenuEntry.ClientAction(NEXT, () -> step.accept(+1), false) : null,
            EditorScreenLang.STAGES_ONE_TEMPLATE));
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

    /** Min Lv · Max Lv · Bands — the rows under the model. */
    public static List<CommandMenuEntry> settingRows(EditorRosterPacket.StageEntry stage) {
        List<CommandMenuEntry> out = new ArrayList<>(3);
        int min = stage.stage().minLevel();
        int max = stage.stage().maxLevel();
        out.add(levelRow(stage.id(), "minlevel",
            EditorScreenLang.text(EditorScreenLang.STAGES_MIN_LEVEL, min), "0-1000"));
        out.add(levelRow(stage.id(), "maxlevel",
            EditorScreenLang.text(EditorScreenLang.STAGES_MAX_LEVEL, max < 0 ? EditorStagesPage.OPEN_MAX : Integer.toString(max)),
            "-1..1000"));
        out.add(bandsRow(stage.id(), stage.stage().phaseMask()));
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

    /** {@code Bands · O · N · V · E · U · C}: each letter a lit toggle for its dimension. */
    static CommandMenuEntry bandsRow(String stageId, int phaseMask) {
        List<CommandMenuEntry> cells = new ArrayList<>(TrainPhase.values().length + 1);
        cells.add(new CommandMenuEntry.Label(EditorScreenLang.text(EditorScreenLang.STAGES_BANDS)));
        for (TrainPhase p : TrainPhase.values()) {
            boolean on = (phaseMask & p.bit()) != 0;
            cells.add(new CommandMenuEntry.Stay(p.letter(),
                EditorPlotTeleport.stagePhaseCommandFor(stageId, p.token(), on ? "off" : "on"), on));
        }
        return new CommandMenuEntry.Cells(cells, BAND_BOUNDS);
    }

    /** The title cell's right edge, then the edges between the phase cells: one boundary per gap. */
    private static List<Double> bandBounds() {
        int n = TrainPhase.values().length;
        double title = 0.34;
        List<Double> out = new ArrayList<>(n);
        out.add(title);
        for (int i = 1; i < n; i++) out.add(title + (1.0 - title) * i / n);
        return List.copyOf(out);
    }
}
