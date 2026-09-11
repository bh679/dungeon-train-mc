package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Confirmation for removing a template that has sub-variants — the three-way version of
 * {@link ConfirmScreen}. A parent's group sidecar is the only place its members' weights, gates and
 * Stage links live, so a plain Yes/No cannot say what happens to them; this screen makes the author
 * choose:
 * <ul>
 *   <li><b>Delete all</b> — the sub-variants go with the parent.</li>
 *   <li><b>Delete parent &amp; unparent</b> — the sub-variants become top-level templates.</li>
 *   <li><b>Delete parent, parent to first</b> — the first sub-variant heads the rest.</li>
 * </ul>
 * Each row runs {@code baseCommand} with the matching server mode word appended
 * ({@code all} / {@code unparent} / {@code promote} — see {@code editor/ParentDeletes.Mode}).
 */
public final class ParentRemoveConfirmScreen implements MenuScreen {

    /** Server literals for the three modes, in row order. */
    public static final String MODE_ALL = "all";
    public static final String MODE_UNPARENT = "unparent";
    public static final String MODE_PROMOTE = "promote";

    private final String title;
    private final String baseCommand;
    private final int subVariantCount;
    private final String firstSubVariantLabel;

    /**
     * @param modelLabel           what the title calls the parent
     * @param baseCommand          the reset command without its mode word
     * @param subVariantCount      how many members the parent has (shown in the rows)
     * @param firstSubVariantLabel the member that would take over under "promote"
     */
    public ParentRemoveConfirmScreen(String modelLabel, String baseCommand,
                                     int subVariantCount, String firstSubVariantLabel) {
        this.title = "Remove '" + modelLabel + "'? It has " + subVariantCount
            + " sub-variant" + (subVariantCount == 1 ? "" : "s");
        this.baseCommand = baseCommand;
        this.subVariantCount = subVariantCount;
        this.firstSubVariantLabel = firstSubVariantLabel;
    }

    @Override public String title() { return title; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run("Delete all (" + subVariantCount + " sub-variant"
                + (subVariantCount == 1 ? "" : "s") + " too)", command(MODE_ALL)),
            new CommandMenuEntry.Run("Delete parent & unparent", command(MODE_UNPARENT)),
            new CommandMenuEntry.Run("Delete parent, parent to '" + firstSubVariantLabel + "'",
                command(MODE_PROMOTE)),
            new CommandMenuEntry.Back("Cancel")
        );
    }

    /** The command a row runs — exposed for tests. */
    public String command(String mode) {
        return baseCommand + " " + mode;
    }
}
