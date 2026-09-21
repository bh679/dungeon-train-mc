package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.BuilderRelaySubVariant;
import games.brennan.dungeontrain.editor.PlotCategory;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which variant parent a player's build is filed under when it is loaded into the editor.
 *
 * <p>One remembered choice per category, and {@link BuilderRelaySubVariant#DEFAULT_PARENT_ID} until
 * the reviewer picks another — so a run of loads lands in one place without a pick per build, and a
 * pick made for contents does not quietly move where the next room goes. Session-scoped like the
 * relay light: it is a reviewing convenience, and a stale parent that no longer exists on this
 * install would be worse than starting from the default.</p>
 */
public final class CreatorLoadParent {

    private static final Map<PlotCategory, String> CHOSEN = new EnumMap<>(PlotCategory.class);

    private CreatorLoadParent() {}

    /** The sentinel a category remembers when the build should land in the Whole room pool. */
    public static final String WHOLE_ROOM = games.brennan.dungeontrain.builder.relay.BuilderRelayWholeRoom.WHOLE_ROOM_PARENT;

    /** Whether a relay build of {@code kind} has a destination to choose — a parent or the Whole pool. */
    public static boolean supports(String kind) {
        return BuilderRelayKinds.CONTENTS.equals(kind) || BuilderRelayKinds.PORTAL_ROOM.equals(kind)
            // A carriage has no parents to land under, but it can land in the Whole pool.
            || BuilderRelayKinds.CARRIAGE.equals(kind);
    }

    /** Whether {@code category}'s builds can be loaded as a whole room — carriages and contents. */
    public static boolean offersWholeRoom(PlotCategory category) {
        return category == PlotCategory.CARRIAGES || category == PlotCategory.CONTENTS;
    }

    /** The parent id builds of {@code category} land under — the default until one is chosen. */
    public static synchronized String parentFor(PlotCategory category) {
        if (category == null) return BuilderRelaySubVariant.DEFAULT_PARENT_ID;
        // A carriage's default is the top level — it has no sub-variant parents at all.
        String fallback = category == PlotCategory.CARRIAGES ? "" : BuilderRelaySubVariant.DEFAULT_PARENT_ID;
        return CHOSEN.getOrDefault(category, fallback);
    }

    /** Remember {@code parentId} for {@code category}; blank goes back to the default. */
    public static synchronized void set(PlotCategory category, String parentId) {
        if (category == null) return;
        if (parentId == null || parentId.isBlank()) {
            CHOSEN.remove(category);
        } else {
            CHOSEN.put(category, parentId.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * What the parent button says: the roster's label for the chosen parent when this install has it,
     * else the default's two words — the parent does not exist yet, and the load will make it.
     */
    public static String labelFor(PlotCategory category, EditorRosterIndex index) {
        String id = parentFor(category);
        if (WHOLE_ROOM.equals(id)) return EditorScreenLang.text(EditorScreenLang.CREATOR_PARENT_WHOLE_ROOM);
        if (id.isEmpty()) return EditorScreenLang.text(EditorScreenLang.CREATOR_PARENT_TOP_LEVEL);
        if (index != null) {
            for (EditorRosterIndex.Tile tile : index.allTiles()) {
                if (tile.key().category() != category || tile.key().isSubVariant()) continue;
                if (tile.variant().modelName().equalsIgnoreCase(id)) return tile.variant().displayName();
            }
        }
        return BuilderRelaySubVariant.DEFAULT_PARENT_ID.equals(id)
            ? BuilderRelaySubVariant.DEFAULT_PARENT_LABEL : id;
    }

    /** Forget every choice — on logout, with the rest of the session's relay state. */
    public static synchronized void reset() {
        CHOSEN.clear();
    }
}
