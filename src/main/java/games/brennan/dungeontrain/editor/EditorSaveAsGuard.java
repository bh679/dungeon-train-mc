package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which other plots a Save-as would reload from disk while they hold unsaved edits.
 *
 * <p>A new name joins its kind's row, and for most kinds that moves every plot after it — which the
 * copy handles by restamping them from their saved templates. A plot with unsaved edits would lose
 * them there, silently, so Save-as refuses instead and names them. The template being saved is not
 * counted: its edits are the point, and they are saved before anything moves.</p>
 *
 * <p>Pure over the dirty scan's answer, so the decision is testable without a world.</p>
 */
public final class EditorSaveAsGuard {

    private EditorSaveAsGuard() {}

    /** The dirty scan's unsaved plots, as {@code category|modelId} keys. */
    public static Set<String> unsavedKeys(ServerLevel level, CarriageDims dims) {
        Set<String> out = new HashSet<>();
        for (EditorDirtyCheck.DirtyEntry e : EditorDirtyCheck.findDirty(level, dims)) {
            if (e.isUnsaved()) out.add(key(e.categoryId(), e.modelId()));
        }
        return out;
    }

    /**
     * The members of {@code reloaded} (dirty-scan model ids in {@code categoryId}) that are unsaved,
     * leaving out {@code sourceModelId}.
     */
    public static List<String> unsavedAmong(String categoryId, Collection<String> reloaded,
                                            String sourceModelId, Set<String> unsaved) {
        List<String> out = new ArrayList<>();
        if (categoryId == null || reloaded == null) return out;
        for (String id : reloaded) {
            if (id == null || id.equals(sourceModelId)) continue;
            if (unsaved.contains(key(categoryId, id))) out.add(id);
        }
        return out;
    }

    static String key(String categoryId, String modelId) {
        return categoryId + "|" + modelId;
    }
}
