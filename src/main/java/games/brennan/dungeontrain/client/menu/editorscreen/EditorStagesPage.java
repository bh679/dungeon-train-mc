package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.ClientStages;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.net.EditorRosterPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Stages tab's rows: one per Stage, {@code name · gate · N blocks}. Pure — the pane paints
 * them and the tests read them.
 *
 * <p>Rows keep to the editor screen's shape: a name, one summary cell, one count. Editing a stage's
 * gate stays on the world-space Stages panel and the {@code editor stage} commands; this list is for
 * finding a stage and seeing what it is built from.</p>
 */
public final class EditorStagesPage {

    /** One stage's row and the id its click selects. */
    public record Row(CommandMenuEntry entry, String stageId) {}

    /** Column bounds as shares of the row: name to 0.55, gate to 0.84, count to the end. */
    static final List<Double> BOUNDS = List.of(0.55, 0.84);
    static final int NAME_CELL = 0;
    static final int GATE_CELL = 1;
    static final int COUNT_CELL = 2;

    private EditorStagesPage() {}

    public static List<Row> rows(List<EditorRosterPacket.StageEntry> stages, Consumer<String> select) {
        List<Row> out = new ArrayList<>(stages.size());
        for (EditorRosterPacket.StageEntry s : stages) {
            String id = s.id();
            CommandMenuEntry name = new CommandMenuEntry.ClientAction(s.name(), () -> select.accept(id), false);
            CommandMenuEntry gate = new CommandMenuEntry.Label(gateSummary(s));
            CommandMenuEntry count = new CommandMenuEntry.Label(
                EditorScreenLang.text(EditorScreenLang.STAGES_BLOCKS, s.totalUnique()));
            out.add(new Row(new CommandMenuEntry.Cells(List.of(name, gate, count), BOUNDS), id));
        }
        return out;
    }

    /** {@code lvl 10..all · VUC} — the same summary the world-space stage picker shows. */
    public static String gateSummary(EditorRosterPacket.StageEntry s) {
        return ClientStages.gateSummary(new ClientStages.Info(
            s.id(), s.name(), s.stage().minLevel(), s.stage().maxLevel(), s.stage().phaseMask()));
    }
}
