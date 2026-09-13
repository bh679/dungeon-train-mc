package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.ClientStages;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.net.EditorRosterPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Stages tab's rows: one titled row per Stage. Pure — the pane paints them and the tests read
 * them.
 *
 * <p>A row is the stage's name and nothing else: what a stage gates and what it is built from is
 * the preview's business, and editing its gate stays on the world-space Stages panel and the
 * {@code editor stage} commands. This list is for finding a stage.</p>
 */
public final class EditorStagesPage {

    /** One stage's row and the id its click selects. */
    public record Row(CommandMenuEntry entry, String stageId) {}

    private EditorStagesPage() {}

    public static List<Row> rows(List<EditorRosterPacket.StageEntry> stages, Consumer<String> select) {
        List<Row> out = new ArrayList<>(stages.size());
        for (EditorRosterPacket.StageEntry s : stages) {
            String id = s.id();
            out.add(new Row(new CommandMenuEntry.ClientAction(s.name(), () -> select.accept(id), false), id));
        }
        return out;
    }

    /** {@code lvl 10..all · VUC} — the same summary the world-space stage picker shows. */
    public static String gateSummary(EditorRosterPacket.StageEntry s) {
        return ClientStages.gateSummary(new ClientStages.Info(
            s.id(), s.name(), s.stage().minLevel(), s.stage().maxLevel(), s.stage().phaseMask()));
    }
}
