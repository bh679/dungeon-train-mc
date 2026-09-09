package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterClient;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterIndex;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorScreenLang;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Move to…" — where a template sits in the sub-variant tree, chosen from a list.
 *
 * <p>Lists <b>Top level</b> plus every template of the same category that could hold this one as a
 * sub-variant: the top-level rows, minus the template itself and minus its current parent. A row
 * that is itself somebody's member is not offered (nesting is single-hop, and the server would
 * refuse). Picking a row runs the one group command that gets there — {@code move} between two
 * parents, {@code remove} up to top level, {@code add} down under a parent — so the member's weight,
 * gate and Stage links travel with it and no file moves.</p>
 *
 * <p>Built from the client's copy of the roster, which is what the screen already shows, so what is
 * offered is exactly what the author can see. The roster refreshes itself after the command lands
 * (the server re-sends it on every group change), which is why this screen holds no state.</p>
 */
public final class GroupParentPickerScreen implements MenuScreen {

    private final PlotCategory category;
    /** The template being moved — its id, never its label. */
    private final String childId;
    /** Its current parent id, or {@code ""} when it is top-level today. */
    private final String currentParentId;

    public GroupParentPickerScreen(PlotCategory category, String childId, String currentParentId) {
        this.category = category;
        this.childId = childId == null ? "" : childId.toLowerCase(Locale.ROOT);
        this.currentParentId = currentParentId == null ? "" : currentParentId.toLowerCase(Locale.ROOT);
    }

    /** Whether {@code category} has sub-variant groups at all — the only two that do today. */
    public static boolean supports(PlotCategory category) {
        return category == PlotCategory.CONTENTS || category == PlotCategory.PORTALS;
    }

    @Override
    public String title() {
        return EditorScreenLang.text(EditorScreenLang.MOVE_TITLE);
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        boolean topLevelNow = currentParentId.isEmpty();
        if (!topLevelNow) {
            out.add(new CommandMenuEntry.Run(EditorScreenLang.text(EditorScreenLang.MOVE_TOP_LEVEL),
                promoteCommand(), false));
        }
        int offered = 0;
        for (EditorTypeMenusPacket.Variant parent : candidateParents()) {
            String id = parent.modelName();
            String label = parent.displayName() + (parent.isLabelled() ? "  ·  " + id : "");
            out.add(new CommandMenuEntry.Run(label, moveCommand(id), false));
            offered++;
        }
        if (offered == 0 && topLevelNow) {
            out.add(new CommandMenuEntry.Label(EditorScreenLang.text(EditorScreenLang.MOVE_NO_TARGETS)));
        }
        out.add(new CommandMenuEntry.Back(EditorScreenLang.text(EditorScreenLang.MOVE_BACK)));
        return out;
    }

    /**
     * Top-level rows of this category that are not the child and not its current parent. Members
     * never appear as top-level rows (the server files them under their parent), so single-hop
     * nesting is honoured by construction.
     */
    List<EditorTypeMenusPacket.Variant> candidateParents() {
        return candidateParents(EditorRosterClient.index());
    }

    /** As {@link #candidateParents()} against a given roster — the seam the test uses. */
    public List<EditorTypeMenusPacket.Variant> candidateParents(EditorRosterIndex index) {
        List<EditorTypeMenusPacket.Variant> out = new ArrayList<>();
        if (index == null) return out;
        for (EditorRosterIndex.Tile tile : index.allTiles()) {
            if (tile.key().category() != category) continue;
            if (tile.key().isSubVariant()) continue;
            String id = tile.variant().modelName().toLowerCase(Locale.ROOT);
            if (id.equals(childId) || id.equals(currentParentId)) continue;
            // The synthetic default room is a fallback, never a parent.
            if (category == PlotCategory.PORTALS && TrackKind.DEFAULT_NAME.equals(id)) continue;
            out.add(tile.variant());
        }
        return out;
    }

    // ---------- commands ----------

    /** Between two parents, or down under one from top level. */
    public String moveCommand(String newParentId) {
        return currentParentId.isEmpty()
            ? groupCommand("add", newParentId, childId)
            : groupCommand("move", childId, newParentId);
    }

    /** Up to top level. */
    public String promoteCommand() {
        return groupCommand("remove", currentParentId, childId);
    }

    private String groupCommand(String verb, String a, String b) {
        String root = category == PlotCategory.PORTALS
            ? "dungeontrain editor portals group "
            : "dungeontrain editor contents group ";
        return root + verb + " " + a + " " + b;
    }

    /** The roster group a tile belongs to, so a caller can tell which page to return to. */
    static EditorRosterPacket.Group groupOf(EditorRosterIndex index, EditorRosterIndex.Tile tile) {
        return index == null || tile == null ? null : index.groupOf(tile.key());
    }
}
