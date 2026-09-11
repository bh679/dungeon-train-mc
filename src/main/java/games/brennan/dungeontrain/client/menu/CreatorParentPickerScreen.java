package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.builder.relay.BuilderRelaySubVariant;
import games.brennan.dungeontrain.client.menu.editorscreen.CreatorLoadParent;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterClient;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterIndex;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorScreenLang;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.track.variant.TrackKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Load under…" — which variant parent a player's build is filed beneath when it is loaded.
 *
 * <p>Lists every top-level template of the category that could hold a sub-variant, the way
 * {@link GroupParentPickerScreen} does for a move, plus <b>User builds</b> marked <i>(new)</i> when
 * this install has no such parent yet — picking it is a promise the load keeps by creating the group.
 * A row that is somebody's member is not offered (nesting is single-hop), nor is the synthetic
 * {@code default} room.</p>
 *
 * <p>Picking a row only remembers the choice ({@link CreatorLoadParent}); nothing is sent until the
 * reviewer presses Load. The screen holds no state of its own, so it reads the roster afresh every
 * time it is drawn.</p>
 */
public final class CreatorParentPickerScreen implements MenuScreen {

    private final PlotCategory category;
    private final Runnable onPicked;

    /**
     * @param onPicked what to do once a row is chosen — the host pops the modal; a picker that
     *                 stayed open after answering would look like it had not heard
     */
    public CreatorParentPickerScreen(PlotCategory category, Runnable onPicked) {
        this.category = category;
        this.onPicked = onPicked == null ? () -> { } : onPicked;
    }

    @Override
    public String title() {
        return EditorScreenLang.text(EditorScreenLang.CREATOR_PARENT_TITLE);
    }

    @Override
    public List<CommandMenuEntry> entries() {
        return entries(EditorRosterClient.index());
    }

    /** As {@link #entries()} against a given roster — the seam the test uses. */
    public List<CommandMenuEntry> entries(EditorRosterIndex index) {
        List<CommandMenuEntry> out = new ArrayList<>();
        String chosen = CreatorLoadParent.parentFor(category);
        List<EditorTypeMenusPacket.Variant> parents = candidateParents(index);
        boolean hasDefault = parents.stream()
            .anyMatch(v -> BuilderRelaySubVariant.DEFAULT_PARENT_ID.equalsIgnoreCase(v.modelName()));
        if (!hasDefault) {
            // The default before it exists: the load creates it, so it is offered as what it will be.
            out.add(row(EditorScreenLang.text(EditorScreenLang.CREATOR_PARENT_NEW,
                    BuilderRelaySubVariant.DEFAULT_PARENT_LABEL),
                BuilderRelaySubVariant.DEFAULT_PARENT_ID, chosen));
        }
        for (EditorTypeMenusPacket.Variant parent : parents) {
            String id = parent.modelName();
            String label = parent.displayName() + (parent.isLabelled() ? "  ·  " + id : "");
            out.add(row(label, id, chosen));
        }
        out.add(new CommandMenuEntry.Back(EditorScreenLang.text(EditorScreenLang.MOVE_BACK)));
        return out;
    }

    private CommandMenuEntry row(String label, String id, String chosen) {
        return new CommandMenuEntry.ClientAction(label, () -> {
            CreatorLoadParent.set(category, id);
            onPicked.run();
        }, id.equalsIgnoreCase(chosen));
    }

    /**
     * Top-level rows of this category. Members never appear as top-level rows (the server files them
     * under their parent), so single-hop nesting is honoured by construction.
     */
    public List<EditorTypeMenusPacket.Variant> candidateParents(EditorRosterIndex index) {
        List<EditorTypeMenusPacket.Variant> out = new ArrayList<>();
        if (index == null) return out;
        for (EditorRosterIndex.Tile tile : index.allTiles()) {
            if (tile.key().category() != category) continue;
            if (tile.key().isSubVariant()) continue;
            String id = tile.variant().modelName().toLowerCase(Locale.ROOT);
            // The synthetic default room is a fallback, never a parent.
            if (category == PlotCategory.PORTALS && TrackKind.DEFAULT_NAME.equals(id)) continue;
            out.add(tile.variant());
        }
        return out;
    }
}
